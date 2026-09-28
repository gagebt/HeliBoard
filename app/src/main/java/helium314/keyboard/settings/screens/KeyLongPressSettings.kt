// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyLongPress
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.toolbarCodeName
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference

const val KEY_LONG_PRESSES = "key_long_presses"

fun createKeyLongPressSettings(context: Context): List<Setting> {
    val keys = KeyLongPress.entries.map { key ->
        Setting(key.prefKey, context.getString(R.string.key_long_press_title,
            toolbarCodeName(context, key.code)), null) { setting ->
            val choices = key.actions().map { code ->
                (if (code == KeyLongPress.DEFAULT) context.getString(R.string.button_default)
                else toolbarCodeName(context, code)) to code
            }
            ListPreference(setting, choices, KeyLongPress.DEFAULT) {
                KeyboardLayoutSet.onSystemLocaleChanged()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
        }
    }
    return keys + Setting(context, KEY_LONG_PRESSES, R.string.key_long_presses,
        R.string.key_long_presses_summary) { setting ->
        var show by rememberSaveable { mutableStateOf(false) }
        Preference(name = setting.title, description = setting.description, onClick = { show = true })
        if (show) ThreeButtonAlertDialog(
            onDismissRequest = { show = false }, onConfirmed = {}, confirmButtonText = null,
            cancelButtonText = stringResource(R.string.dialog_close),
            title = { Text(setting.title) },
            content = {
                LazyColumn {
                    item { Text(stringResource(R.string.key_long_presses_summary)) }
                    items(keys, key = { it.key }) { it.Preference() }
                    item { SettingsActivity.settingsContainer[Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD]?.Preference() }
                }
            })
    }
}
