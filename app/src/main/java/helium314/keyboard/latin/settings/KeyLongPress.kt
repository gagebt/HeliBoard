// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyData
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyLabel
import helium314.keyboard.latin.common.Constants

/** Only the hold changes. Character variants remain part of the layout. */
enum class KeyLongPress(val code: Int, val disableOnly: Boolean = false) {
    SPACE(Constants.CODE_SPACE), COMMA(','.code), PERIOD('.'.code), ENTER(Constants.CODE_ENTER),
    LANGUAGE(KeyCode.LANGUAGE_SWITCH), EMOJI(KeyCode.EMOJI), CLIPBOARD(KeyCode.CLIPBOARD),
    SHIFT(KeyCode.SHIFT, true), DELETE(KeyCode.DELETE, true),
    LEFT(KeyCode.ARROW_LEFT, true), RIGHT(KeyCode.ARROW_RIGHT, true),
    UP(KeyCode.ARROW_UP, true), DOWN(KeyCode.ARROW_DOWN, true);

    val prefKey get() = "key_long_press/" + name.lowercase(java.util.Locale.ROOT)
    fun actions() = if (disableOnly) listOf(DEFAULT, KeyCode.UNSPECIFIED) else listOf(
        DEFAULT, KeyCode.UNSPECIFIED, KeyCode.VOICE_INPUT, KeyCode.LANGUAGE_SWITCH,
        KeyCode.CLIPBOARD, KeyCode.EMOJI, KeyCode.NUMPAD, KeyCode.DPAD,
        KeyCode.SYSTEM_INPUT_METHOD_PICKER, KeyCode.SETTINGS)

    companion object {
        const val DEFAULT = Int.MIN_VALUE
        fun forKey(key: KeyData, code: Int): KeyLongPress? = when (key.groupId) {
            KeyData.GROUP_COMMA -> COMMA
            KeyData.GROUP_PERIOD -> PERIOD
            KeyData.GROUP_ENTER -> ENTER
            else -> when (key.label) {
                KeyLabel.COMMA -> COMMA
                KeyLabel.PERIOD -> PERIOD
                KeyLabel.ACTION -> ENTER
                else -> entries.firstOrNull { it.code == code }
            }
        }
    }
}

fun readKeyLongPress(prefs: SharedPreferences, key: KeyLongPress): Int? =
    runCatching { prefs.getInt(key.prefKey, KeyLongPress.DEFAULT) }.getOrNull()
        ?.takeIf { it != KeyLongPress.DEFAULT && it in key.actions() }
