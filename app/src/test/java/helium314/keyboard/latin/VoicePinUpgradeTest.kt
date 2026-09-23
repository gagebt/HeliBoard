// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
class VoicePinUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<App>()

    @Test fun untouchedOldDefaultPinsVoiceOnUpgrade() {
        val oldDefault = defaultPinnedToolbarPref.replace(
            "VOICE${Separators.KV}true", "VOICE${Separators.KV}false")
        assertNotEquals(defaultPinnedToolbarPref, oldDefault)
        val prefs = context.prefs()
        prefs.edit {
            putInt(Settings.PREF_VERSION_CODE, 4103)
            putString(Settings.PREF_PINNED_TOOLBAR_KEYS, oldDefault)
        }

        checkVersionUpgrade(context)

        assertEquals(defaultPinnedToolbarPref,
            prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null))
    }

    @Test fun customizedOldListKeepsItsPinChoices() {
        val custom = defaultPinnedToolbarPref
            .replace("VOICE${Separators.KV}true", "VOICE${Separators.KV}false")
            .replace("SETTINGS${Separators.KV}false", "SETTINGS${Separators.KV}true")
        val prefs = context.prefs()
        prefs.edit {
            putInt(Settings.PREF_VERSION_CODE, 4103)
            putString(Settings.PREF_PINNED_TOOLBAR_KEYS, custom)
        }

        checkVersionUpgrade(context)

        assertEquals(custom, prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null))
    }
}
