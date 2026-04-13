package com.kernel.ai.core.skills

import android.util.Log
import com.kernel.ai.core.skills.js.JsSkillRunner
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Gateway skill that mirrors Google AI Edge Gallery's `run_js` pattern.
 *
 * Executes a JavaScript skill loaded from `assets/skills/<skill_name>/index.html`.
 * Each JS skill exposes `async function ai_edge_gallery_get_result(args)` which is
 * called by [JsSkillRunner] via a hidden WebView.
 *
 * Built-in JS skills bundled with the app:
 *   - query-wikipedia  Search Wikipedia and return a plain-text summary
 *   - get-weather      Fetch weather for a named city via open-meteo (no GPS needed)
 *
 * Phase 3 (future): user-loadable skills from URLs — prerequisite for #177.
 */
@Singleton
class RunJsSkill @Inject constructor(
    private val runner: JsSkillRunner,
) : Skill {

    override val name = "run_js"
    override val description =
        "Run a built-in JavaScript skill by name. Use for web queries like Wikipedia " +
            "lookups or city weather when GPS is not needed."

    override val schema = SkillSchema(
        parameters = mapOf(
            "skill_name" to SkillParameter(
                type = "string",
                description = "The JS skill to run.",
                enum = listOf("query-wikipedia", "get-weather"),
            ),
            "query" to SkillParameter(
                type = "string",
                description = "The search query or input for the skill.",
            ),
        ),
        required = listOf("skill_name", "query"),
    )

    private val strToken = "<|" + "\"" + "|>"

    override val examples: List<String> = listOf(
        "Wikipedia: <|tool_call>call:run_js{skill_name:${strToken}query-wikipedia${strToken},query:${strToken}New Zealand${strToken}}<tool_call|>",
        "Weather:   <|tool_call>call:run_js{skill_name:${strToken}get-weather${strToken},query:${strToken}Auckland${strToken}}<tool_call|>",
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        val skillName = call.arguments["skill_name"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure(name, "Missing required parameter: skill_name.")
        val args = call.arguments - "skill_name"

        Log.d(TAG, "RunJsSkill: executing skill=$skillName args=$args")

        return try {
            val result = runner.execute(skillName, args)
            SkillResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "RunJsSkill: skill=$skillName failed", e)
            SkillResult.Failure(name, "JS skill '$skillName' failed: ${e.message}")
        }
    }
}
