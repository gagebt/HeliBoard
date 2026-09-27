// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.forEach
import helium314.keyboard.accessibility.KeyCodeDescriptionMapper
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.Locale

fun createToolbarKey(context: Context, key: ToolbarKey): ImageButton {
    val button = ImageButton(context, null, R.attr.suggestionWordStyle)
    button.scaleType = ImageView.ScaleType.CENTER
    button.tag = key
    button.contentDescription = toolbarKeyName(context, key)
    setToolbarButtonActivatedState(button)
    button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
    return button
}

fun setToolbarButtonsActivatedStateOnPrefChange(buttonsGroup: ViewGroup, key: String?) {
    // settings need to be updated when buttons change
    if (key != Settings.PREF_AUTO_CORRECTION
        && key != Settings.PREF_ALWAYS_INCOGNITO_MODE
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_GATHERING_ENABLED
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_DISABLED_BEFORE_TIME_MILLIS
        && key?.startsWith(Settings.PREF_ONE_HANDED_MODE_PREFIX) == false)
        return

    GlobalScope.launch {
        delay(10) // need to wait until SettingsValues are reloaded
        buttonsGroup.forEach { if (it is ImageButton) setToolbarButtonActivatedState(it) }
    }
}

private fun setToolbarButtonActivatedState(button: ImageButton) {
    button.isActivated = when (button.tag) {
        INCOGNITO -> button.context.prefs().getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE)
        ONE_HANDED -> Settings.getValues().mOneHandedModeEnabled
        SPLIT -> Settings.getValues().mIsSplitKeyboardEnabled
        AUTOCORRECT -> Settings.getValues().mAutoCorrectionEnabledPerUserSettings
        BACKGROUND_GATHERING -> useBackgroundGathering
        else -> true
    }
}

fun getCodeForToolbarKey(key: ToolbarKey) = Settings.getInstance().getCustomToolbarKeyCode(key) ?: defaultCodeForToolbarKey(key)

fun defaultCodeForToolbarKey(key: ToolbarKey) = when (key) {
    VOICE -> KeyCode.VOICE_INPUT
    CLIPBOARD -> KeyCode.CLIPBOARD
    NUMPAD -> KeyCode.NUMPAD
    DPAD -> KeyCode.DPAD
    UNDO -> KeyCode.UNDO
    REDO -> KeyCode.REDO
    SETTINGS -> KeyCode.SETTINGS
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_ALL
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_WORD
    COPY -> KeyCode.CLIPBOARD_COPY
    CUT -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD_PASTE
    ONE_HANDED -> KeyCode.TOGGLE_ONE_HANDED_MODE
    INCOGNITO -> KeyCode.TOGGLE_INCOGNITO_MODE
    AUTOCORRECT -> KeyCode.TOGGLE_AUTOCORRECT
    CLEAR_CLIPBOARD -> KeyCode.CLIPBOARD_CLEAR_HISTORY
    CLOSE_HISTORY -> KeyCode.CLIPBOARD
    EMOJI -> KeyCode.EMOJI
    LEFT -> KeyCode.ARROW_LEFT
    RIGHT -> KeyCode.ARROW_RIGHT
    UP -> KeyCode.ARROW_UP
    DOWN -> KeyCode.ARROW_DOWN
    WORD_LEFT -> KeyCode.WORD_LEFT
    WORD_RIGHT -> KeyCode.WORD_RIGHT
    PAGE_UP -> KeyCode.PAGE_UP
    PAGE_DOWN -> KeyCode.PAGE_DOWN
    FULL_LEFT -> KeyCode.MOVE_START_OF_LINE
    FULL_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_START -> KeyCode.MOVE_START_OF_PAGE
    PAGE_END -> KeyCode.MOVE_END_OF_PAGE
    SPLIT -> KeyCode.SPLIT_LAYOUT
    FLOATING -> KeyCode.TOGGLE_FLOATING_WINDOW
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING
}

fun getCodeForToolbarKeyLongClick(key: ToolbarKey) = Settings.getInstance().getCustomToolbarLongpressCode(key) ?: defaultCodeForToolbarKeyLongClick(key)

fun defaultCodeForToolbarKeyLongClick(key: ToolbarKey) = when (key) {
    CLIPBOARD -> KeyCode.CLIPBOARD_PASTE
    UNDO -> KeyCode.REDO
    REDO -> KeyCode.UNDO
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_WORD
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_ALL
    COPY -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD
    LEFT -> KeyCode.KEY_REPEAT
    RIGHT -> KeyCode.KEY_REPEAT
    UP -> KeyCode.KEY_REPEAT
    DOWN -> KeyCode.KEY_REPEAT
    WORD_LEFT -> KeyCode.KEY_REPEAT
    WORD_RIGHT -> KeyCode.KEY_REPEAT
    PAGE_UP -> KeyCode.MOVE_START_OF_PAGE
    PAGE_DOWN -> KeyCode.MOVE_END_OF_PAGE
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING_TEMP_OFF
    else -> KeyCode.UNSPECIFIED
}

