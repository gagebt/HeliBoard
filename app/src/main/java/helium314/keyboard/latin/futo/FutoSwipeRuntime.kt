// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.futo

import org.futo.ml.inference.SwipeDecoder
import java.io.File
import java.util.Locale

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

data class FutoSwipeMode(
    val generation: Long,
    val languageTag: String,
    val layout: FutoSwipeLayout,
    val models: FutoSwipeModels,
    val vocabularies: List<FutoSwipeVocabulary>,
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

/** Serialized owner of the non-thread-safe FUTO swipe decoder. */
class FutoSwipeRuntime internal constructor(private val backend: FutoSwipeBackend) : AutoCloseable {
    constructor() : this(AarFutoSwipeBackend())

    private var generation: Long? = null
    private var nextWordEnabled = false
    private var closed = false

    @Synchronized
    fun configure(mode: FutoSwipeMode) {
        check(!closed) { "FutoSwipeRuntime has been closed" }
        val current = generation
        require(current == null || mode.generation > current) {
            "stale layout generation ${mode.generation}; current is $current"
        }

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

        backend.configure(layout, models, mode.vocabularies)
        generation = mode.generation
        nextWordEnabled = english && models.contextLmPath != null
    }

    @Synchronized
    fun recognize(input: FutoSwipeInput): FutoSwipeResult {
        check(!closed) { "FutoSwipeRuntime has been closed" }
        requireCurrent(input.generation)
        validatePath(input.x, input.y, input.t)
        require(input.topK > 0) { "topK must be positive" }
        require(input.beamWidth > 0) { "beamWidth must be positive" }
        val start = input.t.first()
        val decoded = backend.recognize(input.copy(
            x = input.x.clone(),
            y = input.y.clone(),
            t = FloatArray(input.t.size) { input.t[it] - start },
        ))
        return FutoSwipeResult(input.generation, decoded.first, decoded.second)
    }

    /** Genuine FUTO ContextLM output. This is separate from HeliBoard dictionary prediction. */
    @Synchronized
    fun predictNext(generation: Long, contextWords: List<String>, topK: Int = 10): FutoNextWordResult {
        check(!closed) { "FutoSwipeRuntime has been closed" }
        requireCurrent(generation)
        require(topK > 0) { "topK must be positive" }
        if (!nextWordEnabled) return FutoNextWordResult(generation, emptyList(), 0)
        val start = System.nanoTime()
        val words = backend.predictNext(contextWords, topK)
        return FutoNextWordResult(generation, words, (System.nanoTime() - start) / 1_000)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        backend.close()
        closed = true
    }

    private fun requireCurrent(requestGeneration: Long) {
        val current = generation ?: error("FutoSwipeRuntime has not been configured")
        require(requestGeneration == current) {
            "stale layout generation $requestGeneration; current is $current"
        }
    }
}

internal interface FutoSwipeBackend : AutoCloseable {
    fun configure(layout: FutoSwipeLayout, models: FutoSwipeModels, vocabularies: List<FutoSwipeVocabulary>)
    fun recognize(input: FutoSwipeInput): Pair<List<FutoSwipeWord>, FutoSwipeTiming>
    fun predictNext(contextWords: List<String>, topK: Int): List<FutoSwipeWord>
}

private class AarFutoSwipeBackend : FutoSwipeBackend {
    private var decoder: SwipeDecoder? = null
    private var tries = LongArray(0)

    override fun configure(
        layout: FutoSwipeLayout,
        models: FutoSwipeModels,
        vocabularies: List<FutoSwipeVocabulary>,
    ) {
        models.paths().forEach { require(File(it).isFile) { "missing FUTO model file: $it" } }
        vocabularies.forEach { require(File(it.path).isFile) { "missing raw .combined vocabulary: ${it.path}" } }

        val newTries = LongArray(vocabularies.size)
        var newDecoder: SwipeDecoder? = null
        try {
            vocabularies.forEachIndexed { index, vocabulary ->
                newTries[index] = FutoTrie.load(vocabulary.path, layout.letters)
                check(newTries[index] != 0L) { "failed to load raw .combined vocabulary: ${vocabulary.path}" }
            }
            newDecoder = SwipeDecoder(
                encoderPath = models.encoderPath,
                decoderPath = models.decoderPath,
                lmModelPath = models.contextLmPath,
                lmVocabPath = models.contextLmVocabPath,
            )
            check(newDecoder.setMode(
                letters = layout.letters,
                cx = layout.centerX,
                cy = layout.centerY,
                tries = newTries,
            )) { "FUTO rejected the layout or vocabulary mode" }
        } catch (failure: Throwable) {
            newDecoder?.close()
            newTries.forEach(FutoTrie::close)
            throw failure
        }

        val oldDecoder = decoder
        val oldTries = tries
        decoder = newDecoder
        tries = newTries
        oldDecoder?.close()
        oldTries.forEach(FutoTrie::close)
    }

    override fun recognize(input: FutoSwipeInput): Pair<List<FutoSwipeWord>, FutoSwipeTiming> {
        val active = decoder ?: error("FUTO decoder is not configured")
        active.setContext(input.contextWords)
        val words = active.recognize(
            input.x,
            input.y,
            input.t,
            input.topK,
            input.beamWidth,
            null,
        ).map { FutoSwipeWord(it.word, it.score, it.ctcScore, it.lmScore) }
        val timing = active.lastTiming()
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
        val active = decoder ?: error("FUTO decoder is not configured")
        active.setContext(contextWords)
        return active.predictNext(topK).map { FutoSwipeWord(it.word, it.score, it.ctcScore, it.lmScore) }
    }

    override fun close() {
        decoder?.close()
        decoder = null
        tries.forEach(FutoTrie::close)
        tries = LongArray(0)
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
