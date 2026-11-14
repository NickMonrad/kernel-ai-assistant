package com.kernel.ai.feature.settings

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
