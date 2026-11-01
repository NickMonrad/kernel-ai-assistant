package com.kernel.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.LiteRtInferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Run explicitly per supported physical model/backend lane; never part of the default device suite. */
class ConstrainedTransitionResponseFormatDeviceTest {

    /** App-owned held state. Only [id] is ever offered to the model as an echoable value. */
    private data class HeldAction(
        val id: String,
        val description: String,
        val status: String,
        val slots: List<String> = emptyList(),
    )

    private data class Fixture(
        val name: String,
        val held: List<HeldAction>,
        val context: String?,
        val userReply: String,
        val transition: String,
        val actionId: String?,
        val slotName: String? = null,
        val slotValue: String? = null,
        val dispatchTarget: String? = null,
    )

    private data class Proposal(
        val transition: String,
        val actionId: String?,
        val slotName: String?,
        val slotValue: String?,
    )

    private data class Sample(
        val run: Int,
        val fixture: String,
        val structurallyValid: Boolean,
        val semanticallyCorrect: Boolean,
        val slotCorrect: Boolean,
        val proposalDispatch: String?,
        val wrongDispatch: Boolean,
        val dispatchCount: Int,
        val latencyMs: Long,
        val pssKb: Long,
        val raw: String,
    )

    private val safeToolDispatches = AtomicInteger()
    private val safeToolProvider: ToolProvider = tool(
        object : OpenApiTool {
            private val description = JSONObject().apply {
                put("name", "j1682_safe_probe")
                put("description", "Test-only no-effect probe; call only when explicitly requested.")
                put(
                    "parameters",
                    JSONObject(
                        """{"type":"object","additionalProperties":false,"required":["nonce"],"properties":{"nonce":{"type":"string","enum":["J1682_SAFE"]}}}""",
                    ),
                )
            }.toString()

            override fun getToolDescriptionJsonString(): String = description

            override fun execute(paramsJsonString: String): String {
                require(JSONObject(paramsJsonString).getString("nonce") == "J1682_SAFE")
                safeToolDispatches.incrementAndGet()
                return "J1682_SAFE_TOOL_OK"
            }
        },
    )


