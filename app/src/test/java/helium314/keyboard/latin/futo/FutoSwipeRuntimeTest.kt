// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.futo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FutoSwipeRuntimeTest {
    private class FakeBackend : FutoSwipeBackend {
        lateinit var models: FutoSwipeModels
        lateinit var layout: FutoSwipeLayout
        lateinit var input: FutoSwipeInput
        var configureCount = 0
        var predictCount = 0

        override fun configure(
            layout: FutoSwipeLayout,
            models: FutoSwipeModels,
            vocabularies: List<FutoSwipeVocabulary>,
        ) {
            this.layout = layout
            this.models = models
            configureCount++
        }

        override fun recognize(input: FutoSwipeInput): Pair<List<FutoSwipeWord>, FutoSwipeTiming> {
            this.input = input
            return listOf(
                FutoSwipeWord("hello", 9f, 8f, 1f),
                FutoSwipeWord("help", 7f, 7f, 0f),
            ) to FutoSwipeTiming(1f, 2f, 3f, 4f, 5f, 15f)
        }

        override fun predictNext(contextWords: List<String>, topK: Int): List<FutoSwipeWord> {
            predictCount++
            return listOf(FutoSwipeWord("world", 4f, 0f, 4f))
        }

        override fun close() = Unit
    }

    @Test
    fun rejectsInvalidLayoutsAndStaleGenerations() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        assertFailsWith<IllegalArgumentException> { runtime.configure(mode(1, layout("aba"))) }
        val tooMany = IntArray(65) { 0x1000 + it }
        assertFailsWith<IllegalArgumentException> {
            runtime.configure(mode(1, layout(String(tooMany, 0, tooMany.size))))
        }

        runtime.configure(mode(2))
        assertFailsWith<IllegalArgumentException> { runtime.configure(mode(2)) }
        assertFailsWith<IllegalArgumentException> { runtime.recognize(input(1)) }
        assertEquals(1, backend.configureCount)
    }

    @Test
    fun nonEnglishModeUnloadsEnglishOnlyModels() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.configure(mode(1, languageTag = "ru"))

        assertEquals(null, backend.models.decoderPath)
        assertEquals(null, backend.models.contextLmPath)
        assertEquals(null, backend.models.contextLmVocabPath)
        assertTrue(runtime.predictNext(1, listOf("привет")).words.isEmpty())
        assertEquals(0, backend.predictCount)
    }

    @Test
    fun returnsRankedFutoWordsAndTiming() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.configure(mode(4))

        val result = runtime.recognize(input(4))

        assertEquals(listOf("hello", "help"), result.words.map { it.word })
        assertEquals(15f, result.timing.totalUs)
        assertEquals(4, result.generation)
    }

    @Test
    fun normalizesRenderedKeyOrderAndTouchTime() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.configure(mode(5, FutoSwipeLayout(
            "cba",
            floatArrayOf(.3f, .2f, .1f),
            floatArrayOf(.6f, .5f, .4f),
        )))

        runtime.recognize(input(5).copy(t = floatArrayOf(100f, 120f)))

        assertEquals("abc", backend.layout.letters)
        assertTrue(backend.layout.centerX.contentEquals(floatArrayOf(.1f, .2f, .3f)))
        assertTrue(backend.input.t.contentEquals(floatArrayOf(0f, 20f)))
    }

    @Test
    fun exposesContextLmPredictionSeparately() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.configure(mode(3))

        val result = runtime.predictNext(3, listOf("hello"), topK = 5)

        assertEquals(listOf("world"), result.words.map { it.word })
        assertEquals(1, backend.predictCount)
        assertTrue(result.elapsedUs >= 0)
    }

    private fun mode(
        generation: Long,
        layout: FutoSwipeLayout = layout("abc"),
        languageTag: String = "en-US",
    ) = FutoSwipeMode(
        generation = generation,
        languageTag = languageTag,
        layout = layout,
        models = FutoSwipeModels("encoder", "decoder", "lm", "lm-vocab"),
        vocabularies = listOf(FutoSwipeVocabulary("dictionary")),
    )

    private fun layout(letters: String): FutoSwipeLayout {
        val size = letters.codePointCount(0, letters.length)
        return FutoSwipeLayout(letters, FloatArray(size) { 0.5f }, FloatArray(size) { 0.5f })
    }

    private fun input(generation: Long) = FutoSwipeInput(
        generation,
        floatArrayOf(0.1f, 0.9f),
        floatArrayOf(0.2f, 0.8f),
        floatArrayOf(0f, 20f),
        listOf("say"),
    )
}
