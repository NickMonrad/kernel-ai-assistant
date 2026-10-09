package com.kernel.ai.core.skills

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.Lazy
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

private const val TAG = "KernelAI"

data class LocalToolCallDiagnostic(
    val order: Int,
    val name: String,
    /** Exact values received by the typed @Tool entry point; nested JSON strings remain unmodified. */
    val arguments: Map<String, String>,
    val terminal: Boolean,
    val resultType: String? = null,
    val resultContent: String? = null,
    /** Exact map returned by the @Tool method to LiteRT-LM. */
    val toolResult: Map<String, String>? = null,
    val succeeded: Boolean? = null,
    val directReply: Boolean? = null,
    val returnedToGemma: Boolean? = null,
)

data class LocalGenerationAttemptDiagnostic(
    val order: Int,
    val fullContent: String,
    val rawThinking: String,
)

data class LocalToolDiagnosticSnapshot(
    val calls: List<LocalToolCallDiagnostic>,
    val terminalCall: LocalToolCallDiagnostic?,
    val generationAttempts: List<LocalGenerationAttemptDiagnostic>,
)

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
    private val safeModelTestToken = AtomicReference<Any?>(null)
    private val safeModelTestRunIntents = setOf("get_stopwatch_status")

    /**
     * Restricts one DEBUG model generation to loading run_intent instructions and invoking
     * the allowlisted read-only stopwatch-status action. Callers must close the returned scope.
     */
    fun beginSafeModelTestSandbox(): AutoCloseable {
        val token = Any()
        check(safeModelTestToken.compareAndSet(null, token)) {
            "A safe model-test sandbox is already active"
        }
        return AutoCloseable { safeModelTestToken.compareAndSet(token, null) }
    }

    private fun isSafeModelTestActive(): Boolean = safeModelTestToken.get() != null

    private fun isAllowedSafeModelTestRunIntent(intentName: String, parameters: String): Boolean {
        if (intentName !in safeModelTestRunIntents) return false
        if (parameters.isBlank()) return true
        return try {
            org.json.JSONObject(parameters).length() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isAllowedSafeModelTestDispatch(
        skillName: String,
        args: Map<String, String>,
    ): Boolean = when (skillName) {
        LOAD_SKILL_NAME -> args == mapOf("skill_name" to "run_intent")
        "run_intent" -> args.keys == setOf("intent_name") &&
            args.getValue("intent_name") in safeModelTestRunIntents
        else -> false
    }

    private fun denyInSafeModelTest(
        toolName: String,
        request: String,
        arguments: Map<String, String>?,
    ): Map<String, String> {
        val diagnosticOrder = recordToolCall(toolName, request, arguments)
        val error = "Blocked by safe model-test allowlist"
        val toolResult = mapOf("error" to error)
        lastToolResult = error
        lastToolPresentation = null
        lastToolSpokenSummary = null
        lastToolWasDirectReply = false
        recordToolOutcome(toolName, succeeded = false)
        recordLocalToolResult(
            diagnosticOrder,
            "Blocked",
            error,
            toolResult,
            succeeded = false,
            directReply = false,
            returnedToGemma = true,
        )
        if (toolName != LOAD_SKILL_NAME) captureTerminalResult(toolName)
        Log.d(
            TAG,
            "event_seq: tool_result name=$toolName resultType=Failure " +
                "directReply=false returnedToGemma=true content=\"$error\"",
        )
        return toolResult
    }

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
        // E4B sometimes omits "stop" when naming the stopwatch status action.
        private val RUN_INTENT_NAME_ALIASES =
            mapOf("getwatchstatus" to "get_stopwatch_status")

        internal fun resolveRunIntentName(
            intentName: String,
            callableIntents: List<String> = RunIntentSkill.MODEL_CALLABLE_INTENTS,
        ): String {
            if (intentName in callableIntents) return intentName
            val normalized = normalizeIntentName(intentName)
            val matches = callableIntents.filter { normalizeIntentName(it) == normalized }
            matches.singleOrNull()?.let { return it }
            if (matches.isNotEmpty()) return intentName
            return RUN_INTENT_NAME_ALIASES[normalized]
                ?.takeIf { it in callableIntents }
                ?: intentName
        }

        private fun normalizeIntentName(intentName: String): String =
            intentName.lowercase(Locale.ROOT).filterNot {
                it == '_' || it == '-' || it.isWhitespace()
            }
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

    // Full diagnostic data is retained only while the debug llm_tools harness explicitly arms capture.
    private val localDiagnosticLock = Any()
    private val localDiagnosticCalls = mutableListOf<LocalToolCallDiagnostic>()
    private val localDiagnosticGenerationAttempts = mutableListOf<LocalGenerationAttemptDiagnostic>()
    @Volatile private var localDiagnosticCaptureEnabled = false

    fun beginLocalDiagnosticCapture() {
        synchronized(localDiagnosticLock) {
            localDiagnosticCalls.clear()
            localDiagnosticGenerationAttempts.clear()
            localDiagnosticCaptureEnabled = true
        }
    }

    /** Retains exact per-attempt model output only while the debug harness has armed capture. */
    fun recordLocalGenerationAttempt(fullContent: CharSequence, rawThinking: CharSequence) {
        if (!localDiagnosticCaptureEnabled) return
        synchronized(localDiagnosticLock) {
            if (localDiagnosticCaptureEnabled) {
                localDiagnosticGenerationAttempts += LocalGenerationAttemptDiagnostic(
                    order = localDiagnosticGenerationAttempts.size,
                    fullContent = fullContent.toString(),
                    rawThinking = rawThinking.toString(),
                )
            }
        }
    }

    /** Stops capture and clears the tool set's copy of the sensitive diagnostic data. */
    fun finishLocalDiagnosticCapture(): LocalToolDiagnosticSnapshot {
        val (calls, generationAttempts) = synchronized(localDiagnosticLock) {
            localDiagnosticCaptureEnabled = false
            val calls = localDiagnosticCalls.toList()
            val generationAttempts = localDiagnosticGenerationAttempts.toList()
            localDiagnosticCalls.clear()
            localDiagnosticGenerationAttempts.clear()
            calls to generationAttempts
        }
        return LocalToolDiagnosticSnapshot(
            calls = calls,
            terminalCall = calls.lastOrNull { it.terminal },
            generationAttempts = generationAttempts,
        )
    }

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

    /** Records a tool call for per-attempt, per-turn, terminal, and opt-in local diagnostics. */
    private fun recordToolCall(
        name: String,
        request: String,
        arguments: Map<String, String>?,
    ): Int? {
        toolCalledInThisTurn = true
        setLastToolCall(name, request)
        attemptToolNames.add(name)
        turnToolNames.add(name)
        if (name == LOAD_SKILL_NAME) {
            attemptLoadSkillCalled = true
        } else {
            attemptTerminalToolCalled = true
        }
        val diagnosticArguments = arguments ?: return null
        return synchronized(localDiagnosticLock) {
            if (!localDiagnosticCaptureEnabled) {
                null
            } else {
                val order = localDiagnosticCalls.size
                localDiagnosticCalls += LocalToolCallDiagnostic(
                    order = order,
                    name = name,
                    arguments = diagnosticArguments,
                    terminal = name != LOAD_SKILL_NAME,
                )
                order
            }
        }
    }

    private fun recordLocalToolResult(
        order: Int?,
        resultType: String,
        resultContent: String?,
        toolResult: Map<String, String>,
        succeeded: Boolean,
        directReply: Boolean,
        returnedToGemma: Boolean,
    ) {
        if (order == null) return
        synchronized(localDiagnosticLock) {
            val call = localDiagnosticCalls.getOrNull(order) ?: return@synchronized
            localDiagnosticCalls[order] = call.copy(
                resultType = resultType,
                resultContent = resultContent,
                toolResult = toolResult.toMap(),
                succeeded = succeeded,
                directReply = directReply,
                returnedToGemma = returnedToGemma,
            )
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

    @Tool(description = "Loads full instructions for a gateway skill (meal_planner, run_js, run_intent). Load a relevant skill when parameters or gateway-specific rules are unclear. If the user explicitly asks for skill instructions before acting, load that exact skill as the FIRST tool call even when the action is clear; wait for success, then follow the instructions. For run_intent, a successful load_skill MUST be followed by run_intent for the original request before any final reply; for other gateway skills, follow their returned instructions.")
    fun loadSkill(
        @ToolParam(description = "The gateway skill name to load, such as run_intent.") skillName: String,
    ): Map<String, String> {
        val request = """{"skill_name":"$skillName"}"""
        val arguments = if (localDiagnosticCaptureEnabled) mapOf("skill_name" to skillName) else null
        if (isSafeModelTestActive() && skillName != "run_intent") {
            return denyInSafeModelTest(LOAD_SKILL_NAME, request, arguments)
        }
        val diagnosticOrder = recordToolCall(LOAD_SKILL_NAME, request, arguments)
        Log.d(TAG, "ToolSet: loadSkill($skillName)")
        val result = executeSkill(LOAD_SKILL_NAME, mapOf("skill_name" to skillName), diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        return result
    }

    @Tool(description = "Execute supported native Android actions such as alarms, calendar, media, navigation, contacts, and system toggles, plus list/note operations. If the user asks to read run_intent instructions first, load_skill(skill_name='run_intent') MUST be the first tool call; wait for success, then call run_intent. Otherwise call run_intent directly when clear. Use memory tools only for personal facts, not list/note contents. NOT for weather, system info, web search, or currency; use dedicated top-level tools.")
    fun runIntent(
        @ToolParam(description = "The model-callable intent identifier; use its exact catalogue spelling (unique case/separator variants are normalized).") intentName: String,
        @ToolParam(description = "A valid JSON object encoded as a string; do not send CSV or separate fields. For get_date_diff: ${RunIntentSkill.GET_DATE_DIFF_PARAMETER_HELP}. For bulk_add_to_list, use items as one JSON array of strings and pass the requested list_name. For create_calendar_event use title, date (pass relative dates as-is like \"next friday\"), time (HH:MM 24h), duration_minutes (integer minutes from start to end). For other intents, pass required fields directly.") parameters: String,
    ): Map<String, String> {
        val resolvedIntentName = resolveRunIntentName(intentName)
        if (resolvedIntentName != intentName) {
            Log.i(TAG, "ToolSet: normalized run_intent '$intentName' to '$resolvedIntentName'")
        }
        val request =
            """{"intent_name":"$intentName","parameters":${if (parameters.isBlank()) "{}" else parameters}}"""
        val arguments = if (localDiagnosticCaptureEnabled) {
            mapOf("intent_name" to intentName, "parameters" to parameters)
        } else {
            null
        }
        if (isSafeModelTestActive() &&
            !isAllowedSafeModelTestRunIntent(resolvedIntentName, parameters)
        ) {
            return denyInSafeModelTest("run_intent", request, arguments)
        }
        val diagnosticOrder = recordToolCall("run_intent", request, arguments)
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
        if (resolvedIntentName in reservedSkillNames) {
            val error = "Invalid run_intent call: '$resolvedIntentName' is a skill name, not an intent. Use load_skill first for skills like meal_planner or query_wikipedia."
            lastToolResult = error
            recordToolOutcome("run_intent", succeeded = false)
            captureTerminalResult("run_intent")
            val toolResult = mapOf("status" to "error", "error" to error)
            recordLocalToolResult(
                diagnosticOrder,
                "Rejected",
                error,
                toolResult,
                succeeded = false,
                directReply = false,
                returnedToGemma = true,
            )
            return toolResult
        }

        val args = mutableMapOf("intent_name" to resolvedIntentName)
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
                val toolResult = mapOf("status" to "error", "error" to error)
                recordLocalToolResult(
                    diagnosticOrder,
                    "Rejected",
                    error,
                    toolResult,
                    succeeded = false,
                    directReply = false,
                    returnedToGemma = true,
                )
                return toolResult
            }
            Log.w(TAG, "ToolSet: runIntent blank params parse, using empty: ${e.message}")
        }

        val result = executeSkill("run_intent", args, diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("run_intent")
        return result
    }

    @Tool(description = "Run a JS-backed skill. Call loadSkill first to learn which skills are available and what parameters each needs.")
    fun runJs(
        @ToolParam(description = "A JSON object with skill_name (the JS skill to run) and data (a JSON object with the skill's parameters). Call loadSkill to learn the exact format.") parameters: String,
    ): Map<String, String> {
        val request = parameters
        val arguments = if (localDiagnosticCaptureEnabled) mapOf("parameters" to parameters) else null
        if (isSafeModelTestActive()) {
            return denyInSafeModelTest("run_js", request, arguments)
        }
        val diagnosticOrder = recordToolCall("run_js", request, arguments)
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

        val result = executeSkill("run_js", args, diagnosticOrder)
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
        val request =
            """{"amount":"$amount","from_currency":"$fromCurrency","to_currency":"$toCurrency"}"""
        val arguments = if (localDiagnosticCaptureEnabled) {
            mapOf("amount" to amount, "from_currency" to fromCurrency, "to_currency" to toCurrency)
        } else {
            null
        }
        if (isSafeModelTestActive()) {
            return denyInSafeModelTest("convert_currency", request, arguments)
        }
        val diagnosticOrder = recordToolCall("convert_currency", request, arguments)
        Log.d(TAG, "ToolSet: convertCurrency(amount=$amount, from=$fromCurrency, to=$toCurrency)")

        val args = mapOf(
            "amount" to amount,
            "from_currency" to fromCurrency,
            "to_currency" to toCurrency,
        )

        val result = executeSkill("convert_currency", args, diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("convert_currency")
        return result
    }

    @Tool(description = "Get current weather or a multi-day forecast (pass forecastDays=1-7 for forecast). ONLY for weather queries. NOT for date, time, or general knowledge.")
    fun getWeather(
        @ToolParam(description = "Optional location/city name. Leave blank for device GPS location.") location: String,
        @ToolParam(description = "Number of forecast days (1-7). Omit or pass 0 for current conditions only.") forecastDays: String,
    ): Map<String, String> {
        val request = """{"location":"$location","forecast_days":"$forecastDays"}"""
        val arguments = if (localDiagnosticCaptureEnabled) {
            mapOf("location" to location, "forecast_days" to forecastDays)
        } else {
            null
        }
        if (isSafeModelTestActive()) {
            return denyInSafeModelTest("get_weather", request, arguments)
        }
        val diagnosticOrder = recordToolCall("get_weather", request, arguments)
        Log.d(TAG, "ToolSet: getWeather(location=$location, forecastDays=$forecastDays)")

        val args = mutableMapOf<String, String>()
        if (location.isNotBlank()) {
            args["location"] = location
        }
        if (forecastDays.isNotBlank() && forecastDays != "0") {
            args["forecast_days"] = forecastDays
        }

        val result = executeSkill("get_weather_gps", args, diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("get_weather")
        return result
    }

    @Tool(description = "Look up a named person, place, organisation, event, or other encyclopedia topic on Wikipedia. Use ONLY for explicit Wikipedia searches or fact lookups about a specific named entity. NOT for unit, measurement, or cooking conversions (cups, grams, ml, tbsp), arithmetic, weather, date/time, definitions, or how-to questions — answer those directly without a tool.")
    fun queryWikipedia(
        @ToolParam(description = "The topic, entity, or article title to look up on Wikipedia.") query: String,
    ): Map<String, String> {
        val request = """{"query":"${query.replace("\"", "\\\"").take(200)}"}"""
        val arguments = if (localDiagnosticCaptureEnabled) mapOf("query" to query) else null
        if (isSafeModelTestActive()) {
            return denyInSafeModelTest("query_wikipedia", request, arguments)
        }
        val diagnosticOrder = recordToolCall("query_wikipedia", request, arguments)
        Log.d(TAG, "ToolSet: queryWikipedia(${query.take(60)})")
        val result = executeSkill("query_wikipedia", mapOf("query" to query), diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("query_wikipedia")
        return result
    }

    @Tool(description = "Get current date/time and device runtime info including hardware tier, available memory, battery level, and device details. ALWAYS use this for current date, time, or day queries.")
    fun getSystemInfo(): Map<String, String> {
        val request = "{}"
        val arguments = if (localDiagnosticCaptureEnabled) emptyMap<String, String>() else null
        if (isSafeModelTestActive()) {
            return denyInSafeModelTest("get_system_info", request, arguments)
        }
        val diagnosticOrder = recordToolCall("get_system_info", request, arguments)
        Log.d(TAG, "ToolSet: getSystemInfo()")
        val result = executeSkill("get_system_info", emptyMap(), diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("get_system_info")
        return result
    }

    @Tool(description = "Save an important fact or preference to long-term memory. NOT for list items, calendar events, or alarms — use runIntent for those.")
    fun saveMemory(
        @ToolParam(description = "The exact fact or preference to save, verbatim as the user stated it.") content: String,
    ): Map<String, String> {
        val request = """{"content":"${content.replace("\"", "\\\"").take(200)}"}"""
        val arguments = if (localDiagnosticCaptureEnabled) mapOf("content" to content) else null
        if (isSafeModelTestActive()) {
            return denyInSafeModelTest("save_memory", request, arguments)
        }
        val diagnosticOrder = recordToolCall("save_memory", request, arguments)
        Log.d(TAG, "ToolSet: saveMemory(${content.take(60)})")
        val result = executeSkill("save_memory", mapOf("content" to content), diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("save_memory")
        return result
    }

    @Tool(description = "Search saved memories and past conversations for information. NOT for web search, Wikipedia, or weather.")
    fun searchMemory(
        @ToolParam(description = "What to search for in saved memories and past messages.") query: String,
    ): Map<String, String> {
        val request = """{"query":"$query"}"""
        val arguments = if (localDiagnosticCaptureEnabled) mapOf("query" to query) else null
        if (isSafeModelTestActive()) {
            return denyInSafeModelTest("search_memory", request, arguments)
        }
        val diagnosticOrder = recordToolCall("search_memory", request, arguments)
        Log.d(TAG, "ToolSet: searchMemory($query)")
        val result = executeSkill("search_memory", mapOf("query" to query), diagnosticOrder)
        lastToolResult = result["result"] ?: result["error"]
        captureTerminalResult("search_memory")
        return result
    }

    // -------------------------------------------------------------------------
    // Internal dispatch
    private fun executeSkill(
        skillName: String,
        args: Map<String, String>,
        diagnosticOrder: Int?,
    ): Map<String, String> {
        if (isSafeModelTestActive() && !isAllowedSafeModelTestDispatch(skillName, args)) {
            recordToolOutcome(skillName, succeeded = false)
            val toolResult = mapOf("error" to "Blocked by safe model-test allowlist")
            recordLocalToolResult(
                diagnosticOrder,
                "Blocked",
                toolResult["error"],
                toolResult,
                succeeded = false,
                directReply = false,
                returnedToGemma = true,
            )
            return toolResult
        }
        val skill = skillRegistry.get().get(skillName)
            ?: run {
                recordToolOutcome(skillName, succeeded = false)
                val toolResult = mapOf("error" to "Unknown skill: $skillName")
                recordLocalToolResult(
                    diagnosticOrder,
                    "UnknownSkill",
                    toolResult["error"],
                    toolResult,
                    succeeded = false,
                    directReply = false,
                    returnedToGemma = true,
                )
                return toolResult
            }

        return try {
            val result = runBlocking {
                skill.execute(SkillCall(skillName = skillName, arguments = args))
            }
            val succeeded = result is SkillResult.Success || result is SkillResult.DirectReply
            recordToolOutcome(skillName, succeeded)
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
            val toolResult = when (result) {
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
            recordLocalToolResult(
                diagnosticOrder,
                result::class.simpleName ?: "Unknown",
                resultContent,
                toolResult,
                succeeded = succeeded,
                directReply = lastToolWasDirectReply,
                returnedToGemma = returnedToGemma,
            )
            toolResult
        } catch (e: Exception) {
            recordToolOutcome(skillName, succeeded = false)
            lastToolPresentation = null
            lastToolSpokenSummary = null
            Log.e(TAG, "ToolSet: $skillName execution failed", e)
            val error = e.message ?: "Unknown error executing $skillName"
            val toolResult = mapOf("error" to error)
            recordLocalToolResult(
                diagnosticOrder,
                "Exception",
                error,
                toolResult,
                succeeded = false,
                directReply = false,
                returnedToGemma = true,
            )
            toolResult
        }
    }
}
