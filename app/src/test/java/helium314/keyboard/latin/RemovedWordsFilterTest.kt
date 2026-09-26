// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.SuggestionResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** X11: words the user removed stay out of FUTO swipe results and FUTO next-word predictions. */
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
