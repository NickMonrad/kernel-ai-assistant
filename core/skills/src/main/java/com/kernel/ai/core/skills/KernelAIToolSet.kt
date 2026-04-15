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
 */
@Singleton
class KernelAIToolSet @Inject constructor(
    private val skillRegistry: Lazy<SkillRegistry>,
) : ToolSet {

    @Volatile private var toolCalledInThisTurn = false
    @Volatile private var lastToolName: String? = null
    @Volatile private var lastToolResult: String? = null

    fun resetTurnState() {
        toolCalledInThisTurn = false
        lastToolName = null
        lastToolResult = null
    }

    fun wasToolCalled(): Boolean = toolCalledInThisTurn
    fun lastToolName(): String? = lastToolName
    fun lastToolResult(): String? = lastToolResult

    // -------------------------------------------------------------------------
    // Gateway tools — each delegates to the matching Skill.execute()
    // -------------------------------------------------------------------------

    @Tool(description = "Load full instructions for a skill before calling it. MUST be called first before using any other tool.")
    fun loadSkill(
        @ToolParam(description = "The skill name to load: run_intent, run_js, save_memory, search_memory, or get_system_info") skillName: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "load_skill"
        Log.d(TAG, "ToolSet: loadSkill($skillName)")
        val result = executeSkill("load_skill", mapOf("skill_name" to skillName))
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Execute a native Android device action such as toggling the flashlight, setting an alarm or timer, sending email/SMS, or creating a calendar event")
    fun runIntent(
        @ToolParam(description = "The intent action: toggle_flashlight_on, toggle_flashlight_off, send_email, send_sms, set_alarm, set_timer, create_calendar_event") intentName: String,
        @ToolParam(description = "Additional parameters as key:value pairs in JSON. For set_alarm: {\"time\":\"10pm\"} or {\"time\":\"7:30am\",\"day\":\"monday\",\"label\":\"Wake up\"}. For set_timer: {\"duration_seconds\":\"180\"}. For send_email: {\"subject\":\"Hi\",\"body\":\"Text\"}. For create_calendar_event: {\"title\":\"Meeting\",\"date\":\"2026-04-15\",\"time\":\"12:30\"}") parameters: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "run_intent"
        Log.d(TAG, "ToolSet: runIntent($intentName, $parameters)")

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

    @Tool(description = "Run a built-in JavaScript skill. Use skill_name 'get-weather-city' for weather by city name, 'query-wikipedia' for Wikipedia search. For GPS weather use run_intent with get_weather_gps instead")
    fun runJs(
        @ToolParam(description = "The JS skill to run: 'get-weather-city' or 'query-wikipedia'") skillName: String,
        @ToolParam(description = "The search query or input (city name for weather, topic for Wikipedia)") query: String,
        @ToolParam(description = "For get-weather-city only: number of forecast days 1-7. Omit for current weather, use 3 when user asks for forecast without specifying days") forecastDays: String,
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

    @Tool(description = "Save an important fact or preference to the user's long-term memory. Use when the user says 'remember', 'save', 'note that', or 'don't forget'")
    fun saveMemory(
        @ToolParam(description = "The content to remember, written as a clear factual statement") content: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "save_memory"
        Log.d(TAG, "ToolSet: saveMemory(${content.take(60)})")
        val result = executeSkill("save_memory", mapOf("content" to content))
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Search saved memories and past conversation history for information about a topic. Use when the user asks what you remember, wants to recall a fact, or asks about past conversations")
    fun searchMemory(
        @ToolParam(description = "What to search for in saved memories and past messages") query: String,
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
            when (result) {
                is SkillResult.Success -> mapOf("result" to result.content)
                is SkillResult.Failure -> mapOf("error" to result.error)
                is SkillResult.ParseError -> mapOf("error" to "Parse error: ${result.reason}")
                is SkillResult.UnknownSkill -> mapOf("error" to "Unknown skill: ${result.skillName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ToolSet: $skillName execution failed", e)
            mapOf("error" to (e.message ?: "Unknown error executing $skillName"))
        }
    }
}
