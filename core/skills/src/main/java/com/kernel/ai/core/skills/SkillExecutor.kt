package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses FunctionGemma's raw JSON output into a [SkillCall] and executes it via [SkillRegistry].
 *
 * FunctionGemma output format:
 * {"name": "skill_name", "arguments": {"param1": "value1"}}
 */
@Singleton
class SkillExecutor @Inject constructor(
    private val registry: SkillRegistry,
) {
    suspend fun execute(rawFunctionGemmaOutput: String): SkillResult {
        val call = parseSkillCall(rawFunctionGemmaOutput)
            ?: return SkillResult.ParseError(rawFunctionGemmaOutput, "Invalid JSON or missing 'name' field")

        val skill = registry.get(call.skillName)
            ?: return SkillResult.UnknownSkill(call.skillName)

        val missingParams = skill.schema.required.filter { it !in call.arguments }
        if (missingParams.isNotEmpty()) {
            return SkillResult.ParseError(
                rawFunctionGemmaOutput,
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
            val json = org.json.JSONObject(raw.trim())
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