    @Test
    fun nativeResponseFormatRepeatedSemanticFixturesAndLifecycle(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assertEquals("com.kernel.ai.debug", context.packageName)
        assertEquals("android.app.Application", context.applicationContext.javaClass.name)
        safeToolDispatches.set(0)

        val args = InstrumentationRegistry.getArguments()
        val expectedDevice = args.getString("device") ?: error("Pass device=S21 or device=S23U")
        val requestedModel = args.getString("model") ?: error("Pass model=E2B or model=E4B")
        val requestedBackend = args.getString("backend") ?: error("Pass backend=CPU or backend=GPU")
        val repeats = args.getString("repeats")?.toInt() ?: 2
        assertTrue("Bounded repeat count must be 2..3", repeats in 2..3)
        assertTrue("Unsupported model lane: $requestedModel", requestedModel in setOf("E2B", "E4B"))
        assertTrue("Unsupported backend lane: $requestedBackend", requestedBackend in setOf("CPU", "GPU"))
        val isS21 = Build.MODEL.contains("SM-G991", true) || Build.MODEL.contains("SM_G991", true)
        val isS23U = Build.MODEL.contains("SM-S918", true) || Build.MODEL.contains("SM_S918", true)
        assertTrue("This lane requires an S21 or S23U", isS21 || isS23U)
        assertTrue(
            "Device argument does not match ${Build.MODEL}",
            expectedDevice == "S21" && isS21 || expectedDevice == "S23U" && isS23U,
        )

        val expectedModel = if (isS21) "E2B" else "E4B"
        assertEquals("Unexpected model for the requested device lane", expectedModel, requestedModel)
        val expectedTier = if (isS21) HardwareTier.MID_RANGE else HardwareTier.FLAGSHIP
        val hardwareProfileDetector = HardwareProfileDetector(context)
        val hardwareProfile = hardwareProfileDetector.profile
        assertEquals("Unexpected hardware tier for the requested lane", expectedTier, hardwareProfile.tier)
        val expectedMaxTokens = if (isS21) 3072 else 8000
        assertEquals(expectedMaxTokens, hardwareProfile.recommendedMaxTokens)

        val model = if (requestedModel == "E2B") KernelModel.GEMMA_4_E2B else KernelModel.GEMMA_4_E4B
        val modelFile = model.localFile(context)
        assumeTrue("MODEL_NOT_INSTALLED model=$requestedModel", modelFile.isFile)
        val backend = if (requestedBackend == "CPU") BackendType.CPU else BackendType.GPU
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else 0
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        Log.i(TAG, "PREFLIGHT device=${Build.MODEL} model=$requestedModel backend=$requestedBackend " +
            "modelBytes=${modelFile.length()} thermalStatus=$thermalStatus lowMemory=${memoryInfo.lowMemory} " +
            "availMemBytes=${memoryInfo.availMem}")
        assumeTrue("THERMAL_GATE_BLOCKED status=$thermalStatus", thermalStatus < PowerManager.THERMAL_STATUS_SEVERE)
        assumeTrue("MEMORY_GATE_BLOCKED lowMemory=${memoryInfo.lowMemory}", !memoryInfo.lowMemory)

        val config = ModelConfig(
            modelPath = modelFile.absolutePath,
            backendType = backend,
            maxTokens = hardwareProfile.recommendedMaxTokens,
            systemPrompt = "For the transition probe, return only the requested structured decision. " +
                "For ordinary chat, reply briefly.",
            thinkingEnabled = false,
            // Isolate response-format behavior from the LiteRT-LM #3944 MTP interaction.
            speculativeDecodingEnabled = false,
            toolProvider = safeToolProvider,
        )

        val baselineRuns = mutableListOf<Pair<String, Long>>()
        val baselinePeakPssKb = AtomicLong(0L)
        val baselineEngine = LiteRtInferenceEngine(context, hardwareProfileDetector)
        var baselinePssSampler: Job? = null
        var baselineInitMs = 0L
        try {
            baselinePssSampler = launch(Dispatchers.Default) {
                while (isActive) {
                    baselinePeakPssKb.accumulateAndGet(Debug.getPss().toLong()) { previous, current ->
                        maxOf(previous, current)
                    }
                    delay(100L)
                }
            }
            val initStarted = SystemClock.elapsedRealtime()
            baselineEngine.initialize(config)
            baselineInitMs = SystemClock.elapsedRealtime() - initStarted
            assertEquals("Baseline backend mismatch", backend, baselineEngine.activeBackend.value)
            assertEquals(expectedMaxTokens, baselineEngine.resolvedMaxTokens.value)
            repeat(repeats) {
                val baseline = ordinaryGeneration(baselineEngine, "Say READY in one word.")
                assertTrue("Ordinary baseline chat returned no text", baseline.first.isNotBlank())
                assertEquals("Baseline prompt unexpectedly dispatched the safe tool", 0, safeToolDispatches.get())
                baselineRuns += baseline
            }
        } finally {
            baselinePssSampler?.cancelAndJoin()
            baselineEngine.shutdown()
        }
        val baselineWarmLatencies = baselineRuns.drop(1).map { it.second }.sorted()
        val baselineWarmMedian = baselineWarmLatencies[baselineWarmLatencies.size / 2]
        val baselinePeakPss = baselinePeakPssKb.get()
        Log.i(TAG, "BASELINE_CHAT device=${Build.MODEL} model=$requestedModel backend=$backend " +
            "initMs=$baselineInitMs coldLatencyMs=${baselineRuns.first().second} " +
            "warmMedianLatencyMs=$baselineWarmMedian peakPssKb=$baselinePeakPss")

        val samples = mutableListOf<Sample>()
        val transitionPeakPssKb = AtomicLong(0L)
        val engine = LiteRtInferenceEngine(context, hardwareProfileDetector)
        var transitionPssSampler: Job? = null
        var transitionInitMs = 0L
        try {
            transitionPssSampler = launch(Dispatchers.Default) {
                while (isActive) {
                    transitionPeakPssKb.accumulateAndGet(Debug.getPss().toLong()) { previous, current ->
                        maxOf(previous, current)
                    }
                    delay(100L)
                }
            }
            val initStarted = SystemClock.elapsedRealtime()
            engine.initialize(config)
            transitionInitMs = SystemClock.elapsedRealtime() - initStarted
            assertEquals("Requested backend must not silently fall back", backend, engine.activeBackend.value)
            assertEquals("Harness must use the lane's safe KV-cache budget", expectedMaxTokens, engine.resolvedMaxTokens.value)
            Log.i(TAG, "ENGINE_READY device=${Build.MODEL} model=$requestedModel backend=${engine.activeBackend.value} " +
                "initMs=$transitionInitMs")

            val fixtures = fixtures()
            repeat(repeats) { repetition ->
                fixtures.forEach { fixture ->
                    val dispatchesBefore = safeToolDispatches.get()
                    val started = SystemClock.elapsedRealtime()
                    // Constraint setup/generation failure is a failed sample; never retried unconstrained.
                    val raw = try {
                        engine.generateResponseFormatProbeOnce(
                            prompt = promptFor(fixture.held, fixture.context, fixture.userReply),
                            schema = schemaFor(fixture.held),
                            systemPrompt = TRANSITION_SYSTEM_PROMPT,
                            thinkingEnabled = false,
                            nativeThinkingEnabled = false,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        "PROBE_EXCEPTION ${error.javaClass.simpleName}: ${error.message}"
                    }
                    val latency = SystemClock.elapsedRealtime() - started
                    val dispatchCount = safeToolDispatches.get() - dispatchesBefore
                    val proposal = decode(raw, fixture.held)
                    val semantic = proposal != null &&
                        proposal.transition == fixture.transition && proposal.actionId == fixture.actionId
                    val slotCorrect = proposal != null && proposal.slotName == fixture.slotName &&
                        proposal.slotValue?.normalized() == fixture.slotValue?.normalized()
                    // What a reducer trusting any structurally valid proposal would dispatch.
                    val proposalDispatch = proposal?.takeIf { it.transition == "CONTINUE" }?.actionId
                    val wrongDispatch = proposalDispatch != fixture.dispatchTarget ||
                        (proposalDispatch != null && !slotCorrect)
                    val pss = Debug.getPss().toLong()
                    transitionPeakPssKb.accumulateAndGet(pss) { previous, current -> maxOf(previous, current) }
                    val sample = Sample(
                        repetition + 1, fixture.name, proposal != null, semantic, slotCorrect,
                        proposalDispatch, wrongDispatch, dispatchCount, latency, pss, raw,
                    )
                    samples += sample
                    Log.i(TAG, "FIXTURE run=${sample.run} name=${fixture.name} structural=${sample.structurallyValid} " +
                        "semantic=${sample.semanticallyCorrect} slot=${sample.slotCorrect} " +
                        "proposalDispatch=$proposalDispatch expectedDispatch=${fixture.dispatchTarget} " +
                        "wrongDispatch=$wrongDispatch probeToolCalls=$dispatchCount latencyMs=$latency pssKb=$pss " +
                        "raw=${raw.replace('\n', ' ').take(300)}")
                }
            }

            val cold = samples.first()
            val warmLatencies = samples.drop(1).map { it.latencyMs }.sorted()
            val warmMedian = warmLatencies[warmLatencies.size / 2]
            val peakPss = transitionPeakPssKb.get()
            val latencyDelta = warmMedian - baselineWarmMedian
            val pssDelta = peakPss - baselinePeakPss
            Log.i(TAG, "SPIKE_SUMMARY device=${Build.MODEL} model=$requestedModel backend=$requestedBackend " +
                "repeats=$repeats fixtureCount=${fixtures.size} structuralPass=${samples.count { it.structurallyValid }}/${samples.size} " +
                "semanticPass=${samples.count { it.semanticallyCorrect }}/${samples.size} " +
                "slotPass=${samples.count { it.slotCorrect }}/${samples.size} " +
                "wrongDispatch=${samples.count { it.wrongDispatch }} " +
                "probeToolCalls=${samples.sumOf { it.dispatchCount }} " +
                "initMs=$transitionInitMs coldLatencyMs=${cold.latencyMs} baselineWarmMedianMs=$baselineWarmMedian " +
                "warmMedianLatencyMs=$warmMedian latencyDeltaMs=$latencyDelta baselinePeakPssKb=$baselinePeakPss " +
                "peakPssKb=$peakPss pssDeltaKb=$pssDelta")


            val cancelSchema = """{"type":"object","additionalProperties":false,"required":["value"],"properties":{"value":{"type":"string","minLength":2048,"maxLength":2048}}}"""
            val cancelStarted = SystemClock.elapsedRealtime()
            val longGeneration = async {
                engine.generateResponseFormatProbeOnce(
                    prompt = "Produce a 2048-character value to satisfy the schema; do not stop early.",
                    schema = cancelSchema,
                    systemPrompt = "Return only the schema-constrained object.",
                    thinkingEnabled = false,
                    nativeThinkingEnabled = false,
                )
            }
            withTimeout(120_000L) {
                while (!engine.isGenerating.value) delay(20L)
            }
            delay(250L)
            assertFalse("Cancellation probe completed before cancellation was requested", longGeneration.isCompleted)
            longGeneration.cancelAndJoin()
            val cancellationDrainMs = SystemClock.elapsedRealtime() - cancelStarted
            assertFalse("Engine remained generating after cancellation", engine.isGenerating.value)
            val recoveryState = fixtures.single { it.name == "ambiguous-two-timer-target" }
            val recovery = engine.generateResponseFormatProbeOnce(
                prompt = promptFor(recoveryState.held, recoveryState.context, recoveryState.userReply),
                schema = schemaFor(recoveryState.held),
                systemPrompt = TRANSITION_SYSTEM_PROMPT,
                thinkingEnabled = false,
                nativeThinkingEnabled = false,
            )
            assertNotNull("Constrained request failed after cancellation: $recovery", decode(recovery, recoveryState.held))
            assertEquals("Recovery probe dispatched the safe tool", 0, safeToolDispatches.get())
            Log.i(TAG, "CANCELLATION drainMs=$cancellationDrainMs recovery=PASS pssKb=${Debug.getPss()}")


            val dispatchesBeforePostSpike = safeToolDispatches.get()
            val ordinaryAfter = ordinaryGeneration(
                engine,
                "Call j1682_safe_probe exactly once with nonce J1682_SAFE, then reply briefly.",
            )
            assertTrue("Ordinary generation failed after the spike", ordinaryAfter.first.isNotBlank())
            assertEquals("Post-spike normal tool dispatch did not execute exactly once", 1,
                safeToolDispatches.get() - dispatchesBeforePostSpike)
            Log.i(TAG, "POST_SPIKE_ORDINARY_CHAT_TOOL latencyMs=${ordinaryAfter.second} " +
                "toolDispatches=${safeToolDispatches.get() - dispatchesBeforePostSpike} pssKb=${Debug.getPss()}")

            // Functional gates are asserted after lifecycle evidence so one run records every fixture.
            samples.filterNot { it.structurallyValid }.firstOrNull()?.let {
                throw AssertionError("STRUCTURAL_FAILURE run=${it.run} fixture=${it.fixture} raw=${it.raw}")
            }
            samples.filterNot { it.semanticallyCorrect && it.slotCorrect }.firstOrNull()?.let {
                throw AssertionError("SEMANTIC_FAILURE run=${it.run} fixture=${it.fixture} raw=${it.raw}")
            }
            assertEquals("Wrong proposal dispatches", 0, samples.count { it.wrongDispatch })
            assertEquals("Tool-free response-format probe dispatched a tool", 0, samples.sumOf { it.dispatchCount })
        } finally {
            transitionPssSampler?.cancelAndJoin()
            engine.shutdown()
        }
    }

    private suspend fun ordinaryGeneration(engine: LiteRtInferenceEngine, prompt: String): Pair<String, Long> {
        val started = SystemClock.elapsedRealtime()
        val output = StringBuilder()
        var error: String? = null
        var complete = false
        withTimeout(120_000L) {
            engine.generate(prompt).collect { result ->
                when (result) {
                    is GenerationResult.Token -> output.append(result.text)
                    is GenerationResult.Error -> error = result.message
                    is GenerationResult.Complete -> complete = true
                    is GenerationResult.Thinking -> Unit
                }
            }
        }
        assertEquals("Ordinary generation error", null, error)
        assertTrue("Ordinary generation did not complete", complete)
        return output.toString() to (SystemClock.elapsedRealtime() - started)
    }

    private fun promptFor(held: List<HeldAction>, context: String?, userReply: String): String = buildString {
        appendLine("Held actions (most recent first):")
        held.forEach { action ->
            val slots = if (action.slots.isEmpty()) "" else " (slots: ${action.slots.joinToString()})"
            appendLine("- id ${action.id} [${action.status}]: ${action.description}$slots")
        }
        context?.let { appendLine(it) }
        append("User reply: ").append(userReply)
    }

    /** Per-turn schema from app-owned state: enums of held ids and slot names; no free-text subject. */
    private fun schemaFor(held: List<HeldAction>): String {
        fun nullableEnum(values: List<String>) = JSONObject().put(
            "enum",
            JSONArray().apply { values.forEach { put(it) }; put(JSONObject.NULL) },
        )
        val properties = JSONObject()
            .put("transition", JSONObject().put("type", "string").put("enum", JSONArray(TRANSITIONS)))
            .put("action_id", nullableEnum(held.map { it.id }))
            .put("slot_name", nullableEnum(held.flatMap { it.slots }.distinct()))
            .put("slot_value", JSONObject().put("type", JSONArray(listOf("string", "null"))))
        return JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray(REQUIRED_KEYS))
            .put("properties", properties)
            .toString()
    }

