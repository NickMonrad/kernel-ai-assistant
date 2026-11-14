package com.kernel.ai.core.skills

/**
 * Builds the failures returned when a date query finds nothing (#1860).
 *
 * `error` is the full text-only guidance; `actions` are in-app buttons. Surfaces that do not render
 * buttons (chat text, model replies, voice) print only `error`.
 *
 * - [notFoundFailure]: short not-found text plus "Add an important date". Used for every date label that
 *   matched nothing. Anniversaries and other non-birthday labels never search the calendar.
 * - [calendarNoBirthdaysFailure]: birthday query, calendar permission granted, and Android Calendar holds
 *   no birthdays at all. Says so, notes that contact accounts must sync their birthdays, and offers
 *   "Add an important date" plus "Open Important dates".
 *
 * A birthday query with calendar permission missing is not a failure here. The handler returns
 * `CapabilityRequired(CalendarLookup)`, so the in-app prompt, App info when permanently denied, and the
 * auto-answer after grant still run.
 *
 * Pure Kotlin, so it is unit-testable without Android.
 */
object ImportantDateRecovery {
    /** Destination id for Important dates in [SettingsDeepLink.destination]. */
    const val IMPORTANT_DATES_DESTINATION = "important_dates"

    /** `"true"` asks for calendar birthday access on arrival, as the screen's sync toggle would. */
    const val ARG_SYNC = "sync"

    /** `"true"` opens the add-date editor. */
    const val ARG_ADD = "add"

    /** Label prefilled in the add-date editor. */
    const val ARG_LABEL = "label"

    /**
     * Not-found failure for [label]. [searchedCalendar] is true only for birthday queries that were
     * checked against Android Calendar, so the wording names the calendar only when it was searched.
     */
    fun notFoundFailure(skillName: String, label: String, searchedCalendar: Boolean = false): SkillResult.Failure {
        val name = label.trim()
        val where = if (searchedCalendar) "your important dates or Android Calendar" else "your important dates"
        return SkillResult.Failure(
            skillName = skillName,
            error = "Couldn't find $name in $where, no dramas. Say \"$name is 3 June\" to teach me.",
            technicalDetail = "Unresolved important date: $skillName",
            actions = listOf(addAction(name)),
        )
    }

    /** Birthday query with calendar permission granted, but Android Calendar has no birthdays to match. */
    fun calendarNoBirthdaysFailure(skillName: String, label: String): SkillResult.Failure {
        val name = label.trim()
        return SkillResult.Failure(
            skillName = skillName,
            error = "Couldn't find $name. Android Calendar has no birthdays yet, and contact accounts need to " +
                "sync them first. Say \"$name is 3 June\" to teach me.",
            technicalDetail = "Calendar has no birthdays: $skillName",
            actions = listOf(
                addAction(name),
                SettingsAction(
                    label = "Open Important dates",
                    deepLink = SettingsDeepLink(IMPORTANT_DATES_DESTINATION),
                ),
            ),
        )
    }

    private fun addAction(name: String) = SettingsAction(
        label = "Add an important date",
        deepLink = SettingsDeepLink(IMPORTANT_DATES_DESTINATION, mapOf(ARG_ADD to "true", ARG_LABEL to name)),
    )
}
