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
        "Run a built-in JavaScript skill by name. Use skill_name='get-weather-city' for weather " +
            "with a known city name or forecast by city. " +
            "For current GPS location weather or GPS-based forecast, use get_weather_gps instead. " +
            "For forecast, pass forecast_days (1–7). " +
            "ALWAYS call this tool for weather — never use weather data from memory, it is stale."

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

    private val strToken = "<|" + "\"" + "|>"

    override val examples: List<String> = listOf(
        "Wikipedia: <|tool_call>call:run_js{skill_name:${strToken}query-wikipedia${strToken},query:${strToken}New Zealand${strToken}}<tool_call|>",
        "Weather (current, named city): <|tool_call>call:run_js{skill_name:${strToken}get-weather-city${strToken},query:${strToken}Auckland${strToken}}<tool_call|>",
        "Weather (forecast 3 days, named city): <|tool_call>call:run_js{skill_name:${strToken}get-weather-city${strToken},query:${strToken}Auckland${strToken},forecast_days:${strToken}3${strToken}}<tool_call|>",
        "Weather (tomorrow, named city): <|tool_call>call:run_js{skill_name:${strToken}get-weather-city${strToken},query:${strToken}London${strToken},forecast_days:${strToken}1${strToken}}<tool_call|>",
        "Weather (forecast no days specified — default 3): <|tool_call>call:run_js{skill_name:${strToken}get-weather-city${strToken},query:${strToken}Sydney${strToken},forecast_days:${strToken}3${strToken}}<tool_call|>",
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
