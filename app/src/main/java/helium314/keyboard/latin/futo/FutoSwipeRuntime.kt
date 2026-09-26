// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.futo

import org.futo.ml.inference.SwipeDecoder
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture

data class FutoSwipeLayout(
    val letters: String,
    val centerX: FloatArray,
    val centerY: FloatArray,
)

data class FutoSwipeModels(
    val encoderPath: String,
    val decoderPath: String? = null,
    val contextLmPath: String? = null,
    val contextLmVocabPath: String? = null,
)

data class FutoSwipeVocabulary(val path: String, val weight: Float = 1f)

/**
 * [personal] is a small raw .combined file of the owner's personal-dictionary words. It is not part of the loaded
 * language: changing it only swaps a second trie into the loaded engine.
 */
data class FutoSwipeMode(
    val generation: Long,
    val languageTag: String,
    val layout: FutoSwipeLayout,
    val models: FutoSwipeModels,
    val vocabularies: List<FutoSwipeVocabulary>,
    val personal: String? = null,
)

data class FutoSwipeInput(
    val generation: Long,
    val x: FloatArray,
    val y: FloatArray,
    val t: FloatArray,
    val contextWords: List<String> = emptyList(),
    val topK: Int = 4,
    val beamWidth: Int = 100,
)

data class FutoSwipeWord(
    val word: String,
    val score: Float,
    val ctcScore: Float,
    val lmScore: Float,
)

data class FutoSwipeTiming(
    val resampleUs: Float,
    val encoderUs: Float,
    val decoderUs: Float,
    val beamUs: Float,
    val lmUs: Float,
    val totalUs: Float,
)

data class FutoSwipeResult(
    val generation: Long,
    val words: List<FutoSwipeWord>,
    val timing: FutoSwipeTiming,
)

data class FutoNextWordResult(
    val generation: Long,
    val words: List<FutoSwipeWord>,
    val elapsedUs: Long,
)

/** What a full load produced: its time, and the time of the next-word warm-up (-1 when none ran). */
data class FutoSwipeLoad(val loadMs: Long, val warmMs: Long)

/**
 * Serialized owner of the non-thread-safe FUTO swipe engines.
 *
 * A full load (vocabulary tries, encoder, decoder and ContextLM) depends only on the vocabulary, the letters and the
 * models. It runs on the calling thread without holding the runtime lock, so a load never stalls recognition or
 * prediction of the active engine. The loaded engine is swapped in under the lock. A change of key geometry only
 * re-sets the layout of the loaded engine. At most [maxLoaded] engines stay loaded (the two most recently used).
 * The personal trie of a mode is also loaded outside the lock and swapped in under it; removing it is immediate.
 * A personal trie that fails to load is reported to [personalFailure] and swiping goes on without it.
 */
