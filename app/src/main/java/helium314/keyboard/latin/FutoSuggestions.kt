// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.futo.FutoSwipeInput
import helium314.keyboard.latin.futo.FutoSwipeLayout
import helium314.keyboard.latin.futo.FutoSwipeMode
import helium314.keyboard.latin.futo.FutoSwipeModels
import helium314.keyboard.latin.futo.FutoSwipeRuntime
import helium314.keyboard.latin.futo.FutoSwipeVocabulary
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SuggestionResults
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Thin HeliBoard adapter around the FUTO runtime.
 *
 * Vocabulary and model loads never run while this object's monitor is held: they run on the "FutoSwipeLoader" thread
 * (language switch, keyboard start) or, when a gesture arrives before its language is loaded, on the gesture's thread
 * while it waits outside the monitor. A change of key geometry only re-sets the layout of the loaded language.
 *
 * Personal-dictionary words come only from [helium314.keyboard.latin.dictionary.UserBinaryDictionary], which reads
 * the Android user dictionary for its locale and publishes the words here after each load. A change of the user
 * dictionary withdraws that locale's words at once, so that a removed word is not offered, until the reload
 * publishes the new list. The list is written to a small .combined file and loaded as a second trie on the loader
 * thread; a gesture that arrives first loads it outside the monitor, like a language.
 */
object FutoSuggestions {
    private const val TAG = "FutoSuggestions"
    private const val ENCODER = "futo-swipe/honorable_sturgeon/model_fp32.pte"
    private const val ENCODER_METADATA = "futo-swipe/honorable_sturgeon/metadata.json"
    private const val DECODER = "futo-swipe/magic_macaw/model_fp32.pte"
    private const val DECODER_METADATA = "futo-swipe/magic_macaw/metadata.json"
    private const val CONTEXT_LM = "futo-swipe/hungry_jellyfish/context_lm.pte"
    private const val CONTEXT_LM_METADATA = "futo-swipe/hungry_jellyfish/metadata.json"
    private const val CONTEXT_VOCAB = "futo-swipe/hungry_jellyfish/vocab.txt"
    private const val EN_VOCAB = "futo-vocab/main_en_US.combined"
    private const val RU_VOCAB = "futo-vocab/main_ru.combined"

    /** Whether this keyboard can use FUTO; readiness is handled by the existing pending load. */
    fun supportsGesture(keyboard: Keyboard?, locale: Locale): Boolean =
        keyboard != null && Shape.of(keyboard, locale) != null

    @Volatile private var context: Context? = null
    @Volatile private var runtime: FutoSwipeRuntime? = null
    // guarded by this object's monitor
    private var geometry: String? = null
    private var generation = 0L

