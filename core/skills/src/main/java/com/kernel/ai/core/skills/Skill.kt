package com.kernel.ai.core.skills

/**
 * Contract for all native skills. Each skill:
 * - Has a unique [name] matching the FunctionGemma routing key
 * - Declares its [schema] (JSON Schema object) for FunctionGemma's function definitions
 * - Executes with a [SkillCall] and returns a [SkillResult]
 */
interface Skill {
    val name: String
    val description: String
    val schema: SkillSchema
    suspend fun execute(call: SkillCall): SkillResult
}