class FutoSwipeRuntime internal constructor(
    private val backend: FutoSwipeBackend,
    private val maxLoaded: Int = 2,
    private val personalFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    constructor(personalFailure: (Throwable) -> Unit = {}) : this(AarFutoSwipeBackend(), personalFailure = personalFailure)

    private val lock = Any()
    private val loaded = LinkedHashMap<LoadKey, FutoSwipeEngine>(4, 0.75f, true)
    private val pending = HashMap<LoadKey, CompletableFuture<Unit>>()
    private val layouts = java.util.IdentityHashMap<FutoSwipeEngine, FutoSwipeLayout>()
    // The personal file each engine was given (also when it failed to load); absent means none.
    private val personals = java.util.IdentityHashMap<FutoSwipeEngine, String>()
    private var active: FutoSwipeEngine? = null
    private var generation: Long? = null
    private var nextWordEnabled = false
    private var closed = false
    private var fullLoads = 0

    /** Number of full loads so far; a change of key geometry is not a full load. */
    val loadCount: Int get() = synchronized(lock) { fullLoads }

    /**
     * Loads the mode's engine unless it is loaded; waits if another thread is loading it. Then gives the engine the
     * mode's personal trie unless it has it.
     * Returns the full load that this call did, or null when no full load was done here.
     * [warmUp] runs one throw-away next-word prediction on the new engine before other threads can use it.
     */
    fun preload(mode: FutoSwipeMode, warmUp: Boolean = false): FutoSwipeLoad? {
        val spec = resolve(mode)
        val load = loadEngine(spec, mode, warmUp)
        if (mode.personal != null) loadPersonal(spec, mode.personal)
        return load
    }

    private fun loadPersonal(spec: Spec, path: String) {
        synchronized(lock) {
            val engine = loaded[spec.key] ?: return
            if (personals[engine] == path) return
        }
        val trie = try {
            backend.loadTrie(path, spec.layout.letters)
        } catch (failure: Throwable) {
            personalFailure(failure)
            null
        }
        synchronized(lock) {
            val engine = loaded[spec.key]
            if (closed || engine == null || personals[engine] == path) {
                trie?.close()
                return
            }
            try {
                engine.setPersonal(trie)
            } catch (failure: Throwable) {
                trie?.close()
                engine.setPersonal(null)
                personalFailure(failure)
            }
            // Marked also after a failure, so that configure does not ask for this file again.
            personals[engine] = path
        }
    }

    private fun loadEngine(spec: Spec, mode: FutoSwipeMode, warmUp: Boolean): FutoSwipeLoad? {
        while (true) {
            val waitFor: CompletableFuture<Unit>?
            val mine: CompletableFuture<Unit>
            synchronized(lock) {
                check(!closed) { "FutoSwipeRuntime has been closed" }
                if (loaded[spec.key] != null) return null
                waitFor = pending[spec.key]
                mine = waitFor ?: CompletableFuture<Unit>().also { pending[spec.key] = it }
            }
            if (waitFor != null) {
                // The other load's failure is its caller's; loop and load here if it left nothing.
                waitFor.handle { _, _ -> }.join()
                continue
            }
            val started = System.nanoTime()
            val engine = try {
                backend.load(spec.layout, spec.models, mode.vocabularies)
            } catch (failure: Throwable) {
                synchronized(lock) { pending.remove(spec.key) }
                mine.completeExceptionally(failure)
                throw failure
            }
            val loadMs = (System.nanoTime() - started) / 1_000_000
            var warmMs = -1L
            if (warmUp && spec.nextWord) {
                val warmStart = System.nanoTime()
                try {
                    engine.predictNext(listOf("the"), 1)
                } catch (_: Throwable) {
                    // A failed warm-up only leaves the first real prediction cold.
                }
                warmMs = (System.nanoTime() - warmStart) / 1_000_000
            }
            synchronized(lock) {
                pending.remove(spec.key)
                if (closed) {
                    engine.close()
                    mine.complete(Unit)
                    throw IllegalStateException("FutoSwipeRuntime has been closed")
                }
                loaded[spec.key] = engine
                layouts[engine] = spec.layout
                fullLoads++
                evictLocked()
            }
            mine.complete(Unit)
            return FutoSwipeLoad(loadMs, warmMs)
        }
    }

    fun isLoaded(mode: FutoSwipeMode): Boolean {
        val key = resolve(mode).key
        return synchronized(lock) { loaded.containsKey(key) }
    }

    /**
     * Makes [mode] current. It never loads: returns false when the mode's engine or personal trie is not loaded, so
     * that the caller can [preload] it outside any lock and try again. A mode without a personal file removes the
     * engine's personal trie at once.
     */
    fun configure(mode: FutoSwipeMode): Boolean {
        val spec = resolve(mode)
        synchronized(lock) {
            check(!closed) { "FutoSwipeRuntime has been closed" }
            val current = generation
            require(current == null || mode.generation > current) {
                "stale layout generation ${mode.generation}; current is $current"
            }
            val engine = loaded[spec.key] ?: return false
            if (personals[engine] != mode.personal) {
                if (mode.personal != null) return false
                engine.setPersonal(null)
                personals.remove(engine)
            }
            if (!layouts[engine].sameAs(spec.layout)) {
                engine.setLayout(spec.layout)
                layouts[engine] = spec.layout
            }
            active = engine
            generation = mode.generation
            nextWordEnabled = spec.nextWord
            return true
        }
    }

    fun recognize(input: FutoSwipeInput): FutoSwipeResult = synchronized(lock) {
        check(!closed) { "FutoSwipeRuntime has been closed" }
        val engine = requireCurrent(input.generation)
        validatePath(input.x, input.y, input.t)
        require(input.topK > 0) { "topK must be positive" }
        require(input.beamWidth > 0) { "beamWidth must be positive" }
        val start = input.t.first()
        val decoded = engine.recognize(input.copy(
            x = input.x.clone(),
            y = input.y.clone(),
            t = FloatArray(input.t.size) { input.t[it] - start },
        ))
        FutoSwipeResult(input.generation, decoded.first, decoded.second)
    }

    /** Genuine FUTO ContextLM output. This is separate from HeliBoard dictionary prediction. */
    fun predictNext(generation: Long, contextWords: List<String>, topK: Int = 10): FutoNextWordResult = synchronized(lock) {
        check(!closed) { "FutoSwipeRuntime has been closed" }
        val engine = requireCurrent(generation)
        require(topK > 0) { "topK must be positive" }
        if (!nextWordEnabled) return FutoNextWordResult(generation, emptyList(), 0)
        val start = System.nanoTime()
        val words = engine.predictNext(contextWords, topK)
        FutoNextWordResult(generation, words, (System.nanoTime() - start) / 1_000)
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        loaded.values.forEach(FutoSwipeEngine::close)
        loaded.clear()
        layouts.clear()
        personals.clear()
        active = null
    }

    private fun evictLocked() {
        val iterator = loaded.entries.iterator()
        while (loaded.size > maxLoaded && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.value === active) continue
            iterator.remove()
            layouts.remove(eldest.value)
            personals.remove(eldest.value)
            eldest.value.close()
        }
    }

    private fun requireCurrent(requestGeneration: Long): FutoSwipeEngine {
        val current = generation ?: error("FutoSwipeRuntime has not been configured")
        require(requestGeneration == current) {
            "stale layout generation $requestGeneration; current is $current"
        }
        return active ?: error("FutoSwipeRuntime has not been configured")
    }

    private data class LoadKey(val letters: String, val models: FutoSwipeModels, val vocabularies: List<FutoSwipeVocabulary>)

    private class Spec(val key: LoadKey, val layout: FutoSwipeLayout, val models: FutoSwipeModels, val nextWord: Boolean)

    private fun resolve(mode: FutoSwipeMode): Spec {
        val layout = mode.layout.validated()
        require(mode.vocabularies.isNotEmpty()) { "at least one raw .combined vocabulary is required" }
        mode.vocabularies.forEach {
            require(it.path.isNotBlank()) { "vocabulary path must not be blank" }
            require(it.weight.isFinite() && it.weight > 0f) { "vocabulary weight must be finite and positive" }
        }
        val english = Locale.forLanguageTag(mode.languageTag).language == "en"
        val models = when {
            !english -> mode.models.copy(decoderPath = null, contextLmPath = null, contextLmVocabPath = null)
            !layout.matchesEnglishDecoder() -> mode.models.copy(decoderPath = null)
            else -> mode.models
        }
        require(models.encoderPath.isNotBlank()) { "encoder path must not be blank" }
        require((models.contextLmPath == null) == (models.contextLmVocabPath == null)) {
            "context LM model and vocabulary paths must be supplied together"
        }
        return Spec(LoadKey(layout.letters, models, mode.vocabularies), layout, models,
            english && models.contextLmPath != null)
    }
}