// names need to be aligned with resources strings (using lowercase of key.name)
enum class ToolbarKey {
    VOICE, CLIPBOARD, NUMPAD, DPAD, UNDO, REDO, SETTINGS, SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE, ONE_HANDED, FLOATING, SPLIT,
    INCOGNITO, AUTOCORRECT, CLEAR_CLIPBOARD, CLOSE_HISTORY, EMOJI, LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT,
    PAGE_UP, PAGE_DOWN, FULL_LEFT, FULL_RIGHT, PAGE_START, PAGE_END, BACKGROUND_GATHERING
}

enum class ToolbarMode {
    EXPANDABLE, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN,
}

val toolbarKeyStrings = entries.associateWithTo(EnumMap(ToolbarKey::class.java)) { it.toString().lowercase(Locale.US) }

val defaultToolbarPref by lazy {
    val default = listOf(SETTINGS, VOICE, CLIPBOARD, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT)
    val others = entries.filterNot { it in default || it == CLOSE_HISTORY }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

val defaultPinnedToolbarPref = entries.filterNot { it == CLOSE_HISTORY }.joinToString(Separators.ENTRY) {
    it.name + Separators.KV + (it == VOICE)
}

val defaultClipboardToolbarPref by lazy {
    val default = listOf(CLEAR_CLIPBOARD, UP, DOWN, LEFT, RIGHT, UNDO, CUT, COPY, PASTE, SELECT_WORD, CLOSE_HISTORY)
    val others = entries.filterNot { it in default }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

/** add missing keys, typically because a new key has been added */
fun upgradeToolbarPrefs(prefs: SharedPreferences) {
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)
}

private fun upgradeToolbarPref(prefs: SharedPreferences, pref: String, default: String) {
    if (!prefs.contains(pref)) return
    val list = prefs.getString(pref, default)!!.split(Separators.ENTRY).toMutableList()
    val splitDefault = defaultToolbarPref.split(Separators.ENTRY)
    splitDefault.forEach { entry ->
        val keyWithSeparator = entry.substringBefore(Separators.KV) + Separators.KV
        if (list.none { it.startsWith(keyWithSeparator) })
            list.add("${keyWithSeparator}false")
    }
    // likely not needed, but better prepare for possibility of key removal
    list.removeAll {
        try {
            ToolbarKey.valueOf(it.substringBefore(Separators.KV))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
    }
    prefs.edit { putString(pref, list.joinToString(Separators.ENTRY)) }
}

fun getEnabledToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)

fun getPinnedToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)

fun getEnabledClipboardToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)

fun addPinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // remove the existing version of this key and add the enabled one after the last currently enabled key
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val keys = string.split(Separators.ENTRY).toMutableList()
    keys.removeAll { it.startsWith(key.name + Separators.KV) }
    val lastEnabledIndex = keys.indexOfLast { it.endsWith("true") }
    keys.add(lastEnabledIndex + 1, key.name + Separators.KV + "true")
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, keys.joinToString(Separators.ENTRY)) }
}

fun removePinnedKey(prefs: SharedPreferences, key: ToolbarKey) {
    // just set it to disabled
    val string = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV))
            key.name + Separators.KV + "false"
        else it
    }
    prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, result) }
}

private fun getEnabledToolbarKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val string = prefs.getString(pref, default)!!
    return string.split(Separators.ENTRY).mapNotNull {
        val split = it.split(Separators.KV)
        if (split.last() == "true") {
            try {
                ToolbarKey.valueOf(split.first())
            } catch (_: IllegalArgumentException) {
                null
            }
        } else null
    }
}

fun writeCustomKeyCodes(prefs: SharedPreferences, codes: EnumMap<ToolbarKey, Pair<Int?, Int?>>) {
    val string = codes.mapNotNull { entry -> entry.value?.let { "${entry.key.name},${it.first},${it.second}" } }.joinToString(";")
    prefs.edit { putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, string) }
}

fun readCustomKeyCodes(prefs: SharedPreferences): EnumMap<ToolbarKey, Pair<Int?, Int?>> {
    val map = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
    prefs.getString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Defaults.PREF_TOOLBAR_CUSTOM_KEY_CODES)!!
        .split(";").forEach {
            runCatching {
                val s = it.split(",")
                map[ToolbarKey.valueOf(s[0])] = s[1].toIntOrNull() to s[2].toIntOrNull()
            }
        }
    return map
}

fun getCustomKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.first
}

fun getCustomLongpressKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.second
}

fun clearCustomToolbarKeyCodes() {
    customToolbarKeyCodes = null
}

fun onClickToolbarKey(view: View, onCodeInput: (Int) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
    val code = getCodeForToolbarKey(view.tag as ToolbarKey)
    if (code != KeyCode.UNSPECIFIED) {
        onCodeInput(code)
    }
}

fun onLongClickToolbarKey(view: View, onCodeInput: (Int, Boolean) -> Unit) {
    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_LONG_PRESS)
    val key = view.tag as ToolbarKey
    val hint = toolbarKeyHint(view.context, key)
    val longClickCode = getCodeForToolbarKeyLongClick(key)
    if (longClickCode == KeyCode.KEY_REPEAT) {
        onClickToolbarKey(view) { onCodeInput(it, false) }
        repeatToolbarKey(view) { onClickToolbarKey(view) { onCodeInput(it, true) } }
    } else if (longClickCode != KeyCode.UNSPECIFIED) {
        onCodeInput(longClickCode, false)
    }
    showToolbarHint(hint)
}

