package com.kernel.ai.core.skills

/**
 * A parsed skill invocation.
 */
data class SkillCall(
    val skillName: String,
    val arguments: Map<String, String>, // all args as strings; skills coerce as needed
    /** Maximum estimated token cost for a load_skill instruction payload. */
    val maxInstructionTokens: Int? = null,
)