    private fun decode(raw: String, held: List<HeldAction>): Proposal? = runCatching {
        val value = JSONObject(raw)
        if (value.keys().asSequence().toSet() != REQUIRED_KEYS.toSet()) return null
        fun nullableString(key: String): String? = when (val element = value.opt(key)) {
            JSONObject.NULL -> null
            is String -> element
            else -> throw IllegalArgumentException("$key is not a string or null")
        }
        val transition = nullableString("transition")?.takeIf { it in TRANSITIONS } ?: return null
        val actionId = nullableString("action_id")
        if (actionId != null && held.none { it.id == actionId }) return null
        val slotName = nullableString("slot_name")
        if (slotName != null && held.none { slotName in it.slots }) return null
        Proposal(transition, actionId, slotName, nullableString("slot_value"))
    }.getOrNull()

    private fun String.normalized() = trim().lowercase().replace(Regex("\\s+"), " ")

    // Mirrors the host ConstrainedTransitionFixtureHarnessTest fixtures. Prompts state the held
    // actions and the user turn only; they do not dictate the transition or id.
    private fun fixtures(): List<Fixture> {
        val wikiCheck = HeldAction("wiki-check-1", "Wikipedia fact-check of the claim that prisoners brew alcohol in toilets", "pending")
        val sydneyWeather = HeldAction("weather-1", "weather lookup for Sydney, today", "completed", listOf("date"))
        val reminder = HeldAction("reminder-1", "reminder to call the blinds people on Monday; waiting for the time", "pending", listOf("time"))
        val timer42 = HeldAction("timer-42", "timer for 20 minutes", "active", listOf("duration_minutes"))
        val timer43 = HeldAction("timer-43", "timer for 25 minutes", "active", listOf("duration_minutes"))
        val reminderQuestion = "Assistant's last message: What time on Monday should I remind you?"
        val weatherAnswer = "Assistant's last message: It is sunny in Sydney today."
        return listOf(
            Fixture(
                "wikipedia-disputed-claim-continuation", listOf(wikiCheck, sydneyWeather),
                "Assistant's last message: Which claim should I fact-check?", "The topic we have just been talking about.",
                "CONTINUE", "wiki-check-1", dispatchTarget = "wiki-check-1",
            ),
            Fixture("reminder-cancel", listOf(reminder, timer42), reminderQuestion, "cancel", "CANCEL", "reminder-1"),
            Fixture(
                "pending-reminder-weather-replacement", listOf(reminder), reminderQuestion,
                "What's the weather in Sydney?", "REPLACE", "reminder-1",
            ),
            Fixture(
                "weather-tomorrow-keeps-sydney", listOf(sydneyWeather), weatherAnswer, "What about tomorrow?",
                "CONTINUE", "weather-1", slotName = "date", slotValue = "tomorrow", dispatchTarget = "weather-1",
            ),
            Fixture(
                "timer-correction-same-object", listOf(timer42), "Assistant's last message: Timer set for 20 minutes.",
                "Actually make that 15.", "CONTINUE", "timer-42",
                slotName = "duration_minutes", slotValue = "15", dispatchTarget = "timer-42",
            ),
            Fixture("ambiguous-two-timer-target", listOf(timer42, timer43), null, "Make that one 15.", "CLARIFY", null),
            Fixture(
                "genuine-topic-change-pending-slot", listOf(reminder), reminderQuestion,
                "Set a timer for 20 minutes.", "REPLACE", "reminder-1",
            ),
            Fixture(
                "ambiguous-reference-clarifies",
                listOf(
                    HeldAction("timer-51", "timer for 10 minutes", "active", listOf("duration_minutes")),
                    HeldAction("timer-52", "timer for 30 minutes", "active", listOf("duration_minutes")),
                ),
                null, "Change that.", "CLARIFY", null,
            ),
            Fixture(
                "unrelated-request-after-completed-lookup", listOf(sydneyWeather), weatherAnswer,
                "Set a timer for 10 minutes.", "NEW_TOPIC", null,
            ),
        )
    }

    private companion object {
        const val TAG = "J1682ResponseFormat"
        val TRANSITIONS = listOf("CONTINUE", "CANCEL", "REPLACE", "NEW_TOPIC", "CLARIFY")
        val REQUIRED_KEYS = listOf("transition", "action_id", "slot_name", "slot_value")
        val TRANSITION_SYSTEM_PROMPT = """
            Decide how the user's reply relates to the app's held actions. Return only the structured decision.
            transition:
            - CONTINUE: the reply continues or modifies one held action; action_id is that action.
            - CANCEL: the reply cancels one pending action; action_id is that action.
            - REPLACE: the reply is a different request that supersedes a pending action; action_id is the superseded pending action.
            - NEW_TOPIC: the reply is an unrelated request and no pending action is superseded; action_id is null.
            - CLARIFY: it is unclear which held action is meant or what the user wants; action_id is the single action concerned, or null if more than one could be meant.
            slot_name and slot_value: set only when the user supplies a new value for a listed slot of the chosen action; slot_value is the user's value only. Otherwise both are null.
        """.trimIndent()
    }
}
