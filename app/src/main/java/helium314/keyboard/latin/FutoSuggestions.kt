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
import kotlin.math.min

/** Thin HeliBoard adapter around the serialized FUTO runtime. */
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

    private var context: Context? = null
    private var runtime: FutoSwipeRuntime? = null
    private var signature: String? = null
    private var generation = 0L

    @Synchronized
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    @Synchronized
    fun close() {
        runtime?.close()
        runtime = null
        signature = null
    }

    @Synchronized
    fun recognize(
        pointers: InputPointers,
        contextWords: List<String>,
        keyboard: Keyboard,
        locale: Locale,
    ): SuggestionResults? = try {
        val active = configure(keyboard, locale) ?: return null
        val count = pointers.pointerSize
        if (count == 0) return null
        val width = keyboard.mOccupiedWidth.toFloat()
        val height = keyboard.mOccupiedHeight.toFloat()
        val x = pointers.xCoordinates.copyOf(count).map { it / width }.toFloatArray()
        val y = pointers.yCoordinates.copyOf(count)
            .map { min(1f, it / height * (4f / 3f)) }.toFloatArray()
        val t = pointers.times.copyOf(count).map(Int::toFloat).toFloatArray()
        val result = active.recognize(FutoSwipeInput(generation, x, y, t, contextWords, topK = 8))
        result.words.toSuggestionResults(false, SuggestedWordInfo.KIND_CORRECTION)
    } catch (failure: Throwable) {
        Log.e(TAG, "FUTO swipe failed; dropping this gesture", failure)
        SuggestionResults(1, false, false)
    }

    @Synchronized
    fun predictNext(keyboard: Keyboard, locale: Locale, contextWords: List<String>): SuggestionResults? {
        if (locale.language != Locale.ENGLISH.language || contextWords.isEmpty()) return null
        return try {
            val active = configure(keyboard, locale) ?: return null
            val result = active.predictNext(generation, contextWords, topK = 8)
            if (result.words.isEmpty()) null
            else result.words.toSuggestionResults(false, SuggestedWordInfo.KIND_PREDICTION)
        } catch (failure: Throwable) {
            Log.e(TAG, "FUTO next-word prediction failed; retaining HeliBoard fallback", failure)
            null
        }
    }

    private fun configure(keyboard: Keyboard, locale: Locale): FutoSwipeRuntime? {
        val appContext = context ?: return null
        val vocabAsset = when (locale.language) {
            Locale.ENGLISH.language -> EN_VOCAB
            "ru" -> RU_VOCAB
            else -> return null
        }
        val keys = keyboard.sortedKeys
            .filter { Character.isLetter(it.code) }
        if (keys.isEmpty()) return null
        val letters = String(keys.map { Character.toLowerCase(it.code) }.toIntArray(), 0, keys.size)
        val width = keyboard.mOccupiedWidth.toFloat()
        val height = keyboard.mOccupiedHeight.toFloat()
        val centerX = keys.map { (it.x + it.width / 2f) / width }.toFloatArray()
        val centerY = keys.map { min(1f, (it.y + it.height / 2f) / height * (4f / 3f)) }.toFloatArray()
        val newSignature = "$vocabAsset|${keyboard.mOccupiedWidth}x${keyboard.mOccupiedHeight}|$letters|${centerX.contentHashCode()}|${centerY.contentHashCode()}"
        val active = runtime ?: FutoSwipeRuntime().also { runtime = it }
        if (signature == newSignature) return active

        generation++
        val english = locale.language == Locale.ENGLISH.language
        materialize(appContext, ENCODER_METADATA)
        if (english) {
            materialize(appContext, DECODER_METADATA)
            materialize(appContext, CONTEXT_LM_METADATA)
        }
        active.configure(FutoSwipeMode(
            generation = generation,
            languageTag = locale.toLanguageTag(),
            layout = FutoSwipeLayout(letters, centerX, centerY),
            models = FutoSwipeModels(
                encoderPath = materialize(appContext, ENCODER).path,
                decoderPath = if (english) materialize(appContext, DECODER).path else null,
                contextLmPath = if (english) materialize(appContext, CONTEXT_LM).path else null,
                contextLmVocabPath = if (english) materialize(appContext, CONTEXT_VOCAB).path else null,
            ),
            vocabularies = listOf(FutoSwipeVocabulary(materialize(appContext, vocabAsset).path)),
        ))
        signature = newSignature
        return active
    }

    private fun materialize(context: Context, asset: String): File {
        val target = File(context.filesDir, "futo/$asset")
        val expected = context.assets.open(asset).use { it.available().toLong() }
        if (target.isFile && target.length() == expected) return target
        target.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            target.outputStream().use(input::copyTo)
        }
        check(target.length() == expected) { "Incomplete asset copy for $asset" }
        return target
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
