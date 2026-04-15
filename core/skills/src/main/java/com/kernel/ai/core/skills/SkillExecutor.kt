package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a skill by name with the given arguments via [SkillRegistry].
 */
@Singleton
class SkillExecutor @Inject constructor(
    private val registry: SkillRegistry,
) {
    suspend fun execute(rawOutput: String): SkillResult {
        val call = parseSkillCall(rawOutput)
            ?: return SkillResult.ParseError(rawOutput, "Invalid JSON or missing 'name' field")

        val skill = registry.get(call.skillName)
            ?: return SkillResult.UnknownSkill(call.skillName)

        val missingParams = skill.schema.required.filter { it !in call.arguments }
        if (missingParams.isNotEmpty()) {
            return SkillResult.ParseError(
                rawOutput,
                "Missing required parameters: ${missingParams.joinToString()}"
            )
        }

        return try {
            skill.execute(call)
        } catch (e: Exception) {
            SkillResult.Failure(call.skillName, e.message ?: "Unknown error")
        }
    }

    private fun parseSkillCall(raw: String): SkillCall? {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = org.json.JSONObject(cleaned)
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
            val argsObj = json.optJSONObject("arguments") ?: org.json.JSONObject()
            val args = mutableMapOf<String, String>()
            argsObj.keys().forEach { key -> args[key] = argsObj.optString(key) }
            SkillCall(skillName = name, arguments = args)
        } catch (e: Exception) {
            null
        }
    }
}
