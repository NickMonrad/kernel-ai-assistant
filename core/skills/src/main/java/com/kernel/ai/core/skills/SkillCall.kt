package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.diagnostics.IntentRoute
/**
 * A parsed skill invocation.
 */
data class SkillCall(
    val skillName: String,
    val arguments: Map<String, String>, // all args as strings; skills coerce as needed
    /** Maximum estimated token cost for the complete load_skill tool-result envelope. */
    val maxToolResultTokens: Int? = null,
    /** Layer that chose this call; recorded on the outcome event (#1870). */
    val route: IntentRoute = IntentRoute.OTHER,
)
