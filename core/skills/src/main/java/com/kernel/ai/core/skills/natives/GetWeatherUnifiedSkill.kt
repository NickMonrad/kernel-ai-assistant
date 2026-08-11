package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified weather skill (#490).
 *
 * Single entry point for all weather queries — registered as `get_weather` and routed by
 * [com.kernel.ai.core.skills.QuickIntentRouter] for no-LLM DirectReply delivery.
 *
 * Delegates all actual fetching to [GetWeatherSkill] which handles both GPS-based and
 * location-name-based queries. This class exists as a stable `get_weather` intent alias so
 * QIR city-extraction patterns can forward a `location` param without needing to know which
 * underlying transport (GPS vs geocode) will be used.
 *
 * Parameter semantics (mirrors [GetWeatherSkill]):
 * - `location`     — optional city/place name; omit to use device GPS.
 * - `forecast_days` — optional 1–7 day forecast; omit for current conditions only.
 * - `day`          — optional "today" or "tomorrow"; when "tomorrow", returns tomorrow's forecast only.
 * - `period`       — optional "weekend"; returns the current/upcoming weekend's Saturday + Sunday
 *   forecast from the daily data path (distinct cache identity from current/tomorrow/N-day).
 *
 * Always returns [SkillResult.DirectReply] — the formatted weather string is shown verbatim
 * and never sent to the LLM for wrapping, preventing number/unit corruption.
 */
@Singleton
class GetWeatherUnifiedSkill @Inject constructor(
    private val weatherSkill: GetWeatherSkill,
) : Skill {

    override val name = "get_weather"
    override val description =
        "Get current weather or a multi-day forecast. Uses device GPS by default — only pass a " +
            "location if the user explicitly names a place or says 'at home'. " +
            "ALWAYS call this tool for any weather question — never use weather data from memory, it is stale."
    override val examples = listOf(
        "Current location weather → get_weather()",
        "GPS location 3-day forecast → get_weather(forecast_days=\"3\")",
        "Weather in Auckland → get_weather(location=\"Auckland\")",
        "Weekend forecast in Bundaberg → get_weather(location=\"Bundaberg\", period=\"weekend\")",
        "Weather in Brisbane → get_weather(location=\"Brisbane\")",
    )

    override val schema = SkillSchema(
        parameters = mapOf(
            "location" to SkillParameter(
                type = "string",
                description = "Optional location/city name. Only provide if the user explicitly " +
                    "names a place or says 'at home'. Leave blank to use device GPS.",
            ),
            "forecast_days" to SkillParameter(
                type = "integer",
                description = "Number of forecast days (1–7). Omit for current conditions only.",
            ),
            "day" to SkillParameter(
                type = "string",
                description = "Optional day to get weather for: \"today\" or \"tomorrow\". When \"tomorrow\", returns tomorrow's forecast only.",
            ),
            "period" to SkillParameter(
                type = "string",
                description = "Optional forecast period: \"weekend\" targets the current/upcoming weekend " +
                    "(Saturday + Sunday, or just the remaining Sunday when asked on a Sunday). " +
                    "Omit for the default current/today behaviour. Mutually exclusive with forecast_days.",
            ),
        ),
        required = emptyList(),
    )

    // DirectReply: delegates to GetWeatherSkill which returns DirectReply for all weather data
    override suspend fun execute(call: SkillCall): SkillResult =
        weatherSkill.execute(SkillCall(weatherSkill.name, call.arguments))
}
