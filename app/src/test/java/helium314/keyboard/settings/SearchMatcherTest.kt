// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SearchMatcherTest {
    private val context = ApplicationProvider.getApplicationContext<App>()

    @Test fun corpusCoversRuntimeSettingsAndRejectsRemovedPhrases() {
        val container = SettingsContainer(context)
        assertTrue(container.searchKeys().size >= 155)
        assertEquals(emptyList(), container.searchCoverageIssues())
        assertEquals(listOf("voice_pause_seconds"), missingSearchAliases(
            container.searchKeys(), container.metadataKeys() - "voice_pause_seconds"))
        assertEquals(listOf("layout_SYMBOLS"), missingSearchAliases(
            container.searchKeys(), container.metadataKeys() - "layout_SYMBOLS"))

        val row = context.resources.openRawResource(R.raw.settings_search).bufferedReader().useLines {
            it.first { line -> line.startsWith("voice_pause_seconds|") }
        }
        val fields = row.split('|')
        assertFailsWith<IllegalArgumentException> {
            validatedSearchFields(fields[0] + "|" + fields[1].split(';').take(2).joinToString(";") +
                "|" + fields[2])
        }
    }

    @Test fun rankingHandlesMeaningOrderSpellingAndNegativeQueries() {
        val matcher = SearchMatcher(listOf(
            SearchDocument("cursor", "Horizontal spacebar swipe", aliases = listOf(
                "move caret by sliding spacebar", "двигать курсор пробелом")),
            SearchDocument("clip", "Clipboard retention", aliases = listOf(
                "keep copied text longer", "хранить копии дольше")),
            SearchDocument("voice", "Voice controls", aliases = listOf(
                "hide dictation actions", "скрыть кнопки речи")),
            SearchDocument("vibration", "Vibration", aliases = listOf(
                "make buttons buzz on taps", "вибрация при нажатии")),
            SearchDocument("private", "Incognito mode", aliases = listOf(
                "do not save typed words", "не сохранять слова")),
            SearchDocument("other", "Other", aliases = listOf("voice controls")),
            SearchDocument("accent", "Accent", aliases = listOf("café", "всё")),
        ))
        assertEquals("cursor", matcher.rank("spacebar, caret").first())
        assertEquals("cursor", matcher.rank("ПРОБЕЛОМ курсор").first())
        assertEquals("cursor", matcher.rank("spacebaf caret").first())
        assertEquals("voice", matcher.rank("Voice controls").first())
        assertEquals("clip", matcher.rank("please keep copies until I close another app").first())
        assertEquals("clip", matcher.rank("пожалуйста храните копии подольше").first())
        assertEquals("voice", matcher.rank("hide some dictation buttons please").first())
        assertEquals("vibration", matcher.rank("feel a buzz after touching").first())
        assertEquals("vibration", matcher.rank("no vibration").first())
        assertEquals("private", matcher.rank("do not save my words").first())
        assertEquals("private", matcher.rank("не сохранять мои слова").first())
        assertEquals("accent", matcher.rank("cafe").first())
        assertEquals("accent", matcher.rank("все").first())
        assertTrue(matcher.rank("quantum banana").isEmpty())
        assertTrue(matcher.rank("please rearrange quartz telescope").isEmpty())
        assertTrue(matcher.rank("no quartz").isEmpty())
        assertTrue(matcher.rank("xz").isEmpty())
    }

    @Test fun actualCorpusQueryProbe() {
        val container = SettingsContainer(context)
        val samples = listOf(
            "copy archive expires when" to "clipboard_history_retention_time",
            "microphone always visible" to "pinned_toolbar_keys",
            "move caret using space" to "horizontal_space_swipe",
            "не сохранять мои слова" to "always_incognito_mode",
            "пауза между словами" to "voice_split_seconds",
            "recognize speech and keep listening" to "voice_control_mode",
            "copied notes for several weeks" to "clipboard_history_retention_time",
            "words appear after processing pause" to "voice_split_seconds",
            "клавиши без вибрации" to "vibrate_on",
            "just one button during recording" to "voice_control_mode",
        )
        samples.forEach { (query, key) ->
            val top = container.smartFilter(query).take(3).map { it.key }
            assertTrue(key in top, "$query: $top")
        }
        assertTrue(container.smartFilter("quantum banana").isEmpty())
        assertEquals(container.filter("w").map { it.key }, container.smartFilter("w").map { it.key })
        val ordinaryWordResults = container.filter("word").map { it.key }
        assertTrue(ordinaryWordResults.isNotEmpty())
        assertEquals(ordinaryWordResults, container.smartFilter("word").map { it.key })
        assertTrue(container.filter("copy archive expires when").isEmpty())
        System.getenv("SETTINGS_SEARCH_QUERY")?.split("||")?.forEach { query ->
            println("SEARCH $query => ${container.smartFilter(query).take(10).map { it.key }}")
        }
    }
}
