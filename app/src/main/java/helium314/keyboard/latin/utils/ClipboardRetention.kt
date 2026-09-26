// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.R

/** The History retention time choices (minutes to label), in the order the setting lists them. -1 is "No limit". */
val clipboardRetentionChoices = listOf(
    1 to R.string.retention_1_minute, 5 to R.string.retention_5_minutes,
    10 to R.string.retention_10_minutes, 30 to R.string.retention_30_minutes,
    60 to R.string.retention_1_hour, 120 to R.string.retention_2_hours,
    360 to R.string.retention_6_hours, 720 to R.string.retention_12_hours,
    1440 to R.string.retention_1_day, 4320 to R.string.retention_3_days,
    10080 to R.string.retention_7_days, 20160 to R.string.retention_14_days,
    43200 to R.string.retention_30_days, 129600 to R.string.retention_90_days,
    259200 to R.string.retention_180_days, 525600 to R.string.retention_365_days,
    -1 to R.string.settings_no_limit
)

/** The retention time as the setting names it; a value the list lacks is shown in minutes. */
fun clipboardRetentionLabel(context: Context, minutes: Int): String {
    val choice = clipboardRetentionChoices.firstOrNull { it.first == minutes }
        ?: if (minutes <= 0) clipboardRetentionChoices.last() else null
    return choice?.let { context.getString(it.second) }
        ?: context.getString(R.string.pf6_retention_minutes, minutes)
}

/** The empty clipboard view's line about how long copies are kept. */
fun clipboardEmptyRetentionText(context: Context, minutes: Int): String =
    if (minutes <= 0) context.getString(R.string.pf6_clipboard_kept_no_limit)
    else context.getString(R.string.pf6_clipboard_kept_for, clipboardRetentionLabel(context, minutes))
