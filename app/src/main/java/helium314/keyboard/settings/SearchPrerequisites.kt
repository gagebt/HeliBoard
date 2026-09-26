// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/** One row of the settings search results. */
sealed interface SearchRow {
    data class Result(val key: String) : SearchRow
    /** "Needs: <parent>" with the parent switch, shown once above the first result that depends on it. */
    data class Needs(val parentKey: String) : SearchRow
}

// Settings that act only while a parent switch is on. The settings screens hide these while the parent is off,
// but search shows them, so search must say why they do nothing.
private val searchParents: Map<String, String> = mapOf(
    Settings.PREF_GESTURE_PREVIEW_TRAIL to Settings.PREF_GESTURE_INPUT,
    Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT to Settings.PREF_GESTURE_INPUT,
    Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC to Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
    Settings.PREF_GESTURE_SPACE_AWARE to Settings.PREF_GESTURE_INPUT,
    Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN to Settings.PREF_GESTURE_INPUT,
    Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION to Settings.PREF_GESTURE_INPUT,
    Settings.PREF_VIBRATION_DURATION_SETTINGS to Settings.PREF_VIBRATE_ON,
    Settings.PREF_VIBRATE_IN_DND_MODE to Settings.PREF_VIBRATE_ON,
    Settings.PREF_KEYPRESS_SOUND_VOLUME to Settings.PREF_SOUND_ON,
    Settings.PREF_POPUP_KEYS_HINT_ORDER to Settings.PREF_SHOW_HINTS,
    Settings.PREF_HINT_FONT_SCALE to Settings.PREF_SHOW_HINTS,
    Settings.PREF_VOICE_SAVE_DICTATIONS_TO_HISTORY to Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
    Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME to Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
    Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST to Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
    Settings.PREF_CLIPBOARD_USE_FILES to Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
    Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT to Settings.PREF_CLIPBOARD_USE_FILES,
    Settings.PREF_MORE_AUTO_CORRECTION to Settings.PREF_AUTO_CORRECTION,
    Settings.PREF_AUTOCORRECT_SHORTCUTS to Settings.PREF_AUTO_CORRECTION,
    Settings.PREF_AUTOCORRECT_CAPITALIZED_SUGGESTION to Settings.PREF_AUTO_CORRECTION,
    Settings.PREF_AUTO_CORRECT_CONFIDENCE to Settings.PREF_AUTO_CORRECTION,
    Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT to Settings.PREF_AUTO_CORRECTION,
)

/** Default of each parent switch, for reading its current state. */
val searchParentDefaults: Map<String, Boolean> = mapOf(
    Settings.PREF_GESTURE_INPUT to Defaults.PREF_GESTURE_INPUT,
    Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT to Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
    Settings.PREF_VIBRATE_ON to Defaults.PREF_VIBRATE_ON,
    Settings.PREF_SOUND_ON to Defaults.PREF_SOUND_ON,
    Settings.PREF_SHOW_HINTS to Defaults.PREF_SHOW_HINTS,
    Settings.PREF_ENABLE_CLIPBOARD_HISTORY to Defaults.PREF_ENABLE_CLIPBOARD_HISTORY,
    Settings.PREF_CLIPBOARD_USE_FILES to Defaults.PREF_CLIPBOARD_USE_FILES,
    Settings.PREF_AUTO_CORRECTION to Defaults.PREF_AUTO_CORRECTION,
)

/**
 * Orders search results with one [SearchRow.Needs] row above the first result whose parent switch is off.
 * The outermost switched-off parent is named. The parent itself is not listed a second time: a later result row
 * for it is dropped, and no Needs row is added when the parent is already listed above its dependent.
 */
fun withPrerequisites(keys: List<String>, isOn: (String) -> Boolean): List<SearchRow> {
    val rows = mutableListOf<SearchRow>()
    val listed = mutableSetOf<String>()
    for (key in keys) {
        var parent = searchParents[key]
        var offParent: String? = null
        while (parent != null) {
            if (!isOn(parent)) offParent = parent
            parent = searchParents[parent]
        }
        if (offParent != null && offParent !in listed) {
            rows.add(SearchRow.Needs(offParent))
            listed.add(offParent)
        }
        if (key in listed) continue
        rows.add(SearchRow.Result(key))
        listed.add(key)
    }
    return rows
}
