// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.SuggestionResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NextWordPredictionMergerTest {
    private fun word(value: String, score: Int, dictionary: Dictionary) = SuggestedWordInfo(
        value, "", score, SuggestedWordInfo.KIND_PREDICTION, dictionary,
        SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE,
    )

    private fun results(vararg words: SuggestedWordInfo) =
        SuggestionResults(words.size, false, false).also { it.addAll(words.toList()) }

    @Test
    fun personalAndFutoPredictionsAreInterleaved() {
        val native = results(
            word("mine", 90, Dictionary.DICTIONARY_USER_TYPED),
            word("history", 80, Dictionary.DICTIONARY_USER_TYPED),
            word("native", 100, Dictionary.DICTIONARY_HARDCODED),
        )
        val futo = results(
            word("and", 1_000_000_000, Dictionary.DICTIONARY_APPLICATION_DEFINED),
            word("the", 999_999_999, Dictionary.DICTIONARY_APPLICATION_DEFINED),
        )

        val merged = mergeNextWordPredictions(futo, native).toList()

        assertEquals(listOf("mine", "and", "history", "the"), merged.map { it.mWord })
        assertEquals(Dictionary.DICTIONARY_USER_TYPED, merged[0].mSourceDict)
        assertEquals(Dictionary.DICTIONARY_APPLICATION_DEFINED, merged[1].mSourceDict)
    }

    @Test
    fun duplicateKeepsPersonalCandidateAndFutoStillAppears() {
        val native = results(word("and", 90, Dictionary.DICTIONARY_USER_TYPED))
        val futo = results(
            word("and", 1_000_000_000, Dictionary.DICTIONARY_APPLICATION_DEFINED),
            word("the", 999_999_999, Dictionary.DICTIONARY_APPLICATION_DEFINED),
        )

        val merged = mergeNextWordPredictions(futo, native).toList()

        assertEquals(listOf("and", "the"), merged.map { it.mWord })
        assertEquals(Dictionary.DICTIONARY_USER_TYPED, merged[0].mSourceDict)
    }

    @Test
    fun missingFutoReturnsNativeResultsUnchanged() {
        val native = results(word("mine", 90, Dictionary.DICTIONARY_USER_TYPED))

        assertSame(native, mergeNextWordPredictions(null, native))
        assertSame(native, mergeNextWordPredictions(results(), native))
    }

    @Test
    fun noPersonalResultsReturnsFutoUnchanged() {
        val native = results(word("native", 90, Dictionary.DICTIONARY_HARDCODED))
        val futo = results(word("and", 1_000_000_000, Dictionary.DICTIONARY_APPLICATION_DEFINED))

        assertSame(futo, mergeNextWordPredictions(futo, native))
    }
}
