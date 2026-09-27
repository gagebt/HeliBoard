// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.SuggestionResults
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.Mockito.mock
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** X11: words the user removed stay out of FUTO swipe results and FUTO next-word predictions. */
@RunWith(RobolectricTestRunner::class)
class RemovedWordsFilterTest {
    private val removed = setOf("hell")

    private fun word(value: String, score: Int, dictionary: Dictionary, kind: Int) = SuggestedWordInfo(
        value, "", score, kind, dictionary, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE,
    )

    private fun futo(kind: Int, vararg words: String) = SuggestionResults(words.size, false, false).also { results ->
        words.forEachIndexed { rank, it ->
            results.add(word(it, 1_000_000_000 - rank, Dictionary.DICTIONARY_APPLICATION_DEFINED, kind))
        }
    }

    @Test
    fun personalOnlyWordCanBeRemoved() {
        val dictionaries = DictionaryFacilitatorImpl()
        dictionaries.removeWord("Radek")
        assertTrue(dictionaries.isBlacklisted("Radek"))
        assertTrue(dictionaries.isBlacklisted("radek"))
    }

    @Test
    fun removedWordLeavesAnAlreadyCachedPrediction() {
        val dictionaries = DictionaryFacilitatorImpl()
        val suggest = Suggest(dictionaries)
        val context = NgramContext.BEGINNING_OF_SENTENCE
        val results = futo(SuggestedWordInfo.KIND_PREDICTION, "Radek", "hello")
        val cacheField = Suggest::class.java.getDeclaredField("nextWordSuggestionsCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(suggest) as MutableMap<NgramContext, SuggestionResults>
        cache[context] = results
        val method = Suggest::class.java.getDeclaredMethod("getNextWordSuggestions", NgramContext::class.java,
            Keyboard::class.java, Int::class.javaPrimitiveType, SettingsValuesForSuggestion::class.java).apply { isAccessible = true }
        val keyboard = mock(Keyboard::class.java)
        val settings = SettingsValuesForSuggestion(false, false)
        fun read() = (method.invoke(suggest, context, keyboard, 0, settings) as SuggestionResults).map { it.mWord }
        assertEquals(listOf("Radek", "hello"), read())
        dictionaries.removeWord("Radek")
        assertEquals(listOf("hello"), read())
        assertSame(results, cache[context])
    }

    @Test
    fun removedWordLeavesFutoSwipeResults() {
        val swipe = futo(SuggestedWordInfo.KIND_CORRECTION, "hell", "hello", "help")

        val shown = withoutRemovedWords(swipe, removed::contains)!!.map { it.mWord }

        assertEquals(listOf("hello", "help"), shown)
    }

    @Test
    fun removedWordLeavesFutoNextWordPredictions() {
        val native = SuggestionResults(2, false, false).also {
            it.add(word("mine", 90, Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.KIND_PREDICTION))
        }
        val predictions = futo(SuggestedWordInfo.KIND_PREDICTION, "hell", "and")

        val merged = mergeNextWordPredictions(withoutRemovedWords(predictions, removed::contains), native)

        assertEquals(listOf("mine", "and"), merged.map { it.mWord })
    }

    @Test
    fun listsWithoutRemovedWordsStayTheSameObject() {
        val swipe = futo(SuggestedWordInfo.KIND_CORRECTION, "hello", "help")

        assertSame(swipe, withoutRemovedWords(swipe, removed::contains))
        assertNull(withoutRemovedWords(null, removed::contains))
    }
}
