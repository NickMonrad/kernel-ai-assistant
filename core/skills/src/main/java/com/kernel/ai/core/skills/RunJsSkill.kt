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

    override val examples: List<String> = listOf(
        "Wikipedia search → runJs(skillName=\"query-wikipedia\", query=\"New Zealand\", forecastDays=\"\")",
        "Weather current → runJs(skillName=\"get-weather-city\", query=\"Auckland\", forecastDays=\"\")",
        "Weather 3-day forecast → runJs(skillName=\"get-weather-city\", query=\"Auckland\", forecastDays=\"3\")",
        "Weather tomorrow → runJs(skillName=\"get-weather-city\", query=\"London\", forecastDays=\"1\")",
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