    @Volatile private var latestPreload: Shape? = null
    private val loader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FutoSwipeLoader").apply { isDaemon = true }
    }
    private val assetLock = Any()
    private val materialized = ConcurrentHashMap<String, File>()
    private val failures = FutoFailureLog { Log.w(TAG, it) }

    // Personal words by locale (Locale.toString()), guarded by personalLock. A locale's token changes with every
    // withdrawal, so that a load that started before a change cannot publish its older list.
    private val personalLock = Any()
    private var personalCount = 0L
    private val personalTokens = HashMap<String, Long>()
    private val personalFiles = HashMap<String, File>()

    @Synchronized
    fun init(context: Context) {
        this.context = context.applicationContext
        if (runtime == null) runtime = FutoSwipeRuntime { failures.report("personal", it) }
    }

    /**
     * The personal words of [locale] may have changed: they stop being offered now. A load that starts after this
     * call reads its token with [personalWordsToken].
     */
    fun personalWordsChanged(locale: Locale) {
        synchronized(personalLock) {
            personalTokens[locale.toString()] = ++personalCount
            personalFiles.remove(locale.toString())
        }
    }

    /** The token to pass to [setPersonalWords] by a load of [locale]'s personal words that starts now. */
    fun personalWordsToken(locale: Locale): Long = synchronized(personalLock) { personalTokens[locale.toString()] ?: 0L }

    /**
     * Publishes the personal words of [locale] (word to frequency, 0-255) that a load with [token] read. Ignored when
     * the words changed after that load started. The file is written and loaded on the loader thread.
     */
    fun setPersonalWords(locale: Locale, token: Long, words: Map<String, Int>) {
        val key = locale.toString()
        loader.execute {
            val appContext = context ?: return@execute
            try {
                val text = personalCombined(words)
                val dir = File(appContext.filesDir, "futo/personal")
                val file = synchronized(personalLock) {
                    if ((personalTokens[key] ?: 0L) != token) return@execute
                    // Files of withdrawn or replaced lists; a list in use stays until the next publication.
                    dir.listFiles()?.forEach { if (it !in personalFiles.values) it.delete() }
                    if (text.isEmpty()) {
                        personalFiles.remove(key)
                        null
                    } else {
                        dir.mkdirs()
                        File(dir, "personal-${++personalCount}.combined").also {
                            it.writeText(text, Charsets.UTF_8)
                            personalFiles[key] = it
                        }
                    }
                }
                Log.i(TAG, "futo personal words: locale=$key count=${text.count { it == '\n' }}")
                // Load now for the language in use, so that its next gesture does not load it.
                val shape = latestPreload?.takeIf { it.localeKey == key && file != null } ?: return@execute
                val active = runtime ?: return@execute
                active.preload(shape.withPersonal(file?.path).mode(appContext, 0))
                    ?.let { logLoad(shape, it.loadMs, it.warmMs, active) }
            } catch (failure: Throwable) {
                failures.report("personal", failure)
            }
        }
    }

    private fun personalFile(locale: Locale): String? =
        synchronized(personalLock) { personalFiles[locale.toString()]?.path }

    @Synchronized
    fun close() {
        runtime?.close()
        runtime = null
        geometry = null
    }

    /**
     * Loads the vocabulary and models of [locale] on the loader thread, then warms the next-word model.
     * A request that a newer one replaced before the loader reached it is skipped.
     */
    fun preload(keyboard: Keyboard, locale: Locale) {
        val shape = Shape.of(keyboard, locale) ?: return
        latestPreload = shape
        loader.execute {
            if (latestPreload !== shape) return@execute
            val active = runtime ?: return@execute
            val appContext = context ?: return@execute
            try {
                active.preload(shape.mode(appContext, 0), warmUp = true)?.let { logLoad(shape, it.loadMs, it.warmMs, active) }
            } catch (failure: Throwable) {
                if (runtime === active) failures.report("preload", failure)
            }
        }
    }

    fun recognize(
        pointers: InputPointers,
        contextWords: List<String>,
        keyboard: Keyboard,
        locale: Locale,
    ): SuggestionResults? {
        var phase = "prepare"
        return try {
            val active = runtime ?: return null
            val appContext = context ?: return null
            val shape = Shape.of(keyboard, locale) ?: return null
            val count = pointers.pointerSize
            if (count == 0) return null
            val width = keyboard.mOccupiedWidth.toFloat()
            val height = keyboard.mOccupiedHeight.toFloat()
            val x = pointers.xCoordinates.copyOf(count).map { normalizedGestureCoordinate(it, width) }.toFloatArray()
            val y = pointers.yCoordinates.copyOf(count)
                .map { normalizedGestureCoordinate(it, height, 4f / 3f) }.toFloatArray()
            val t = pointers.times.copyOf(count).map(Int::toFloat).toFloatArray()
            phase = "load"
            var waitMs = 0L
            repeat(3) {
                waitMs += ensureLoaded(active, shape, appContext)
                phase = "configure"
                val words = synchronized(this) {
                    val current = activate(active, shape, appContext) ?: return@synchronized null
                    phase = "recognize"
                    active.recognize(FutoSwipeInput(current, x, y, t, contextWords, topK = 8)).words
                } ?: return@repeat
                if (waitMs > 0) Log.i(TAG, "futo gesture waited for load: points=$count waitMs=$waitMs")
                return words.toSuggestionResults(false, SuggestedWordInfo.KIND_CORRECTION)
            }
            error("FUTO swipe language was unloaded three times while waiting")
        } catch (failure: Throwable) {
            failures.report(phase, failure)
            SuggestionResults(1, false, false)
        }
    }

    /**
     * FUTO next-word prediction for English. Returns null (HeliBoard's own predictions remain) while the language is
     * still loading: a prediction never waits for a load, so it cannot hold the worker that also runs gestures.
     */
    fun predictNext(keyboard: Keyboard, locale: Locale, contextWords: List<String>): SuggestionResults? {
        if (locale.language != Locale.ENGLISH.language || contextWords.isEmpty()) return null
        var phase = "prepare"
        return try {
            val active = runtime ?: return null
            val appContext = context ?: return null
            val shape = Shape.of(keyboard, locale) ?: return null
            val mode = shape.mode(appContext, 0)
            if (!active.isLoaded(mode)) {
                preload(keyboard, locale)
                return null
            }
            phase = "configure"
            val words = synchronized(this) {
                val current = activate(active, shape, appContext) ?: run {
                    preload(keyboard, locale)
                    return null
                }
                phase = "predict"
                active.predictNext(current, contextWords, topK = 8).words
            }
            if (words.isEmpty()) null else words.toSuggestionResults(false, SuggestedWordInfo.KIND_PREDICTION)
        } catch (failure: Throwable) {
            failures.report(phase, failure)
            null
        }
    }

    /** Loads the shape's language unless loaded; returns the milliseconds spent waiting or loading here. */
    private fun ensureLoaded(active: FutoSwipeRuntime, shape: Shape, appContext: Context): Long {
        val started = System.nanoTime()
        val load = active.preload(shape.mode(appContext, 0))
        if (load != null) logLoad(shape, load.loadMs, load.warmMs, active)
        return (System.nanoTime() - started) / 1_000_000
    }

    /** Makes the shape current; returns its generation, or null when its language is not loaded. Needs the monitor. */
    private fun activate(active: FutoSwipeRuntime, shape: Shape, appContext: Context): Long? {
        if (geometry == shape.signature) return generation
        if (!active.configure(shape.mode(appContext, generation + 1))) return null
        generation++
        geometry = shape.signature
        return generation
    }

    private fun logLoad(shape: Shape, loadMs: Long, warmMs: Long, active: FutoSwipeRuntime) {
        Log.i(TAG, "futo full load: lang=${shape.languageTag} ms=$loadMs warmMs=$warmMs " +
            "thread=${Thread.currentThread().name} loads=${active.loadCount}")
    }

    private class Shape(
        val languageTag: String,
        val localeKey: String,
        val english: Boolean,
        val vocabAsset: String,
        val letters: String,
        val centerX: FloatArray,
        val centerY: FloatArray,
        private val geometry: String,
        val personal: String?,
    ) {
        val signature = "$geometry|$personal"

        fun withPersonal(path: String?) =
            Shape(languageTag, localeKey, english, vocabAsset, letters, centerX, centerY, geometry, path)

        fun mode(context: Context, generation: Long): FutoSwipeMode {
            materialize(context, ENCODER_METADATA)
            if (english) {
                materialize(context, DECODER_METADATA)
                materialize(context, CONTEXT_LM_METADATA)
            }
            return FutoSwipeMode(
                generation = generation,
                languageTag = languageTag,
                layout = FutoSwipeLayout(letters, centerX, centerY),
                models = FutoSwipeModels(
                    encoderPath = materialize(context, ENCODER).path,
                    decoderPath = if (english) materialize(context, DECODER).path else null,
                    contextLmPath = if (english) materialize(context, CONTEXT_LM).path else null,
                    contextLmVocabPath = if (english) materialize(context, CONTEXT_VOCAB).path else null,
                ),
                vocabularies = listOf(FutoSwipeVocabulary(materialize(context, vocabAsset).path)),
                personal = personal,
            )
        }

        companion object {
            fun of(keyboard: Keyboard, locale: Locale): Shape? {
                val (vocabAsset, script) = when (locale.language) {
                    Locale.ENGLISH.language -> EN_VOCAB to Character.UnicodeScript.LATIN
                    "ru" -> RU_VOCAB to Character.UnicodeScript.CYRILLIC
                    else -> return null
                }
                val keys = keyboard.sortedKeys.filter { Character.isLetter(it.code) }
                if (keys.isEmpty()) return null
                // A keyboard of another language (not yet reloaded after a switch) must not load this vocabulary.
                if (keys.any { Character.UnicodeScript.of(it.code) != script }) return null
                val letters = String(keys.map { Character.toLowerCase(it.code) }.toIntArray(), 0, keys.size)
                val width = keyboard.mOccupiedWidth.toFloat()
                val height = keyboard.mOccupiedHeight.toFloat()
                val centerX = keys.map { (it.x + it.width / 2f) / width }.toFloatArray()
                val centerY = keys.map { min(1f, (it.y + it.height / 2f) / height * (4f / 3f)) }.toFloatArray()
                val geometry = "$vocabAsset|${keyboard.mOccupiedWidth}x${keyboard.mOccupiedHeight}|$letters|" +
                    "${centerX.contentHashCode()}|${centerY.contentHashCode()}"
                return Shape(locale.toLanguageTag(), locale.toString(), locale.language == Locale.ENGLISH.language,
                    vocabAsset, letters, centerX, centerY, geometry, personalFile(locale))
            }
        }
    }

    private fun materialize(context: Context, asset: String): File {
        materialized[asset]?.let { return it }
        synchronized(assetLock) {
            materialized[asset]?.let { return it }
            val target = File(context.filesDir, "futo/$asset")
            val expected = context.assets.open(asset).use { it.available().toLong() }
            val packageTime = File(context.applicationInfo.sourceDir).lastModified()
            if (!target.isFile || target.length() != expected || target.lastModified() != packageTime) {
                target.parentFile?.mkdirs()
                context.assets.open(asset).use { input ->
                    target.outputStream().use(input::copyTo)
                }
                check(target.length() == expected) { "Incomplete asset copy for $asset" }
                check(target.setLastModified(packageTime)) { "Cannot stamp copied asset $asset" }
            }
            materialized[asset] = target
            return target
        }
    }

    private fun List<helium314.keyboard.latin.futo.FutoSwipeWord>.toSuggestionResults(
        beginningOfSentence: Boolean,
        kind: Int,
    ): SuggestionResults {
        val results = SuggestionResults(size.coerceAtLeast(1), beginningOfSentence, false)
        forEachIndexed { rank, candidate ->
            results.add(SuggestedWordInfo(
                candidate.word,
                "",
                1_000_000_000 - rank,
                kind,
                Dictionary.DICTIONARY_APPLICATION_DEFINED,
                SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWordInfo.NOT_A_CONFIDENCE,
            ))
        }
        return results
    }
}

/**
 * Reports a FUTO failure with its phase and exception class only, once per distinct (phase, class) cause.
 * A message or stack trace can carry typed or dictated text, so neither is written.
 */
internal class FutoFailureLog(private val sink: (String) -> Unit) {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun report(phase: String, failure: Throwable) {
        val cause = "phase=$phase class=${failure.javaClass.name}"
        if (seen.add(cause)) sink("FUTO failure: $cause")
    }
}

/**
 * Personal words as raw .combined lines (`word=<word>,f=<0-255>`). The FUTO loader ends a word at its first comma
 * and a line at a line break, so a word with either, or with surrounding blanks, is left out.
 */
internal fun personalCombined(words: Map<String, Int>): String = buildString {
    for ((word, frequency) in words.toSortedMap()) {
        if (word.isBlank() || word != word.trim() || word.any { it == ',' || it == '\n' || it == '\r' }) continue
        append("word=").append(word).append(",f=").append(frequency.coerceIn(0, 255)).append('\n')
    }
}

internal fun normalizedGestureCoordinate(point: Int, extent: Float, scale: Float = 1f): Float =
    (point / extent * scale).coerceIn(0f, 1f)
