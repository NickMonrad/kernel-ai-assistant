package com.kernel.ai.core.skills

/**
 * Builds the failures returned when a birthday or date query finds nothing (#1860).
 *
 * Both cases use the settings-action pattern from #1859: `error` is the full text-only guidance,
 * and `actions` are in-app buttons. Surfaces that do not render buttons (chat text, model replies,
 * voice) print only `error`.
 *
 * - Calendar permission granted and no birthdays in Android Calendar and no saved dates at all: a hint
 *   plus "Turn on calendar sync", which opens the Important dates screen and refreshes calendar birthdays.
 * - Anything else that matches nothing: the short not-found text plus "Add an important date", which
 *   opens the add editor with the query as the label.
 *
 * A birthday query with calendar permission missing is not a failure here. It stays
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

    /** Failure when a birthday query finds nothing, calendar permission is granted, and no dates exist anywhere. */
    fun noDatesYetFailure(skillName: String): SkillResult.Failure = SkillResult.Failure(
        skillName = skillName,
        error = "Nothing saved yet, no dramas. Turn on calendar sync and I'll check your birthdays.",
        technicalDetail = "No birthdays or important dates yet: $skillName",
        actions = listOf(
            SettingsAction(
                label = "Turn on calendar sync",
                deepLink = SettingsDeepLink(IMPORTANT_DATES_DESTINATION, mapOf(ARG_SYNC to "true")),
            ),
        ),
    )

    /** Failure for a date query that nothing matched while calendar birthday sync is on. */
    fun notFoundFailure(skillName: String, label: String): SkillResult.Failure {
        val name = label.trim()
        return SkillResult.Failure(
            skillName = skillName,
            error = "Couldn't find $name in your important dates or calendar, no dramas. " +
                "Say \"$name is 3 June\" to teach Jandal.",
            technicalDetail = "Unresolved important date: $skillName",
            actions = listOf(
                SettingsAction(
                    label = "Add an important date",
                    deepLink = SettingsDeepLink(
                        IMPORTANT_DATES_DESTINATION,
                        mapOf(ARG_ADD to "true", ARG_LABEL to name),
                    ),
                ),
            ),
        )
    }
}
