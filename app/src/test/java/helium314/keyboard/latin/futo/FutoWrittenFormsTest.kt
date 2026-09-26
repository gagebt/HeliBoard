// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.futo

import kotlin.test.Test
import kotlin.test.assertEquals

class FutoWrittenFormsTest {
    // Forms as the native trie returns them for the real vocabularies, with each form's frequency below the most
    // frequent one (main_en_US.combined and main_ru.combined; host probe in checks/T).
    private val forms = mapOf(
        "it's" to listOf("it's" to 0f, "its" to 4f),
        "еще" to listOf("еще" to 0f, "ещё" to 26f),
        "все" to listOf("все" to 0f, "всё" to 15f),
        "were" to listOf("were" to 0f, "we're" to 80f),
        "work" to listOf("work" to 0f, "work's" to 80f),
        "don't" to listOf("don't" to 0f),
    )

    private fun decoded(vararg words: String) = words.mapIndexed { i, w -> FutoSwipeWord(w, 10f - i, 0f, 0f) }

    private fun strip(vararg words: String) =
        withWrittenForms(decoded(*words)) { word, maxDrop ->
            forms[word].orEmpty().filter { it.second <= maxDrop }.map { it.first }
        }.map { it.word }

    @Test
    fun nearFormsFollowTheirWord() {
        assertEquals(listOf("it's", "its", "is"), strip("it's", "is"))
        assertEquals(listOf("еще", "ещё", "все", "всё"), strip("еще", "все"))
    }

    @Test
    fun rareFormsComeAfterAllDecodedWords() {
        assertEquals(listOf("were", "work", "worn", "we're", "work's"), strip("were", "work", "worn"))
    }

    @Test
    fun wordsWithoutOtherFormsAreUnchanged() {
        assertEquals(listOf("don't", "done", "hello"), strip("don't", "done", "hello"))
        assertEquals(listOf("hello", "help"), strip("hello", "help"))
    }

    @Test
    fun aFormAlsoDecodedIsShownOnce() {
        assertEquals(listOf("it's", "its", "is"), strip("it's", "its", "is"))
        assertEquals(listOf("were", "we're", "work", "work's"), strip("were", "we're", "work"))
    }
}
