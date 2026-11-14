package com.kernel.ai.feature.settings

import androidx.compose.runtime.saveable.Saver

/**
 * Arrival requests for Important dates from a settings-action deep link (#1860).
 *
 * [syncRequested] runs the same path as the Calendar birthdays switch: it asks for the calendar
 * permission, or refreshes when it is already granted. [addLabel] opens the add-date editor with that
 * label filled in.
 */
data class ImportantDatesDeepLink(
    val syncRequested: Boolean = false,
    val addLabel: String? = null,
)

/**
 * Saves the screen's pending arrival request. `null` means consumed, so a restored screen never replays it.
 */
val ImportantDatesDeepLinkSaver: Saver<ImportantDatesDeepLink?, Any> = Saver(
    save = { link -> if (link == null) emptyList<Any?>() else listOf(link.syncRequested, link.addLabel) },
    restore = { saved ->
        val fields = saved as List<*>
        if (fields.isEmpty()) {
            null
        } else {
            ImportantDatesDeepLink(syncRequested = fields[0] as Boolean, addLabel = fields[1] as String?)
        }
    },
)
