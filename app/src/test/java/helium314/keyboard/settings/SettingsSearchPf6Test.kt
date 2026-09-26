// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SettingsSearchPf6Test {
    private val context = ApplicationProvider.getApplicationContext<App>()
    private val gesture = Settings.PREF_GESTURE_INPUT
    private val trail = Settings.PREF_GESTURE_PREVIEW_TRAIL
    private val fadeout = Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION

    @Test fun needsLineOnceAboveFirstDependentWhenParentIsOff() {
        val off = withPrerequisites(listOf(trail, fadeout, "other")) { false }
        assertEquals(listOf(SearchRow.Needs(gesture), SearchRow.Result(trail), SearchRow.Result(fadeout),
            SearchRow.Result("other")), off)
        val on = withPrerequisites(listOf(trail, fadeout)) { true }
        assertEquals(listOf(SearchRow.Result(trail), SearchRow.Result(fadeout)), on)
    }

    @Test fun parentSwitchIsNeverListedTwice() {
        // parent found after its dependent: the later result row is dropped
        assertEquals(listOf(SearchRow.Needs(gesture), SearchRow.Result(trail)),
            withPrerequisites(listOf(trail, gesture)) { false })
        // parent found before its dependent: no extra Needs row
        assertEquals(listOf(SearchRow.Result(gesture), SearchRow.Result(trail)),
            withPrerequisites(listOf(gesture, trail)) { false })
    }

    @Test fun outermostOffParentIsNamed() {
        val dynamic = Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC
        assertEquals(SearchRow.Needs(gesture), withPrerequisites(listOf(dynamic)) { false }.first())
        assertEquals(SearchRow.Needs(Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT),
            withPrerequisites(listOf(dynamic)) { it == gesture }.first())
    }

    @Test fun everyParentHasADefaultAndIsASetting() {
        val container = SettingsContainer(context)
        searchParentDefaults.keys.forEach { assertTrue(container[it] != null, "no setting for parent $it") }
        val rows = withPrerequisites(container.searchKeys().toList()) { false }
        rows.filterIsInstance<SearchRow.Needs>().forEach {
            assertTrue(it.parentKey in searchParentDefaults, "no default for ${it.parentKey}")
        }
    }

    @Test fun realTrailSearchWithGestureTypingOff() {
        val container = SettingsContainer(context)
        val keys = container.smartFilter("trail").map { it.key }
        assertTrue(trail in keys, "trail search: $keys")
        val rows = withPrerequisites(keys) { it != gesture }
        assertEquals(1, rows.count { it == SearchRow.Needs(gesture) }, "rows: $rows")
        assertEquals(0, rows.count { it == SearchRow.Result(gesture) }, "rows: $rows")
        assertEquals(SearchRow.Needs(gesture), rows.first { it is SearchRow.Needs || it == SearchRow.Result(trail) })
    }

    @Test fun newTextsAreFoundBySearch() {
        val container = SettingsContainer(context)
        fun top(query: String) = container.smartFilter(query).take(5).map { it.key }
        assertTrue("voice_help" in top("voice"), "voice: ${top("voice")}")
        assertTrue("voice_save_dictations_to_history" in top("retention"), "retention: ${top("retention")}")
        assertTrue("voice_save_dictations_to_history" in top("how long saved dictations last"),
            "how long: ${top("how long saved dictations last")}")
        // bare "how long" also matches the long-press settings by title, so both retention texts must be on the first screen
        val howLong = container.smartFilter("how long").take(10).map { it.key }
        assertTrue("clipboard_history_retention_time" in howLong && "voice_save_dictations_to_history" in howLong,
            "how long: $howLong")
        assertTrue("clipboard_history_retention_time" in top("how long are copies kept"),
            "copies kept: ${top("how long are copies kept")}")
        assertTrue("nav_preferences" in top("where are voice settings"), "where: ${top("where are voice settings")}")
        assertTrue(container.smartFilter("qwxzv blorptang").isEmpty())
    }
}
