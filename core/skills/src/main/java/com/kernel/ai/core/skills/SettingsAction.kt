package com.kernel.ai.core.skills

/**
 * An in-app destination that a [SettingsAction] opens.
 *
 * [destination] is a stable id (for example `"people_contacts"`). The app's nav host maps ids to
 * routes, so `core/skills` stays free of navigation dependencies. [args] are optional prefill
 * values for the destination (for example `alias`, `add`, `pick`). The destination decides which
 * keys it understands and ignores the rest. Deep links never perform side effects by themselves.
 */
data class SettingsDeepLink(
    val destination: String,
    val args: Map<String, String> = emptyMap(),
)

/**
 * A labelled button offered by [SkillResult.Failure.actions]. Activating it opens [deepLink].
 */
data class SettingsAction(
    val label: String,
    val deepLink: SettingsDeepLink,
)
