// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import androidx.core.content.edit
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.KeyLongPress
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.POPUP_KEYS_LAYOUT
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class KeyLongPressTest {
    private val ime = Robolectric.setupService(LatinIME::class.java)
    private val prefs = ime.prefs()
    private val params = KeyboardParams().apply {
        mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        mPopupKeyOrder.add(POPUP_KEYS_LAYOUT)
        mPopupKeyHintOrder.add(POPUP_KEYS_LAYOUT)
        LocaleKeyboardInfos.addLocaleKeyTextsToParams(ime, this, LocaleKeyboardInfos.POPUP_KEYS_NORMAL)
    }
    @Before @After fun clearHoldChoices() {
        prefs.edit {
            KeyLongPress.entries.forEach { remove(it.prefKey) }
            remove("prefs_long_press_keyboard_to_change_lang")
        }
    }

    private fun key(label: String, code: Int = 0, group: Int = 0): Key =
        LayoutParser.parseJsonString("""[[{"label":"$label","code":$code,"groupId":$group}]]""")
            .flatten().single().compute(params)!!.toKeyParams(params).apply {
                mAbsoluteWidth = 100f
                mAbsoluteHeight = 80f
            }.createKey()

    @Test fun spaceSelectionAndNoneUseRealBuiltKeyWithoutChangingTapOrSize() {
        val original = key("space")
        assertEquals(KeyCode.SYSTEM_INPUT_METHOD_PICKER, original.popupKeys!!.single().mCode)
        for (action in KeyLongPress.SPACE.actions().filter { it != KeyLongPress.DEFAULT }) {
            prefs.edit { putInt(KeyLongPress.SPACE.prefKey, action) }
            val changed = key("space")
            assertEquals(32, changed.code)
            assertEquals(original.width, changed.width)
            assertEquals(original.height, changed.height)
            assertFalse(changed.isRepeatable)
            assertEquals(action != 0, changed.isLongPressEnabled)
            if (action == 0) assertNull(changed.popupKeys)
            else {
                assertTrue(changed.hasNoPanelAutoPopupKey())
                assertEquals(action, changed.popupKeys!!.single().mCode)
            }
        }
        prefs.edit { remove(KeyLongPress.SPACE.prefKey) }
        assertEquals(KeyCode.SYSTEM_INPUT_METHOD_PICKER, key("space").popupKeys!!.single().mCode)
    }

    @Test fun disabledRepeatAndModifierKeysKeepTheirTapCodes() {
        for ((setting, label) in listOf(KeyLongPress.DELETE to "delete", KeyLongPress.SHIFT to "shift",
                KeyLongPress.LEFT to "left", KeyLongPress.RIGHT to "right", KeyLongPress.UP to "up", KeyLongPress.DOWN to "down")) {
            val before = key(label, setting.code)
            prefs.edit { putInt(setting.prefKey, KeyCode.UNSPECIFIED) }
            val after = key(label, setting.code)
            assertEquals(before.code, after.code)
            assertFalse(after.isRepeatable, setting.name)
            assertFalse(after.isLongPressEnabled, setting.name)
            assertNull(after.popupKeys)
            prefs.edit { remove(setting.prefKey) }
            assertEquals(before.isRepeatable, key(label, setting.code).isRepeatable)
        }
    }

    @Test fun commaAndEnterSlotsOverridePopupOrderWithoutChangingPrimaryAction() {
        params.mPopupKeyOrder.clear()
        for ((setting, label, code, group) in listOf(
                listOf(KeyLongPress.COMMA, "@", 64, 1),
                listOf(KeyLongPress.PERIOD, ".", 46, 2),
                listOf(KeyLongPress.ENTER, "action", 10, 3))) {
            setting as KeyLongPress
            prefs.edit { putInt(setting.prefKey, KeyCode.CLIPBOARD) }
            val changed = key(label as String, code as Int, group as Int)
            assertEquals(code, changed.code)
            assertEquals(KeyCode.CLIPBOARD, changed.popupKeys!!.single().mCode)
            assertTrue(changed.hasNoPanelAutoPopupKey())
            prefs.edit { putInt(setting.prefKey, 0) }
            assertNull(key(label, code, group).popupKeys)
        }
    }

    @Test fun unknownAndDisallowedActionsUseDefaultAndLeaveLetterAccentsAlone() {
        prefs.edit { putInt(KeyLongPress.SPACE.prefKey, 999999); putInt(KeyLongPress.DELETE.prefKey, KeyCode.SETTINGS) }
        assertEquals(KeyCode.SYSTEM_INPUT_METHOD_PICKER, key("space").popupKeys!!.single().mCode)
        assertTrue(key("delete").isRepeatable)
        val before = key("e")
        KeyLongPress.entries.forEach { setting -> prefs.edit { putInt(setting.prefKey, 0) } }
        val after = key("e")
        assertEquals(before.popupKeys?.toList(), after.popupKeys?.toList())
        assertEquals(before.code, after.code)
    }

    @Test fun upgradePreservesOldDisabledSpaceAndExplicitNewSelection() {
        prefs.edit { putInt(Settings.PREF_VERSION_CODE, 4109); putBoolean("prefs_long_press_keyboard_to_change_lang", false) }
        checkVersionUpgrade(ime)
        assertEquals(0, prefs.getInt(KeyLongPress.SPACE.prefKey, -1))
        assertFalse(prefs.contains("prefs_long_press_keyboard_to_change_lang"))
        prefs.edit { putInt(Settings.PREF_VERSION_CODE, 4109); putBoolean("prefs_long_press_keyboard_to_change_lang", false); putInt(KeyLongPress.SPACE.prefKey, KeyCode.CLIPBOARD) }
        checkVersionUpgrade(ime)
        assertEquals(KeyCode.CLIPBOARD, prefs.getInt(KeyLongPress.SPACE.prefKey, -1))
    }
}
