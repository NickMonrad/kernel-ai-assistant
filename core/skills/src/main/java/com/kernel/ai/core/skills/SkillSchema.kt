package com.kernel.ai.core.skills

/**
 * JSON Schema representation for a skill's parameters.
 * Used to generate the function_declarations payload for the system prompt.
 */
data class SkillSchema(
    val parameters: Map<String, SkillParameter> = emptyMap(),
    val required: List<String> = emptyList(),
)

data class SkillParameter(
    val type: String,           // "string", "boolean", "integer", "number"
    val description: String,
    val enum: List<String>? = null,
)
