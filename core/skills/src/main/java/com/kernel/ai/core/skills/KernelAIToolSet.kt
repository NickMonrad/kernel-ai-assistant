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

    @Tool(description = "Execute a native Android device action: flashlight, alarms, timers, calendar events, email, SMS, phone calls, Do Not Disturb, volume, Wi-Fi, Bluetooth, airplane mode, hotspot, media playback (local/YouTube/Spotify/Netflix/Plex), navigation, app launching, battery status, current time, current date, date arithmetic, and list management (shopping lists, grocery lists, to-do lists). NOT for weather, web search, memory recall, or general knowledge questions.")
    fun runIntent(
        @ToolParam(description = "The intent action: toggle_flashlight_on, toggle_flashlight_off, send_email, send_sms, make_call, set_alarm, set_timer, create_calendar_event, toggle_dnd_on, toggle_dnd_off, toggle_wifi, toggle_bluetooth, toggle_airplane_mode, toggle_hotspot, set_volume, play_media, play_media_album, play_media_playlist, play_youtube, play_spotify, play_plexamp, play_youtube_music, play_netflix, play_plex, navigate_to, find_nearby, open_app, get_battery, get_time, get_date, get_date_diff, add_to_list (ONE item only), bulk_add_to_list (TWO OR MORE items — always use this when adding multiple items at once), create_list, get_list_items, remove_from_list") intentName: String,
        @ToolParam(description = "Additional parameters as key:value pairs in JSON. For set_alarm: {\"time\":\"10pm\"} or {\"time\":\"7:30am\",\"day\":\"monday\",\"label\":\"Wake up\"}. For set_timer: {\"duration_seconds\":\"180\"}. For send_email: {\"subject\":\"Hi\",\"body\":\"Text\"}. For send_sms: {\"contact\":\"Mom\",\"message\":\"Text\"}. For make_call: {\"contact\":\"Dad\"}. For create_calendar_event: {\"title\":\"Meeting\",\"date\":\"2026-04-15\",\"time\":\"12:30\"}. For set_volume: {\"value\":\"50\",\"is_percent\":\"true\"}. For play_media: {\"query\":\"Song Name\",\"artist\":\"Artist\"}. For play_plex: {\"title\":\"Movie Name\"}. For navigate_to: {\"destination\":\"airport\"}. For toggle_wifi/bluetooth/airplane_mode/hotspot: {\"state\":\"on\"}. For open_app: {\"app_name\":\"Spotify\"}. For toggle_dnd/flashlight/get_battery/get_time/get_date: {}. For get_date_diff: {\"target_date\":\"2026-08-22\"} or {\"target_date\":\"Christmas\"} — ALWAYS use this for date arithmetic, never calculate days yourself. For add_to_list (single item): {\"item\":\"milk\",\"list_name\":\"shopping list\"}. For bulk_add_to_list (multiple items — use whenever 2+ items are mentioned): {\"items\":\"milk,eggs,bread\",\"list_name\":\"shopping list\"}. For create_list: {\"list_name\":\"meal plan\"}. For get_list_items: {\"list_name\":\"shopping list\"}. For remove_from_list: {\"item\":\"milk\",\"list_name\":\"shopping list\"}") parameters: String,
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

    @Tool(description = "Run a built-in JavaScript skill. Use 'query-wikipedia' for Wikipedia or encyclopedia lookups. DO NOT use for weather — use the getWeather tool instead. NOTE: to use this tool, first call loadSkill('run_js') — do NOT call loadSkill('query-wikipedia').")
    fun runJs(
        @ToolParam(description = "The JS skill to run: 'get-weather-city' or 'query-wikipedia'. IMPORTANT: load this tool with loadSkill('run_js'), not loadSkill('query-wikipedia').") skillName: String,
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

    @Tool(description = "Get current weather conditions or a multi-day weather forecast for a location. ONLY for weather, temperature, precipitation, wind, or climate queries. NOT for date, time, day-of-week, calendar, or general knowledge questions.")
    fun getWeather(
        @ToolParam(description = "Optional location/city name. ONLY provide if the user explicitly names a place (e.g. 'in Brisbane') or says 'at home'. Leave blank for all other weather queries — device GPS will be used automatically and is more accurate than profile location.") location: String,
        @ToolParam(description = "Optional number of forecast days (1-7). Omit for current conditions only") forecastDays: String,
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

    @Tool(description = "Save an important fact or preference to the user's long-term memory. Use when the user says 'remember', 'note that', 'don't forget', or 'keep that in mind'. NOT for adding items to a shopping list, grocery list, to-do list, or any named list — use runIntent with bulk_add_to_list for that. NOT for calendar events, alarms, reminders, or timers — use runIntent for those.")
    fun saveMemory(
        @ToolParam(description = "The exact fact or preference to save, verbatim as the user stated it — NOT a meta-summary or description of what they said. Example: 'Nick prefers dark mode' or 'Nick\\'s dog is called Biscuit'. Never write 'The user wants to remember X'.") content: String,
    ): Map<String, String> {
        toolCalledInThisTurn = true
        lastToolName = "save_memory"
        Log.d(TAG, "ToolSet: saveMemory(${content.take(60)})")
        val result = executeSkill("save_memory", mapOf("content" to content))
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Search saved memories and past conversation history for information about a topic. Use when the user asks what you remember, wants to recall a fact, or asks about past conversations. NOT for web search, Wikipedia, weather, or any real-time information.")
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