/** Builds engines. [load] is slow and shares no state with loaded engines, so it may run on any thread. */
internal interface FutoSwipeBackend {
    fun load(layout: FutoSwipeLayout, models: FutoSwipeModels, vocabularies: List<FutoSwipeVocabulary>): FutoSwipeEngine
    /** Loads one more raw .combined vocabulary for the letters of a loaded engine. */
    fun loadTrie(path: String, letters: String): FutoSwipeTrie
}

internal interface FutoSwipeTrie : AutoCloseable

/** One loaded language. Not thread-safe; [FutoSwipeRuntime] serializes its use. */
internal interface FutoSwipeEngine : AutoCloseable {
    /** Cheap: the same tries and models with other key positions. */
    fun setLayout(layout: FutoSwipeLayout)
    /** Cheap: decodes with [trie] beside the language's own tries (none when null); closes the previous one. */
    fun setPersonal(trie: FutoSwipeTrie?)
    fun recognize(input: FutoSwipeInput): Pair<List<FutoSwipeWord>, FutoSwipeTiming>
    fun predictNext(contextWords: List<String>, topK: Int): List<FutoSwipeWord>
}

private class AarFutoSwipeBackend : FutoSwipeBackend {
    override fun load(
        layout: FutoSwipeLayout,
        models: FutoSwipeModels,
        vocabularies: List<FutoSwipeVocabulary>,
    ): FutoSwipeEngine {
        models.paths().forEach { require(File(it).isFile) { "missing FUTO model file: $it" } }
        vocabularies.forEach { require(File(it.path).isFile) { "missing raw .combined vocabulary: ${it.path}" } }

        val tries = LongArray(vocabularies.size)
        var decoder: SwipeDecoder? = null
        try {
            vocabularies.forEachIndexed { index, vocabulary ->
                tries[index] = FutoTrie.load(vocabulary.path, layout.letters)
                check(tries[index] != 0L) { "failed to load raw .combined vocabulary: ${vocabulary.path}" }
            }
            decoder = SwipeDecoder(
                encoderPath = models.encoderPath,
                decoderPath = models.decoderPath,
                lmModelPath = models.contextLmPath,
                lmVocabPath = models.contextLmVocabPath,
            )
            return AarFutoSwipeEngine(decoder, tries).also { it.setLayout(layout) }
        } catch (failure: Throwable) {
            decoder?.close()
            tries.forEach { if (it != 0L) FutoTrie.close(it) }
            throw failure
        }
    }

