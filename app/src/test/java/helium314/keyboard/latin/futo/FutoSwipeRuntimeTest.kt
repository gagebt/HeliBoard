// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.futo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FutoSwipeRuntimeTest {
    private class FakeEngine(val models: FutoSwipeModels, val vocabularies: List<FutoSwipeVocabulary>) : FutoSwipeEngine {
        lateinit var currentLayout: FutoSwipeLayout
        lateinit var input: FutoSwipeInput
        var setLayoutCount = 0
        var predictCount = 0
        var closed = false

        override fun setLayout(layout: FutoSwipeLayout) {
            currentLayout = layout
            setLayoutCount++
        }

        override fun recognize(input: FutoSwipeInput): Pair<List<FutoSwipeWord>, FutoSwipeTiming> {
            check(!closed)
            this.input = input
            return listOf(
                FutoSwipeWord("hello", 9f, 8f, 1f),
                FutoSwipeWord("help", 7f, 7f, 0f),
            ) to FutoSwipeTiming(1f, 2f, 3f, 4f, 5f, 15f)
        }

        override fun predictNext(contextWords: List<String>, topK: Int): List<FutoSwipeWord> {
            check(!closed)
            predictCount++
            return listOf(FutoSwipeWord("world", 4f, 0f, 4f))
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeBackend : FutoSwipeBackend {
        val engines = mutableListOf<FakeEngine>()
        @Volatile var gate: CountDownLatch? = null
        @Volatile var loadEntered = CountDownLatch(1)

        override fun load(
            layout: FutoSwipeLayout,
            models: FutoSwipeModels,
            vocabularies: List<FutoSwipeVocabulary>,
        ): FutoSwipeEngine {
            loadEntered.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            return FakeEngine(models, vocabularies).also {
                it.setLayout(layout)
                synchronized(engines) { engines.add(it) }
            }
        }

        val last get() = synchronized(engines) { engines.last() }
    }

    @Test
    fun rejectsInvalidLayoutsAndStaleGenerations() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        assertFailsWith<IllegalArgumentException> { runtime.preload(mode(1, layout("aba"))) }
        val tooMany = IntArray(65) { 0x1000 + it }
        assertFailsWith<IllegalArgumentException> {
            runtime.preload(mode(1, layout(String(tooMany, 0, tooMany.size))))
        }

        runtime.preload(mode(0))
        assertTrue(runtime.configure(mode(2)))
        assertFailsWith<IllegalArgumentException> { runtime.configure(mode(2)) }
        assertFailsWith<IllegalArgumentException> { runtime.recognize(input(1)) }
        assertEquals(1, backend.engines.size)
    }

    @Test
    fun configureNeverLoads() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)

        assertFalse(runtime.configure(mode(1)))
        assertFalse(runtime.isLoaded(mode(1)))
        assertEquals(0, backend.engines.size)
        assertNotNull(runtime.preload(mode(0)))
        assertNull(runtime.preload(mode(0)), "a loaded language must not load again")
        assertTrue(runtime.isLoaded(mode(1)))
        assertTrue(runtime.configure(mode(1)))
        assertEquals(1, runtime.loadCount)
    }

    @Test
    fun shapeChangeResetsLayoutWithoutLoading() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.preload(mode(0))
        runtime.configure(mode(1))
        val engine = backend.last
        val taller = FutoSwipeLayout("abc", floatArrayOf(.1f, .5f, .9f), floatArrayOf(.2f, .2f, .2f))

        assertTrue(runtime.configure(mode(2, taller)))
        runtime.recognize(input(2))

        assertEquals(1, backend.engines.size)
        assertEquals(1, runtime.loadCount)
        assertTrue(engine.currentLayout.centerX.contentEquals(floatArrayOf(.1f, .5f, .9f)))
        assertEquals(2, engine.setLayoutCount)
    }

    @Test
    fun twoLanguagesStayLoadedAndAThirdEvictsTheOlderInactiveOne() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.preload(mode(0))
        runtime.preload(mode(0, languageTag = "ru", vocabulary = "ru"))
        val english = backend.engines[0]
        val russian = backend.engines[1]

        assertTrue(runtime.configure(mode(1)))
        assertTrue(runtime.configure(mode(2, languageTag = "ru", vocabulary = "ru")))
        assertTrue(runtime.configure(mode(3)))
        assertEquals(2, runtime.loadCount)

        runtime.preload(mode(0, layout("xyz"), vocabulary = "other"))
        assertEquals(3, runtime.loadCount)
        assertTrue(russian.closed, "the least recently used inactive language is closed")
        assertFalse(english.closed, "the active language is never evicted")
        runtime.recognize(input(3))
    }

    @Test
    fun loadDoesNotHoldTheRuntimeLock() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.preload(mode(0))
        runtime.configure(mode(1))
        val gate = CountDownLatch(1)
        backend.loadEntered = CountDownLatch(1)
        backend.gate = gate
        val pool = Executors.newFixedThreadPool(2)
        try {
            val loading = pool.submit<FutoSwipeLoad?> { runtime.preload(mode(0, languageTag = "ru", vocabulary = "ru")) }
            assertTrue(backend.loadEntered.await(2, TimeUnit.SECONDS), "the second load started")
            val words = pool.submit<List<FutoSwipeWord>> { runtime.recognize(input(1)).words }
                .get(2, TimeUnit.SECONDS)
            assertEquals("hello", words.first().word)
            assertTrue(runtime.predictNext(1, listOf("say")).words.isNotEmpty())
            gate.countDown()
            assertNotNull(loading.get(2, TimeUnit.SECONDS))
            assertEquals(2, runtime.loadCount)
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentPreloadsOfOneLanguageLoadOnce() {
        val backend = FakeBackend()
        val gate = CountDownLatch(1)
        backend.gate = gate
        val runtime = FutoSwipeRuntime(backend)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<FutoSwipeLoad?> { runtime.preload(mode(0), warmUp = true) }
            assertTrue(backend.loadEntered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<FutoSwipeLoad?> { runtime.preload(mode(0)) }
            Thread.sleep(100)
            assertFalse(second.isDone, "the second caller waits for the running load")
            gate.countDown()
            val results = listOf(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS))
            assertEquals(1, results.count { it != null })
            assertEquals(1, backend.engines.size)
            assertEquals(1, runtime.loadCount)
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun warmUpRunsOneEnglishPredictionBeforeUse() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)

        val english = runtime.preload(mode(0), warmUp = true)
        runtime.preload(mode(0, languageTag = "ru", vocabulary = "ru"), warmUp = true)

        assertNotNull(english)
        assertTrue(english.warmMs >= 0)
        assertEquals(1, backend.engines[0].predictCount)
        assertEquals(0, backend.engines[1].predictCount, "Russian has no next-word model to warm")
    }

    @Test
    fun closeDuringLoadClosesTheNewEngine() {
        val backend = FakeBackend()
        val gate = CountDownLatch(1)
        backend.gate = gate
        val runtime = FutoSwipeRuntime(backend)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val loading = pool.submit<FutoSwipeLoad?> { runtime.preload(mode(0)) }
            assertTrue(backend.loadEntered.await(2, TimeUnit.SECONDS))
            runtime.close()
            gate.countDown()
            assertFailsWith<java.util.concurrent.ExecutionException> { loading.get(2, TimeUnit.SECONDS) }
            assertTrue(backend.last.closed)
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun nonEnglishModeUnloadsEnglishOnlyModels() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.preload(mode(0, languageTag = "ru"))
        runtime.configure(mode(1, languageTag = "ru"))

        val models = backend.last.models
        assertEquals(null, models.decoderPath)
        assertEquals(null, models.contextLmPath)
        assertEquals(null, models.contextLmVocabPath)
        assertTrue(runtime.predictNext(1, listOf("привет")).words.isEmpty())
        assertEquals(0, backend.last.predictCount)
    }

    @Test
    fun returnsRankedFutoWordsAndTiming() {
        val runtime = FutoSwipeRuntime(FakeBackend())
        runtime.preload(mode(0))
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
        val reversed = FutoSwipeLayout("cba", floatArrayOf(.3f, .2f, .1f), floatArrayOf(.6f, .5f, .4f))
        runtime.preload(mode(0, reversed))
        runtime.configure(mode(5, reversed))

        runtime.recognize(input(5).copy(t = floatArrayOf(100f, 120f)))

        val engine = backend.last
        assertEquals("abc", engine.currentLayout.letters)
        assertTrue(engine.currentLayout.centerX.contentEquals(floatArrayOf(.1f, .2f, .3f)))
        assertTrue(engine.input.t.contentEquals(floatArrayOf(0f, 20f)))
    }

    @Test
    fun exposesContextLmPredictionSeparately() {
        val backend = FakeBackend()
        val runtime = FutoSwipeRuntime(backend)
        runtime.preload(mode(0))
        runtime.configure(mode(3))

        val result = runtime.predictNext(3, listOf("hello"), topK = 5)

        assertEquals(listOf("world"), result.words.map { it.word })
        assertEquals(1, backend.last.predictCount)
        assertTrue(result.elapsedUs >= 0)
    }

    private fun mode(
        generation: Long,
        layout: FutoSwipeLayout = layout("abc"),
        languageTag: String = "en-US",
        vocabulary: String = "dictionary",
    ) = FutoSwipeMode(
        generation = generation,
        languageTag = languageTag,
        layout = layout,
        models = FutoSwipeModels("encoder", "decoder", "lm", "lm-vocab"),
        vocabularies = listOf(FutoSwipeVocabulary(vocabulary)),
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
