package com.kernel.ai.core.skills

/**
 * Contract for all native skills. Each skill:
 * - Has a unique [name] matching the FunctionGemma routing key
 * - Declares its [schema] (JSON Schema object) for FunctionGemma's function definitions
 * - Optionally provides [examples] — ready-to-paste <|tool_call> lines shown in the system prompt
 *   (used by gateway skills like run_intent to show multiple concrete invocations)
 * - Executes with a [SkillCall] and returns a [SkillResult]
 */
interface Skill {
    val name: String
    val description: String
    val schema: SkillSchema
    val examples: List<String> get() = emptyList()
    suspend fun execute(call: SkillCall): SkillResult
}
