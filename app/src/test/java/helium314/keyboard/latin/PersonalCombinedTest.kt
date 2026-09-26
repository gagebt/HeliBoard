// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalCombinedTest {
    @Test
    fun writesOneLinePerWordTheFutoLoaderCanRead() {
        val text = personalCombined(mapOf(
            "Kacper" to 160,
            "Ждана" to 300,
            "don't" to -3,
            "a,b" to 160,
            "two\nlines" to 160,
            " padded" to 160,
            "" to 160,
        ))
        assertEquals("word=Kacper,f=160\nword=don't,f=0\nword=Ждана,f=255\n", text)
    }

    @Test
    fun noWordsIsAnEmptyList() {
        assertEquals("", personalCombined(emptyMap()))
    }
}