    override fun loadTrie(path: String, letters: String): FutoSwipeTrie {
        require(File(path).isFile) { "missing raw .combined vocabulary: $path" }
        val handle = FutoTrie.load(path, letters)
        check(handle != 0L) { "failed to load raw .combined vocabulary: $path" }
        return AarTrie(handle)
    }
}

private class AarTrie(val handle: Long) : FutoSwipeTrie {
    override fun close() = FutoTrie.close(handle)
}

private class AarFutoSwipeEngine(private val decoder: SwipeDecoder, private val own: LongArray) : FutoSwipeEngine {
    private var layout: FutoSwipeLayout? = null
    private var personal: AarTrie? = null
    private var tries = own

    override fun setLayout(layout: FutoSwipeLayout) {
        check(decoder.setMode(
            letters = layout.letters,
            cx = layout.centerX,
            cy = layout.centerY,
            tries = tries,
        )) { "FUTO rejected the layout or vocabulary mode" }
        this.layout = layout
    }

    override fun setPersonal(trie: FutoSwipeTrie?) {
        val old = personal
        personal = trie as AarTrie?
        tries = if (trie == null) own else own + trie.handle
        try {
            layout?.let(::setLayout)
        } catch (failure: Throwable) {
            personal = old
            tries = if (old == null) own else own + old.handle
            layout?.let(::setLayout)
            throw failure
        }
        old?.close()
    }

    override fun recognize(input: FutoSwipeInput): Pair<List<FutoSwipeWord>, FutoSwipeTiming> {
        decoder.setContext(input.contextWords)
        val words = decoder.recognize(
            input.x,
            input.y,
            input.t,
            input.topK,
            input.beamWidth,
            null,
        ).map { FutoSwipeWord(it.word, it.score, it.ctcScore, it.lmScore) }
        val timing = decoder.lastTiming()
        val strip = withWrittenForms(words) { word, maxDrop ->
            tries.flatMap { FutoTrie.forms(it, word, maxDrop).orEmpty().asList() }
        }
        return strip to FutoSwipeTiming(
            timing.resampleUs,
            timing.encoderUs,
            timing.decoderUs,
            timing.beamUs,
            timing.lmUs,
            timing.totalUs,
        )
    }

    override fun predictNext(contextWords: List<String>, topK: Int): List<FutoSwipeWord> {
        decoder.setContext(contextWords)
        return decoder.predictNext(topK).map { FutoSwipeWord(it.word, it.score, it.ctcScore, it.lmScore) }
    }

    override fun close() {
        decoder.close()
        own.forEach(FutoTrie::close)
        personal?.close()
    }
}

private object FutoTrie {
    init {
        System.loadLibrary("futo_trie")
    }

    external fun load(path: String, letters: String): Long
    /**
     * The written forms of word's swipe path, most frequent first, down to maxDrop frequency points below the most
     * frequent one; empty when the path has only its key letters.
     */
    external fun forms(handle: Long, word: String, maxDrop: Float): Array<String>?
    external fun close(handle: Long)
}

/**
 * Frequency points (the vocabulary's 0-255 scale) within which another written form follows its word in the strip.
 * The ё and contraction pairs sit 4-26 points below their main form (`ещё`, `всё`, `its`), possessives such as
 * `work's` 50-80 points below.
 */
internal const val NEAR_FORM_DROP = 30f

/**
 * Each decoded word followed by the other written forms of its swipe path that are nearly as frequent (`it's` then
 * `its`, `еще` then `ещё`), so that they show in the strip; rarer forms (`we're` for `were`) go after all decoded
 * words, where they stay reachable without pushing the decoder's own candidates out of the strip. The decoder
 * returns one word per path: the most frequent written form.
 */
