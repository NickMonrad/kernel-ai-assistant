package com.kernel.ai.core.skills

/**
 * Contract for all native skills. Each skill:
 * - Has a unique [name] matching the QuickIntentRouter routing key
 * - Declares its [schema] (JSON Schema object) for tool documentation
 * - Optionally provides [examples] — ready-to-paste <|tool_call> lines shown in the system prompt
 *   (used by gateway skills like run_intent to show multiple concrete invocations)
 * - Provides [fullInstructions] — complete parameter docs + examples + rules, returned by
 *   LoadSkillSkill on demand so the system prompt stays minimal (#341)
 * - Executes with a [SkillCall] and returns a [SkillResult]
 */
interface Skill {
    val name: String
    val description: String
    val schema: SkillSchema
    val examples: List<String> get() = emptyList()

    /**
     * Full self-contained instructions for this skill: description, parameters, examples, and
     * any enforcement rules. Returned by [LoadSkillSkill] when the model calls
     * `load_skill{skill_name:"<name>"}`. Defaults to building from schema + examples.
     * Override to inject skill-specific rules that previously lived in buildSystemPrompt().
     */
    val fullInstructions: String
        get() = buildString {
            append("$name: $description\n")
            if (schema.parameters.isNotEmpty()) {
                append("\nParameters:\n")
                schema.parameters.forEach { (k, v) ->
                    val req = if (k in schema.required) " (required)" else ""
                    val enumNote = if (!v.enum.isNullOrEmpty()) " [${v.enum!!.joinToString(", ")}]" else ""
                    append("- $k$req (${v.type}$enumNote): ${v.description}\n")
                }
            }
            if (examples.isNotEmpty()) {
                append("\nExamples:\n")
                examples.forEach { append("  $it\n") }
            }
        }

    suspend fun execute(call: SkillCall): SkillResult
}
