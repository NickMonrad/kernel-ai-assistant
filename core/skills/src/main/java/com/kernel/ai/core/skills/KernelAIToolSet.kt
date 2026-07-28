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
 * 2. For simple tools, model calls the target tool directly
 * 3. For complex/gateway skills, model may call `loadSkill` first to get detailed instructions
 * 4. SDK feeds result back → model generates final text response
 *
 * ## Lazy injection
 * [SkillRegistry] is injected lazily to break the circular dependency:
 * SkillRegistry → Set<Skill> (includes LoadSkillSkill) → SkillRegistry.
 *
 * ## ⚠️ System prompt constraint
 * Direct tool names are fine in high-level behavioural rules, but avoid embedding raw
 * call syntax or low-level gateway recipes in the system prompt. Behavioural rules are
 * safe; detailed invocation recipes belong in `@Tool` descriptions or loadSkill payloads.
 */
@Singleton
class KernelAIToolSet @Inject constructor(
    private val skillRegistry: Lazy<SkillRegistry>,
) : ToolSet {
    enum class ToolExecutionOutcome {
        NOT_CALLED,
        SUCCEEDED,
        FAILED,
    }

    private fun ToolExecutionOutcome.isSuccess(): Boolean =
        this == ToolExecutionOutcome.SUCCEEDED

    private fun ToolExecutionOutcome.isFailure(): Boolean =
        this == ToolExecutionOutcome.FAILED

    // -------------------------------------------------------------------------
    // Explicit per-attempt execution outcomes
    // -------------------------------------------------------------------------
    @Volatile private var attemptLoadSkillOutcome = ToolExecutionOutcome.NOT_CALLED
    @Volatile private var attemptTerminalToolOutcome = ToolExecutionOutcome.NOT_CALLED

    companion object {
        /** The single non-terminal internal-only tool name. */
        private const val LOAD_SKILL_NAME = "load_skill"
    }

    // -------------------------------------------------------------------------
    // Existing per-turn call metadata (preserved across retries)
    // -------------------------------------------------------------------------
    @Volatile private var toolCalledInThisTurn = false
    @Volatile private var lastToolName: String? = null
    @Volatile private var lastToolRequest: String? = null
    @Volatile private var lastToolResult: String? = null
    @Volatile private var lastToolPresentation: ToolPresentation? = null
    @Volatile private var lastToolSpokenSummary: String? = null
    @Volatile private var lastToolWasDirectReply: Boolean = false

    // -------------------------------------------------------------------------
    // Per-attempt tracking (reset before each generation attempt)
    // -------------------------------------------------------------------------
    private val attemptToolNames = mutableListOf<String>()
    private var attemptLoadSkillCalled = false
    private var attemptTerminalToolCalled = false
    private val turnToolNames = mutableListOf<String>()
    @Volatile private var terminalToolName: String? = null
    @Volatile private var terminalToolRequest: String? = null
    @Volatile private var terminalToolResult: String? = null
    @Volatile private var terminalToolPresentation: ToolPresentation? = null
    @Volatile private var terminalToolSpokenSummary: String? = null
    @Volatile private var terminalToolWasDirectReply: Boolean = false
    @Volatile private var terminalToolOutcome = ToolExecutionOutcome.NOT_CALLED
    fun resetTurnState() {
        toolCalledInThisTurn = false
        lastToolName = null
        lastToolRequest = null
        lastToolResult = null
        lastToolPresentation = null
        lastToolSpokenSummary = null
        lastToolWasDirectReply = false
        turnToolNames.clear()
        terminalToolName = null
        terminalToolRequest = null
        terminalToolResult = null
        terminalToolPresentation = null
        terminalToolSpokenSummary = null
        terminalToolWasDirectReply = false
        terminalToolOutcome = ToolExecutionOutcome.NOT_CALLED
        resetAttemptState()
    }

    /** Clears only the current generation-attempt state.
     * Preserves turn sequence and the latest terminal executable record. */
    fun resetAttemptState() {
        attemptToolNames.clear()
        attemptLoadSkillCalled = false
        attemptTerminalToolCalled = false
        attemptLoadSkillOutcome = ToolExecutionOutcome.NOT_CALLED
        attemptTerminalToolOutcome = ToolExecutionOutcome.NOT_CALLED
        lastToolName = null
        lastToolRequest = null
        lastToolResult = null
        lastToolPresentation = null
        lastToolSpokenSummary = null
        lastToolWasDirectReply = false
        // Preserve terminalToolName, terminalToolRequest, terminalToolResult,
        // terminalToolPresentation, terminalToolSpokenSummary, terminalToolWasDirectReply,
        // terminalToolOutcome — these represent the turn's latest executable tool.
        // Cleared only in resetTurnState().
    }

    fun wasToolCalled(): Boolean = toolCalledInThisTurn
    fun lastToolName(): String? = lastToolName
    fun lastToolRequest(): String? = lastToolRequest
    fun lastToolResult(): String? = lastToolResult
    fun lastToolPresentation(): ToolPresentation? = lastToolPresentation
    fun lastToolSpokenSummary(): String? = lastToolSpokenSummary
    fun lastToolWasDirectReply(): Boolean = lastToolWasDirectReply

    /** True when load_skill was called in the current generation attempt. */
    fun loadSkillCalledInCurrentAttempt(): Boolean = attemptLoadSkillCalled

    /** True when load_skill completed successfully in the current attempt. */
    fun loadSkillSucceededInCurrentAttempt(): Boolean = attemptLoadSkillOutcome.isSuccess()

    /** True when load_skill completed with a failure in the current attempt. */
    fun loadSkillFailedInCurrentAttempt(): Boolean = attemptLoadSkillOutcome.isFailure()

    /** True when a terminal executable tool was called in the current attempt. */
    fun terminalToolCalledInCurrentAttempt(): Boolean = attemptTerminalToolCalled

    /** True when the terminal executable completed successfully. */
    fun terminalToolSucceededInCurrentAttempt(): Boolean =
        attemptTerminalToolOutcome.isSuccess()

    /** True when the terminal executable completed with a failure. */
    fun terminalToolFailedInCurrentAttempt(): Boolean =
        attemptTerminalToolOutcome.isFailure()

    /** The last terminal (executable) tool name, or null. Never returns "load_skill". */
    fun terminalToolName(): String? = terminalToolName

    /** The last terminal tool request, or null. */
    fun terminalToolRequest(): String? = terminalToolRequest

    /** The last terminal tool result, or null. */
    fun terminalToolResult(): String? = terminalToolResult

    /** The last terminal tool presentation, or null. */
    fun terminalToolPresentation(): ToolPresentation? = terminalToolPresentation

    /** The last terminal tool spoken summary, or null. */
    fun terminalToolSpokenSummary(): String? = terminalToolSpokenSummary

    /** True when the last terminal tool was a DirectReply. */
    fun terminalToolWasDirectReply(): Boolean = terminalToolWasDirectReply

    /** Whether the turn's terminal executable tool succeeded. Turn-level (survives retry). */
    fun terminalToolSucceeded(): Boolean = terminalToolOutcome.isSuccess()

    /** Whether the turn's terminal executable tool failed. Turn-level (survives retry). */
    fun terminalToolFailed(): Boolean = terminalToolOutcome.isFailure()

    fun attemptToolSequence(): String = buildString {
        if (attemptToolNames.isEmpty()) { append("none"); return@buildString }
        attemptToolNames.joinTo(this, ">")
    }

    fun turnToolSequence(): String = buildString {
        if (turnToolNames.isEmpty()) { append("none"); return@buildString }
        turnToolNames.joinTo(this, ">")
    }

    /** The terminal tool name for diagnostics, or "none". */
    fun terminalToolNameOrDefault(): String = terminalToolName ?: "none"

    // -------------------------------------------------------------------------
    // Internal tool-call tracking helpers
    // -------------------------------------------------------------------------

    /** Records a tool call for per-attempt, per-turn, and terminal tracking. */
    private fun recordToolCall(name: String, request: String) {
        toolCalledInThisTurn = true
        setLastToolCall(name, request)
        attemptToolNames.add(name)
        turnToolNames.add(name)
        if (name == LOAD_SKILL_NAME) {
            attemptLoadSkillCalled = true
        } else {
            attemptTerminalToolCalled = true
        }
    }

    /** Records the actual SkillResult outcome, independent of its rendered text. */
    private fun recordToolOutcome(name: String, succeeded: Boolean) {
        val outcome = if (succeeded) {
            ToolExecutionOutcome.SUCCEEDED
        } else {
            ToolExecutionOutcome.FAILED
        }
        if (name == LOAD_SKILL_NAME) {
            attemptLoadSkillOutcome = outcome
        } else {
            attemptTerminalToolOutcome = outcome
        }
    }

    /** After executeSkill returns, copies result metadata into terminal fields.
     * Only copies for non-load_skill tools. Also preserves the attempt-level outcome
     * so the turn-level outcome survives resetAttemptState(). */
    private fun captureTerminalResult(name: String) {
        if (name != LOAD_SKILL_NAME) {
            terminalToolName = lastToolName
            terminalToolRequest = lastToolRequest
            terminalToolResult = lastToolResult
            terminalToolPresentation = lastToolPresentation
            terminalToolSpokenSummary = lastToolSpokenSummary
            terminalToolWasDirectReply = lastToolWasDirectReply
            terminalToolOutcome = attemptTerminalToolOutcome
        }
    }

    /** Logs the per-attempt + per-turn + terminal diagnostic summary. */
    fun logToolSequence() {
        Log.d(TAG, buildString {
            append("llm_tools_tool_sequence:")
            append(" attempt="); append(attemptToolSequence())
            append(" turn="); append(turnToolSequence())
            append(" terminal="); append(terminalToolNameOrDefault())
        })
    }

    private fun setLastToolCall(name: String, request: String) {
        lastToolName = name
        lastToolRequest = request
        lastToolResult = null
        lastToolPresentation = null
        lastToolSpokenSummary = null
        lastToolWasDirectReply = false
        Log.d(TAG, "event_seq: tool_call name=$name args=${request.take(256)}")
    }

    // -------------------------------------------------------------------------
    // Gateway tools — each delegates to the matching Skill.execute()
    // -------------------------------------------------------------------------

    @Tool(description = "Loads full instructions for a complex gateway skill (meal_planner, run_js, run_intent). Call only when the required parameters or intent names for that skill are unclear.")
    fun loadSkill(
        @ToolParam(description = "The skill name to load.") skillName: String,
    ): Map<String, String> {
        recordToolCall(LOAD_SKILL_NAME, """{"skill_name":"$skillName"}""")
        Log.d(TAG, "ToolSet: loadSkill($skillName)")
        val result = executeSkill(LOAD_SKILL_NAME, mapOf("skill_name" to skillName))
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Execute native Android device actions like alarms, calendar, media, navigation, contacts, and system toggles. NOT for weather, web search, or memory — use other tools for those. Call run_intent directly when the required intent name and parameters are clear. Call load_skill(\"run_intent\") only when the supported intent name or required parameters are unclear.")
    fun runIntent(
        @ToolParam(description = "The intent action name. Call run_intent directly when the intent is known (e.g. 'set_alarm', 'create_calendar_event', 'send_sms'). Only call load_skill first when unsure which intent or parameters to use.") intentName: String,
        @ToolParam(description = "Additional parameters as key:value pairs in JSON. Provide parameters directly when known; call load_skill only when required parameters are unclear.") parameters: String,
    ): Map<String, String> {
        recordToolCall("run_intent", """{"intent_name":"$intentName","parameters":${if (parameters.isBlank()) "{}" else parameters}}""")
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
            recordToolOutcome("run_intent", succeeded = false)
            captureTerminalResult("run_intent")
            return mapOf("status" to "error", "error" to error)
        }

        val args = mutableMapOf("intent_name" to intentName)
        try {
            val json = org.json.JSONObject(parameters.ifBlank { "{}" })
            json.keys().forEach { key -> args[key] = json.optString(key) }
        } catch (e: Exception) {
            if (parameters.isNotBlank()) {
                val error = "Invalid parameters: expected a JSON object. Got: ${parameters.take(120)}"
                Log.w(TAG, "ToolSet: runIntent params not valid JSON — failing closed: ${e.message}")
                lastToolResult = error
                recordToolOutcome("run_intent", succeeded = false)
                captureTerminalResult("run_intent")
                return mapOf("status" to "error", "error" to error)
            }
            Log.w(TAG, "ToolSet: runIntent blank params parse, using empty: ${e.message}")
        }

        val result = executeSkill("run_intent", args)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("run_intent")
        return result
    }

    @Tool(description = "Run a JS-backed skill. Call loadSkill first to learn which skills are available and what parameters each needs.")
    fun runJs(
        @ToolParam(description = "A JSON object with skill_name (the JS skill to run) and data (a JSON object with the skill's parameters). Call loadSkill to learn the exact format.") parameters: String,
    ): Map<String, String> {
        recordToolCall("run_js", parameters)
        Log.d(TAG, "ToolSet: runJs(params=$parameters)")

        val args = mutableMapOf<String, String>()
        try {
            val json = org.json.JSONObject(parameters.ifBlank { "{}" })
            val skillName = json.optString("skill_name", "")
            val dataJson = json.opt("data")
            if (dataJson is org.json.JSONObject) {
                dataJson.keys().forEach { key -> args[key] = dataJson.optString(key) }
            }
            args["skill_name"] = skillName
        } catch (e: Exception) {
            Log.w(TAG, "ToolSet: runJs params parse failed, treating as empty: ${e.message}")
        }

        val result = executeSkill("run_js", args)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("run_js")
        return result
    }

    @Tool(description = "Convert an amount between currencies using latest ECB-backed exchange rates. Use for any currency conversion like 'how much is 100 AUD in INR' or 'convert 50 USD to NZD'. NOT for unit conversion (use runIntent with convert_units for that).")
    fun convertCurrency(
        @ToolParam(description = "The amount to convert, as a number (e.g. '100', '50.75')") amount: String,
        @ToolParam(description = "Source currency code or full name (e.g. 'AUD', 'USD', 'Australian dollars')") fromCurrency: String,
        @ToolParam(description = "Target currency code or full name (e.g. 'INR', 'NZD', 'Indian rupees')") toCurrency: String,
    ): Map<String, String> {
        recordToolCall("convert_currency", """{"amount":"$amount","from_currency":"$fromCurrency","to_currency":"$toCurrency"}""")
        Log.d(TAG, "ToolSet: convertCurrency(amount=$amount, from=$fromCurrency, to=$toCurrency)")

        val args = mapOf(
            "amount" to amount,
            "from_currency" to fromCurrency,
            "to_currency" to toCurrency,
        )

        val result = executeSkill("convert_currency", args)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("convert_currency")
        return result
    }

    @Tool(description = "Get current weather or a multi-day forecast (pass forecastDays=1-7 for forecast). ONLY for weather queries. NOT for date, time, or general knowledge.")
    fun getWeather(
        @ToolParam(description = "Optional location/city name. Leave blank for device GPS location.") location: String,
        @ToolParam(description = "Number of forecast days (1-7). Omit or pass 0 for current conditions only.") forecastDays: String,
    ): Map<String, String> {
        recordToolCall("get_weather", """{"location":"$location","forecast_days":"$forecastDays"}""")
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
        captureTerminalResult("get_weather")
        return result
    }

    @Tool(description = "Look up a named person, place, organisation, event, or other encyclopedia topic on Wikipedia. Use ONLY for explicit Wikipedia searches or fact lookups about a specific named entity. NOT for unit, measurement, or cooking conversions (cups, grams, ml, tbsp), arithmetic, weather, date/time, definitions, or how-to questions — answer those directly without a tool.")
    fun queryWikipedia(
        @ToolParam(description = "The topic, entity, or article title to look up on Wikipedia.") query: String,
    ): Map<String, String> {
        recordToolCall("query_wikipedia", """{"query":"${query.replace("\"", "\\\"").take(200)}"}""")
        Log.d(TAG, "ToolSet: queryWikipedia(${query.take(60)})")
        val result = executeSkill("query_wikipedia", mapOf("query" to query))
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("query_wikipedia")
        return result
    }

    @Tool(description = "Get current date/time and device runtime info including hardware tier, available memory, battery level, and device details. ALWAYS use this for current date, time, or day queries.")
    fun getSystemInfo(): Map<String, String> {
        recordToolCall("get_system_info", "{}")
        Log.d(TAG, "ToolSet: getSystemInfo()")
        val result = executeSkill("get_system_info", emptyMap())
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("get_system_info")
        return result
    }

    @Tool(description = "Save an important fact or preference to long-term memory. NOT for list items, calendar events, or alarms — use runIntent for those.")
    fun saveMemory(
        @ToolParam(description = "The exact fact or preference to save, verbatim as the user stated it.") content: String,
    ): Map<String, String> {
        recordToolCall("save_memory", """{"content":"${content.replace("\"", "\\\"").take(200)}"}""")
        Log.d(TAG, "ToolSet: saveMemory(${content.take(60)})")
        val result = executeSkill("save_memory", mapOf("content" to content))
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("save_memory")
        return result
    }

    @Tool(description = "Search saved memories and past conversations for information. NOT for web search, Wikipedia, or weather.")
    fun searchMemory(
        @ToolParam(description = "What to search for in saved memories and past messages.") query: String,
    ): Map<String, String> {
        recordToolCall("search_memory", """{"query":"$query"}""")
        Log.d(TAG, "ToolSet: searchMemory($query)")
        val result = executeSkill("search_memory", mapOf("query" to query))
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("search_memory")
        return result
    }

    // -------------------------------------------------------------------------
    // Internal dispatch
    private fun executeSkill(skillName: String, args: Map<String, String>): Map<String, String> {
        val skill = skillRegistry.get().get(skillName)
            ?: run {
                recordToolOutcome(skillName, succeeded = false)
                return mapOf("error" to "Unknown skill: $skillName")
            }

        return try {
            val result = runBlocking {
                skill.execute(SkillCall(skillName = skillName, arguments = args))
            }
            recordToolOutcome(
                skillName,
                succeeded = result is SkillResult.Success || result is SkillResult.DirectReply,
            )
            lastToolWasDirectReply = result is SkillResult.DirectReply
            lastToolPresentation = when (result) {
                is SkillResult.Success -> result.presentation
                is SkillResult.DirectReply -> result.presentation
                else -> null
            }
            lastToolSpokenSummary = when (result) {
                is SkillResult.Success -> result.spokenSummary
                is SkillResult.DirectReply -> result.spokenSummary
                else -> null
            }
            val returnedToGemma = result !is SkillResult.DirectReply
            val resultContent = when (result) {
                is SkillResult.Success -> result.content
                is SkillResult.DirectReply -> result.content
                is SkillResult.Failure -> result.error
                else -> result::class.simpleName ?: "Unknown"
            }
            Log.d(TAG, "event_seq: tool_result name=$skillName " +
                "resultType=${result::class.simpleName} " +
                "directReply=$lastToolWasDirectReply " +
                "returnedToGemma=$returnedToGemma " +
                "content=\"${resultContent.take(256).replace("\n","\\n").replace("\"","\\\"")}\"")
            when (result) {
                is SkillResult.Success -> mapOf("result" to result.content)
                is SkillResult.DirectReply -> mapOf("result" to result.content)
                is SkillResult.Failure -> mapOf("error" to result.error)
                is SkillResult.ParseError -> mapOf("error" to "Parse error: ${result.reason}")
                is SkillResult.UnknownSkill -> mapOf("error" to "Unknown skill: ${result.skillName}")
                is SkillResult.CapabilityRequired -> mapOf(
                    "capability_required" to result.capabilityKey.name,
                    "skill" to result.skillName,
                    "contextParams" to result.contextParams.entries.joinToString(",") { "${it.key}=${it.value}" },
                )
            }
        } catch (e: Exception) {
            recordToolOutcome(skillName, succeeded = false)
            lastToolPresentation = null
            lastToolSpokenSummary = null
            Log.e(TAG, "ToolSet: $skillName execution failed", e)
            mapOf("error" to (e.message ?: "Unknown error executing $skillName"))
        }
    }
}
