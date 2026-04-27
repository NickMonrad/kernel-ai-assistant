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
 *                      Pass forecast_days (1–7) for a daily forecast instead of current.
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

    override val schema = SkillSchema(
        parameters = mapOf(
            "skill_name" to SkillParameter(
                type = "string",
                description = "The JS skill to run.",
                enum = listOf("query-wikipedia", "get-weather-city"),
            ),
            "query" to SkillParameter(
                type = "string",
                description = "The search query or input for the skill (city name for weather).",
            ),
            "forecast_days" to SkillParameter(
                type = "integer",
                description = "For get-weather-city only: number of forecast days (1–7). " +
                    "Omit for current weather. When the user asks for a forecast without " +
                    "specifying a number of days, use 3.",
            ),
        ),
        required = listOf("skill_name", "query"),
    )

    override val examples: List<String> = listOf(
        "Wikipedia search → runJs(skillName=\"query-wikipedia\", query=\"New Zealand\")",
        "Weather current → runJs(skillName=\"get-weather-city\", query=\"Auckland\", forecastDays=\"\")",
        "Weather 3-day forecast → runJs(skillName=\"get-weather-city\", query=\"Auckland\", forecastDays=\"3\")",
        "Weather tomorrow → runJs(skillName=\"get-weather-city\", query=\"London\", forecastDays=\"1\")",
    )

    override val fullInstructions: String = buildString {
        appendLine("$name: $description")
        appendLine()
        appendLine("Instructions:")
        appendLine("- This is a low-level execution gateway for JS-backed skills.")
        appendLine("- Prefer loading a specific skill such as query_wikipedia or get_weather before calling this tool.")
        appendLine("- If you do call run_js directly, set skill_name to the exact bundled JS skill name.")
        appendLine("- forecast_days applies only to get-weather-city. Never use it for Wikipedia lookups.")
        appendLine()
        appendLine("Parameters:")
        appendLine("- skill_name (required) (string [query-wikipedia, get-weather-city]): The bundled JS skill to run.")
        appendLine("- query (required) (string): The search query or input for the skill.")
        appendLine("- forecast_days (integer): For get-weather-city only: number of forecast days (1–7). Omit for current weather and all non-weather skills.")
        appendLine()
        appendLine("Examples:")
        examples.forEach { appendLine("  $it") }
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
