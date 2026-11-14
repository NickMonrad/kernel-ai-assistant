package com.kernel.ai.core.skills

/**
 * Builds the failures returned when a birthday or date query finds nothing (#1860).
 *
 * Both cases use the settings-action pattern from #1859: `error` is the full text-only guidance,
 * and `actions` are in-app buttons. Surfaces that do not render buttons (chat text, model replies,
 * voice) print only `error`.
 *
 * - Calendar birthday sync off or not permitted: a hint plus "Turn on calendar sync", which opens
 *   the Important dates screen and asks for the calendar permission there.
 * - Sync on, nothing matches: the short not-found text plus "Add an important date", which opens the
 *   add editor with the query as the label.
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

    /** Failure for a date query when calendar birthday sync is off or not permitted. */
    fun syncOffFailure(skillName: String): SkillResult.Failure = SkillResult.Failure(
        skillName = skillName,
        error = "I can't see any birthdays yet. Turn on calendar sync and I'll know them.",
        technicalDetail = "Calendar birthday sync off: $skillName",
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
            error = "I couldn't find $name in your important dates or calendar. " +
                "Say \"$name is 3 June\" to teach me.",
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
