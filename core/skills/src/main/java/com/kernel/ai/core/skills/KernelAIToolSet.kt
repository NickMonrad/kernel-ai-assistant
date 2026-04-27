package com.kernel.ai.core.skills

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Native LiteRT-LM tool set exposing 5 gateway functions to the SDK.
 *
 * Mirrors Google AI Edge Gallery's `AgentTools` pattern: the SDK auto-discovers
 * `@Tool`-annotated methods, generates tool declarations for the model, handles
 * constrained decoding to guarantee well-formed calls, and feeds return values
 * back to the model as tool responses.
 *
 * Each method delegates to an existing [Skill.execute] implementation so all
 * business logic remains in the individual skill classes.
 *
 * ## Tool pipeline
 * 1. Model sees tool names + descriptions (SDK-generated from annotations)
 * 2. Model calls `loadSkill` → gets full instructions for a specific skill
 * 3. Model calls the target tool with correct parameters
 * 4. SDK feeds result back → model generates final text response
 *
 * ## Lazy injection
 * [SkillRegistry] is injected lazily to break the circular dependency:
 * SkillRegistry → Set<Skill> (includes LoadSkillSkill) → SkillRegistry.
 *
 * ## ⚠️ System prompt constraint
 * Because the model only learns skill parameters via `loadSkill`, the system prompt
 * ([DEFAULT_SYSTEM_PROMPT] in ModelConfig) must never contain raw tool call syntax
 * (e.g. `runJs(skillName="query-wikipedia")`). That would cause the model to skip
 * step 2 entirely. Behavioural rules are fine; skill invocation recipes are not.
 */
