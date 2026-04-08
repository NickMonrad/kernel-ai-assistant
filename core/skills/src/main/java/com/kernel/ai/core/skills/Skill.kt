package com.kernel.ai.core.skills

/** Permission level required by a skill. */
enum class PermissionLevel {
    /** No dangerous Android permissions needed. */
    STANDARD,
    /** Requires one or more dangerous Android permissions. */
    PRIVILEGED
}

/** Result of executing a skill. */
sealed class SkillResult {
    data class Success(val data: Map<String, Any?>) : SkillResult()
    data class Error(val message: String) : SkillResult()
}

/**
 * Contract for all skills (native Kotlin and Wasm).
 * Each skill declares its identity, parameter schema, and execution logic.
 */
interface Skill {
    val id: String
    val name: String
    val description: String
    val parameterSchema: String // JSON Schema string
    val permissionLevel: PermissionLevel
    suspend fun execute(params: Map<String, Any?>): SkillResult
}