fun toolbarKeyName(context: Context, key: ToolbarKey): String {
    val code = getCodeForToolbarKey(key)
    return when {
        code == KeyCode.VOICE_INPUT -> context.getString(R.string.voice_button_hint)
        code == defaultCodeForToolbarKey(key) -> key.name.lowercase().getStringResourceOrName("", context)
        else -> toolbarCodeName(context, code)
    }
}

/** Both names come from the same effective codes that the buttons dispatch. */
fun toolbarKeyHint(context: Context, key: ToolbarKey, holdAction: CharSequence? = null): String {
    val main = toolbarKeyName(context, key)
    val hold = holdAction ?: when (val code = getCodeForToolbarKeyLongClick(key)) {
        KeyCode.UNSPECIFIED -> return main
        KeyCode.KEY_REPEAT -> context.getString(R.string.button_hold_repeat)
        else -> toolbarCodeName(context, code)
    }
    return context.getString(R.string.button_hold_hint, main, hold)
}

private fun toolbarCodeName(context: Context, code: Int): String = when (code) {
    KeyCode.UNSPECIFIED -> context.getString(R.string.action_none)
    KeyCode.VOICE_INPUT -> context.getString(R.string.voice_button_hint)
    KeyCode.CAPS_LOCK -> context.getString(R.string.label_shift_key_locked)
    KeyCode.BACKGROUND_GATHERING_TEMP_OFF -> context.getString(R.string.button_pause_gesture_gathering)
    else -> toolbarActionName(context, code)
        ?: KeyCodeDescriptionMapper.instance.getDescriptionForCodePoint(context, code)
        ?: "${context.getString(R.string.key_code)}: $code"
}

/** Functional keys use the same in-keyboard hints as toolbar buttons, without changing the hold. */
fun keyboardKeyHint(context: Context, keyboard: Keyboard,
    key: Key, holdAction: Boolean): String? {
    val code = key.code
    if (!hasKeyboardKeyHint(code)) return null
    val mapper = KeyCodeDescriptionMapper.instance
    val main = mapper.getDescriptionForKey(context, keyboard, key, false) ?: return null
    val hold = when {
        key.isRepeatable -> context.getString(R.string.button_hold_repeat)
        !holdAction -> null
        key.hasNoPanelAutoPopupKey() -> toolbarCodeName(context, key.popupKeys!![0].mCode)
        code == KeyCode.LANGUAGE_SWITCH || code == Constants.CODE_SPACE
            && key.popupKeys == null && Settings.getValues().mSpaceForLangChange -> context.getString(R.string.select_input_method)
        code == KeyCode.SYMBOL_ALPHA -> context.getString(R.string.spoken_description_to_numeric)
        else -> key.popupKeys?.map { popup -> popup.mLabel?.takeIf { it.isNotBlank() }
            ?: toolbarCodeName(context, popup.mCode) }?.distinct()?.joinToString(", ")
    }
    return if (hold.isNullOrEmpty()) main else context.getString(R.string.button_hold_hint, main, hold)
}

fun hasKeyboardKeyHint(code: Int) = code < 0 || code == Constants.CODE_SPACE
    || code == Constants.CODE_ENTER || code == Constants.CODE_TAB

fun showKeyboardKeyHint(keyboard: Keyboard,
    key: Key, holdAction: Boolean) {
    val context = KeyboardSwitcher.getInstance().mainKeyboardView?.context ?: return
    showToolbarHint(keyboardKeyHint(context, keyboard, key, holdAction))
}

/** Shows a key hint inside the keyboard view; Android 13+ suppresses system toasts from a keyboard. */
@JvmOverloads
fun showToolbarHint(text: CharSequence?, millis: Int = 2000) {
    if (text.isNullOrEmpty()) return
    val duration = maxOf(millis, (1500 + text.length * 40).coerceAtMost(3500))
    KeyboardSwitcher.getInstance().showToast(text.toString(), duration)
}

/** The toolbar key whose tap does [code] by default, or null if no key does. */
fun toolbarKeyForCode(code: Int): ToolbarKey? =
    ToolbarKey.entries.firstOrNull { it != CLOSE_HISTORY && defaultCodeForToolbarKey(it) == code }

/** The label of the toolbar action that sends [code], or null if no toolbar key has that action. */
fun toolbarActionName(context: Context, code: Int): String? =
    toolbarKeyForCode(code)?.let { it.name.lowercase().getStringResourceOrName("", context) }

private fun repeatToolbarKey(view: View, onClick: (view: View) -> Unit) {
    view.handler.postDelayed({
        if (view.isPressed) {
            onClick(view)
            repeatToolbarKey(view, onClick)
        }
    }, view.resources.getInteger(R.integer.config_key_repeat_interval).toLong())
}

private var customToolbarKeyCodes: EnumMap<ToolbarKey, Pair<Int?, Int?>>? = null