internal fun withWrittenForms(
    words: List<FutoSwipeWord>,
    formsOf: (word: String, maxDrop: Float) -> List<String>,
): List<FutoSwipeWord> {
    val seen = HashSet<String>()
    val out = ArrayList<FutoSwipeWord>(words.size)
    val rare = ArrayList<FutoSwipeWord>()
    for (word in words) {
        if (seen.add(word.word)) out.add(word)
        val near = formsOf(word.word, NEAR_FORM_DROP)
        for (form in near) {
            if (seen.add(form)) out.add(word.copy(word = form))
        }
        for (form in formsOf(word.word, Float.POSITIVE_INFINITY)) {
            if (form !in near) rare.add(word.copy(word = form))
        }
    }
    for (form in rare) {
        if (seen.add(form.word)) out.add(form)
    }
    return out
}

private fun FutoSwipeModels.paths(): List<String> = listOfNotNull(
    encoderPath,
    decoderPath,
    contextLmPath,
    contextLmVocabPath,
)

private fun FutoSwipeLayout?.sameAs(other: FutoSwipeLayout): Boolean = this != null &&
    letters == other.letters && centerX.contentEquals(other.centerX) && centerY.contentEquals(other.centerY)

private fun FutoSwipeLayout.validated(): FutoSwipeLayout {
    val codePoints = letters.codePoints().toArray()
    require(codePoints.isNotEmpty()) { "layout must contain at least one key" }
    require(codePoints.size <= 64) { "FUTO encoder supports at most 64 keys" }
    val lower = codePoints.map(Character::toLowerCase)
    require(lower.toSet().size == lower.size) { "FUTO layouts cannot contain duplicate letters" }
    require(centerX.size == lower.size && centerY.size == lower.size) {
        "layout letters and key centres must have the same size"
    }
    require(centerX.all { it.isFinite() && it in 0f..1f }) { "key centre x values must be normalized" }
    require(centerY.all { it.isFinite() && it in 0f..1f }) { "key centre y values must be normalized" }
    val order = lower.indices.sortedBy(lower::get)
    val sorted = order.map(lower::get).toIntArray()
    return FutoSwipeLayout(
        String(sorted, 0, sorted.size),
        FloatArray(order.size) { centerX[order[it]] },
        FloatArray(order.size) { centerY[order[it]] },
    )
}

private fun FutoSwipeLayout.matchesEnglishDecoder(): Boolean {
    if (letters != "abcdefghijklmnopqrstuvwxyz") return false
    val expectedX = floatArrayOf(
        .0556f, .6111f, .3889f, .2778f, .2222f, .3889f, .5f, .6111f, .7778f,
        .7222f, .8333f, .9444f, .8333f, .7222f, .8889f, 1f, 0f, .3333f,
        .1667f, .4444f, .6667f, .5f, .1111f, .2778f, .5556f, .1667f,
    )
    val expectedY = floatArrayOf(
        .5f, 1f, 1f, .5f, 0f, .5f, .5f, .5f, 0f, .5f, .5f, .5f, 1f,
        1f, 0f, 0f, 0f, 0f, .5f, 0f, 0f, 1f, 0f, 1f, 0f, 1f,
    )
    val minX = centerX.min()
    val minY = centerY.min()
    val rangeX = centerX.max() - minX
    val rangeY = centerY.max() - minY
    if (rangeX == 0f || rangeY == 0f) return false
    return centerX.indices.all {
        kotlin.math.abs((centerX[it] - minX) / rangeX - expectedX[it]) < .1f &&
            kotlin.math.abs((centerY[it] - minY) / rangeY - expectedY[it]) < .1f
    }
}

private fun validatePath(x: FloatArray, y: FloatArray, t: FloatArray) {
    require(x.isNotEmpty()) { "swipe path must not be empty" }
    require(x.size == y.size && x.size == t.size) { "swipe x, y, and t arrays must have the same size" }
    require(x.all { it.isFinite() && it in 0f..1f }) { "swipe x values must be normalized" }
    require(y.all { it.isFinite() && it in 0f..1f }) { "swipe y values must be normalized" }
    require(t.all(Float::isFinite)) { "swipe timestamps must be finite" }
    require(t.first() >= 0f && t.asList().zipWithNext().all { (a, b) -> b >= a }) {
        "swipe timestamps must be non-negative and monotonic"
    }
}
