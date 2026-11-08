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
 *   - get-weather-city Fetch current weather or multi-day forecast for a named city
 *                      via Open-Meteo (no GPS needed).
 *
 * Phase 3 (future): user-loadable skills from URLs — prerequisite for #177.
 */
@Singleton
class RunJsSkill @Inject constructor(
    private val runner: JsSkillRunner,
) : Skill {

    override val name = "run_js"
    override val description =
        "Internal JavaScript execution gateway. Prefer loading a specific JS-backed skill " +
            "such as query_wikipedia instead of loading run_js directly."

    override val schema = SkillSchema()

    override val fullInstructions: String = buildString {
        appendLine("run_js: Gateway for executing JS-backed skills.")
        appendLine()
        appendLine("Instructions:")
        appendLine("- This is a low-level execution gateway for JS-backed skills.")
        appendLine("- Prefer loading a specific skill such as query_wikipedia or get_weather before calling this tool.")
        appendLine("- If you do call run_js directly, use the format below.")
        appendLine()
        appendLine("Tool format:")
        appendLine("- Call runJs with a single 'parameters' argument: a JSON string containing")
        appendLine("  'skill_name' (the JS skill to run) and 'data' (a JSON object with the skill's parameters).")
        appendLine()
        appendLine("Examples:")
        appendLine("  Wikipedia search → runJs(parameters='{\"skill_name\":\"query-wikipedia\",\"data\":{\"query\":\"New Zealand\"}}')")
        appendLine("  Weather current → runJs(parameters='{\"skill_name\":\"get-weather-city\",\"data\":{\"query\":\"Auckland\"}}')")
        appendLine("  Weather 3-day forecast → runJs(parameters='{\"skill_name\":\"get-weather-city\",\"data\":{\"query\":\"Auckland\",\"forecast_days\":\"3\"}}')")
    }

    // Success: JS skill output requires LLM synthesis (e.g. Wikipedia summaries)
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