@Singleton
class KernelAIToolSet @Inject constructor(
    private val skillRegistry: Lazy<SkillRegistry>,
) : ToolSet {

    @Volatile private var toolCalledInThisTurn = false
    @Volatile private var lastToolName: String? = null
    @Volatile private var lastToolResult: String? = null
    @Volatile private var lastToolPresentation: ToolPresentation? = null

    fun resetTurnState() {
        toolCalledInThisTurn = false
        lastToolName = null
        lastToolResult = null
        lastToolPresentation = null
    }

    fun wasToolCalled(): Boolean = toolCalledInThisTurn
    fun lastToolName(): String? = lastToolName
    fun lastToolResult(): String? = lastToolResult
    fun lastToolPresentation(): ToolPresentation? = lastToolPresentation

    // -------------------------------------------------------------------------
    // Gateway tools — each delegates to the matching Skill.execute()
    // -------------------------------------------------------------------------

    @Tool(description = "Loads a skill's full instructions before calling it. Call this first for any new task before using other tools.")
    fun loadSkill(
        @ToolParam(description = "The skill name to load.") skillName: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "load_skill"
        Log.d(TAG, "ToolSet: loadSkill($skillName)")
        val result = executeSkill("load_skill", mapOf("skill_name" to skillName))
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Execute native Android device actions like alarms, calendar, media, navigation, contacts, and system toggles. NOT for weather, web search, or memory — use other tools for those. Call loadSkill first before using this to learn available intents.")
    fun runIntent(
        @ToolParam(description = "The intent action name. Call loadSkill first to learn available intents for the skill you need.") intentName: String,
        @ToolParam(description = "Additional parameters as key:value pairs in JSON. Call loadSkill first to learn required parameters.") parameters: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "run_intent"
        Log.d(TAG, "ToolSet: runIntent($intentName, $parameters)")

        val reservedSkillNames = setOf(
            "load_skill",
            "run_intent",
            "run_js",
            "get_weather",
            "get_weather_gps",
            "query_wikipedia",
            "meal_planner",
            "save_memory",
            "search_memory",
            "get_system_info",
        )
        if (intentName in reservedSkillNames) {
            val error = "Invalid run_intent call: '$intentName' is a skill name, not an intent. Use load_skill first for skills like meal_planner or query_wikipedia."
            lastToolResult = error
            return mapOf("status" to "error", "error" to error)
        }

        val args = mutableMapOf("intent_name" to intentName)
        try {
            val json = org.json.JSONObject(parameters.ifBlank { "{}" })
            json.keys().forEach { key -> args[key] = json.optString(key) }
        } catch (e: Exception) {
            Log.w(TAG, "ToolSet: runIntent params parse failed, treating as empty: ${e.message}")
        }

        val result = executeSkill("run_intent", args)
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Execute JS-backed skills. First call loadSkill for the specific skill you want. DO NOT use when a dedicated skill exists.")
    fun runJs(
        @ToolParam(description = "The JS skill name. Call loadSkill first to learn available skills.") skillName: String,
        @ToolParam(description = "The search query or input for the skill.") query: String,
        @ToolParam(description = "Optional parameter — call loadSkill to learn what this skill needs.") forecastDays: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "run_js"
        Log.d(TAG, "ToolSet: runJs($skillName, $query, forecastDays=$forecastDays)")

        val args = mutableMapOf("skill_name" to skillName, "query" to query)
        if (forecastDays.isNotBlank() && forecastDays != "0") {
            args["forecast_days"] = forecastDays
        }

        val result = executeSkill("run_js", args)
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Get current weather or a multi-day forecast. ONLY for weather queries. NOT for date, time, or general knowledge.")
    fun getWeather(
        @ToolParam(description = "Optional location/city name. Leave blank for device GPS location.") location: String,
        @ToolParam(description = "Optional number of forecast days (1-7). Omit for current conditions only.") forecastDays: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "get_weather"
        Log.d(TAG, "ToolSet: getWeather(location=$location, forecastDays=$forecastDays)")

        val args = mutableMapOf<String, String>()
        if (location.isNotBlank()) {
            args["location"] = location
        }
        if (forecastDays.isNotBlank() && forecastDays != "0") {
            args["forecast_days"] = forecastDays
        }

        val result = executeSkill("get_weather_gps", args)
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Save an important fact or preference to long-term memory. NOT for list items, calendar events, or alarms — use runIntent for those.")
    fun saveMemory(
        @ToolParam(description = "The exact fact or preference to save, verbatim as the user stated it.") content: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "save_memory"
        Log.d(TAG, "ToolSet: saveMemory(${content.take(60)})")
        val result = executeSkill("save_memory", mapOf("content" to content))
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

   @Tool(description = "Search saved memories and past conversations for information. NOT for web search, Wikipedia, or weather.")
    fun searchMemory(
        @ToolParam(description = "What to search for in saved memories and past messages.") query: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "search_memory"
        Log.d(TAG, "ToolSet: searchMemory($query)")
        val result = executeSkill("search_memory", mapOf("query" to query))
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    // -------------------------------------------------------------------------
    // Internal dispatch
    // -------------------------------------------------------------------------

    /**
     * Dispatches to the named [Skill] via [SkillRegistry].
     *
     * LiteRT-LM @Tool methods are called synchronously by the SDK on its inference
     * thread, but our Skill.execute() methods are suspend functions (Room, network).
     * We bridge with [runBlocking] scoped to the single call — acceptable because the
     * SDK already blocks its inference loop waiting for the tool result.
     */
    private fun executeSkill(skillName: String, args: Map<String, String>): Map<String, String> {
        val skill = skillRegistry.get().get(skillName)
            ?: return mapOf("error" to "Unknown skill: $skillName")

        return try {
            val result = runBlocking {
                skill.execute(SkillCall(skillName = skillName, arguments = args))
            }
            lastToolPresentation = when (result) {
                is SkillResult.Success -> result.presentation
                is SkillResult.DirectReply -> result.presentation
                else -> null
            }
            when (result) {
                is SkillResult.Success -> mapOf("result" to result.content)
                is SkillResult.DirectReply -> mapOf("result" to result.content)
                is SkillResult.Failure -> mapOf("error" to result.error)
                is SkillResult.ParseError -> mapOf("error" to "Parse error: ${result.reason}")
                is SkillResult.UnknownSkill -> mapOf("error" to "Unknown skill: ${result.skillName}")
            }
        } catch (e: Exception) {
            lastToolPresentation = null
            Log.e(TAG, "ToolSet: $skillName execution failed", e)
            mapOf("error" to (e.message ?: "Unknown error executing $skillName"))
        }
    }
}
