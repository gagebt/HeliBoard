// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.futo

import kotlin.test.Test
import kotlin.test.assertEquals

class FutoWrittenFormsTest {
    // Forms as the native trie returns them for the real vocabularies (host probe, checks/T/host).
    private val forms = mapOf(
        "it's" to listOf("it's", "its"),
        "еще" to listOf("еще", "ещё"),
        "все" to listOf("все", "всё"),
        "don't" to listOf("don't"),
    )

    private fun decoded(vararg words: String) = words.mapIndexed { i, w -> FutoSwipeWord(w, 10f - i, 0f, 0f) }

    private fun strip(vararg words: String) =
        withWrittenForms(decoded(*words)) { forms[it].orEmpty() }.map { it.word }

    @Test
    fun otherFormsFollowTheirWord() {
        assertEquals(listOf("it's", "its", "is"), strip("it's", "is"))
        assertEquals(listOf("еще", "ещё", "все", "всё"), strip("еще", "все"))
    }

    @Test
    fun wordsWithoutOtherFormsAreUnchanged() {
        assertEquals(listOf("don't", "done", "hello"), strip("don't", "done", "hello"))
        assertEquals(listOf("hello", "help"), strip("hello", "help"))
    }

    @Test
    fun aFormAlsoDecodedIsShownOnce() {
        assertEquals(listOf("it's", "its", "is"), strip("it's", "its", "is"))
    }
}
