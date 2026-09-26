// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.screens.createAboutSettings
import helium314.keyboard.settings.screens.createAdvancedSettings
import helium314.keyboard.settings.screens.createAppearanceSettings
import helium314.keyboard.settings.screens.createCorrectionSettings
import helium314.keyboard.settings.screens.createGestureTypingSettings
import helium314.keyboard.settings.screens.createLayoutSettings
import helium314.keyboard.settings.screens.createPreferencesSettings
import helium314.keyboard.settings.screens.createToolbarSettings

class SettingsContainer(context: Context) {
    private val list = createSettings(context) + createSearchDestinations(context)
    private val map: Map<String, Setting> = HashMap<String, Setting>(list.size).apply {
        list.forEach {
            if (put(it.key, it) != null)
                throw IllegalArgumentException("key ${it.key} added twice")
        }
    }

    operator fun get(key: Any): Setting? = map[key]

    private data class Aliases(val phrases: List<String>, val choices: List<String>)

    private val aliases: Map<String, Aliases> = context.resources.openRawResource(R.raw.settings_search)
        .bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.associate { line ->
                val fields = validatedSearchFields(line)
                val english = fields[1].split(';').map(String::trim)
                val russian = fields[2].split(';').map(String::trim)
                val choices = fields.getOrNull(3)?.split(',')?.map { name ->
                    val id = context.resources.getIdentifier(name.trim(), "string", context.packageName)
                    require(id != 0) { "missing search choice: $name" }
                    context.getString(id)
                }.orEmpty()
                fields[0] to Aliases(english + russian, choices)
            }
        }

    private val matcher = SearchMatcher(list.map { setting ->
        val meta = aliases[setting.key] ?: aliases[setting.key.substringBefore('/', setting.key)]
        SearchDocument(setting.key, setting.title, setting.description,
            meta?.phrases.orEmpty(), meta?.choices.orEmpty())
    })

    internal fun searchCoverageIssues(): List<String> = missingSearchAliases(searchKeys(), metadataKeys())

    internal fun searchKeys(): Set<String> = list.map { it.key }.toSet()
    internal fun metadataKeys(): Set<String> = aliases.keys

    fun smartFilter(searchTerm: String): List<Setting> {
        if (searchTerm.trim().length < 3) return filter(searchTerm)
        val matches = matcher.rank(searchTerm).mapNotNull(map::get)
        return matches.ifEmpty { filter(searchTerm) }
    }

    // Original title/description prefix search, kept for Normal mode.
    fun filter(searchTerm: String): List<Setting> {
        val term = searchTerm.lowercase()
        val results = mutableSetOf<Setting>()
        list.forEach { setting -> if (setting.title.lowercase().startsWith(term)) results.add(setting) }
        list.forEach { setting -> if (setting.title.lowercase().split(' ').any { it.startsWith(term) }) results.add(setting) }
        list.forEach { setting ->
            if (setting.description?.lowercase()?.split(' ')?.any { it.startsWith(term) } == true)
                results.add(setting)
        }
        return results.toList()
    }
}

@Immutable
class Setting(
    val key: String,
    val title: String,
    val description: String?,
    private val content: @Composable (Setting) -> Unit
) {
    constructor(context: Context, key: String, @StringRes titleId: Int,
                @StringRes descriptionId: Int? = null,
                content: @Composable (Setting) -> Unit) : this(
        key, context.getString(titleId), descriptionId?.let { context.getString(it) }, content)

    @Composable
    fun Preference() {
        content(this)
    }
}

internal fun validatedSearchFields(line: String): List<String> = line.split('|').also { fields ->
    require(fields.size in 3..4) { "invalid search metadata: $line" }
    require(fields[1].split(';').count(String::isNotBlank) >= 3 &&
            fields[2].split(';').count(String::isNotBlank) >= 3) {
        "missing search phrases: ${fields[0]}"
    }
}

internal fun missingSearchAliases(keys: Collection<String>, metadataKeys: Set<String>): List<String> =
    keys.filter { it.substringBefore('/', it) !in metadataKeys }

// Page destinations are searchable even though they do not own one global preference key.
private fun createSearchDestinations(context: Context): List<Setting> = buildList {
    fun page(key: String, title: Int, description: Int, destination: String) {
        add(Setting(context, key, title, description) { setting ->
            Preference(name = setting.title, description = setting.description,
                onClick = { SettingsDestination.navigateTo(destination) }) { NextScreenIcon() }
        })
    }
    page("nav_preferences", R.string.settings_screen_preferences,
        R.string.pf6_preferences_summary, SettingsDestination.Preferences)
    page("nav_languages", R.string.language_and_layouts_title,
        R.string.search_languages_summary, SettingsDestination.Languages)
    page("nav_dictionaries", R.string.dictionary_settings_category,
        R.string.search_dictionaries_summary, SettingsDestination.Dictionaries)
    page("nav_layouts", R.string.settings_screen_secondary_layouts,
        R.string.search_layouts_summary, SettingsDestination.Layouts)
    SubtypeSettings.getEnabledSubtypes(true).distinctBy { it.toSettingsSubtype().toPref() }.forEach { subtype ->
        val settingsSubtype = subtype.toSettingsSubtype()
        val destination = SettingsDestination.Subtype + settingsSubtype.toPref()
        val name = subtype.displayName()
        add(Setting("nav_subtype/$destination", context.getString(R.string.search_subtype_title, name),
            context.getString(R.string.search_subtype_summary)) { setting ->
            Preference(name = setting.title, description = setting.description,
                onClick = { SettingsDestination.navigateTo(destination) }) { NextScreenIcon() }
        })
    }
}

// intentionally not putting individual debug settings in here so user knows the context
private fun createSettings(context: Context) = createAboutSettings(context) + createAppearanceSettings(context) +
        createCorrectionSettings(context) + createPreferencesSettings(context) + createToolbarSettings(context) +
        createLayoutSettings(context) + createAdvancedSettings(context) +
        if (JniUtils.hasGestureTyping()) createGestureTypingSettings(context) else emptyList()

object SettingsWithoutKey {
    const val EDIT_PERSONAL_DICTIONARY = "edit_personal_dictionary"
    const val APP = "app"
    const val VERSION = "version"
    const val LICENSE = "license"
    const val HIDDEN_FEATURES = "hidden_features"
    const val GITHUB = "github"
    const val GITHUB_WIKI = "github_wiki"
    const val COMMUNITY_LINKS = "community_links"
    const val SAVE_LOG = "save_log"
    const val BACKUP_RESTORE = "backup_restore"
    const val DEBUG_SETTINGS = "screen_debug"
    const val LOAD_GESTURE_LIB = "load_gesture_library"
    const val BACKGROUND_IMAGE = "background_image"
    const val BACKGROUND_IMAGE_LANDSCAPE = "background_image_landscape"
    const val CUSTOM_FONT = "custom_font"
    const val CUSTOM_EMOJI_FONT = "custom_emoji_font"
}
