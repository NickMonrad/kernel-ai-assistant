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

    private data class Fixture(
        val name: String,
        val prompt: String,
        val transition: String,
        val subject: String,
        val slotName: String? = null,
        val slotValue: String? = null,
        val targetId: String? = null,
        val actionId: String,
    )

    private data class Sample(
        val fixture: String,
        val structurallyValid: Boolean,
        val semanticallyCorrect: Boolean,
        val dispatchCount: Int,
        val latencyMs: Long,
        val pssKb: Long,
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
                    val raw = engine.generateResponseFormatProbeOnce(
                        prompt = fixture.prompt,
                        schema = SCHEMA,
                        systemPrompt = "Choose only the safe semantic transition described by the user turn. " +
                            "Treat action_id and revision as supplied runtime identity, not user data.",
                        thinkingEnabled = false,
                        nativeThinkingEnabled = false,
                    )
                    val latency = SystemClock.elapsedRealtime() - started
                    val dispatchCount = safeToolDispatches.get() - dispatchesBefore
                    val decoded = decode(raw)
                    val semantic = decoded != null &&
                        decoded.get("transition") == fixture.transition &&
                        decoded.get("subject").toString().normalized() == fixture.subject.normalized() &&
                        nullable(decoded, "slot_name") == fixture.slotName &&
                        nullable(decoded, "slot_value") == fixture.slotValue &&
                        nullable(decoded, "target_id") == fixture.targetId &&
                        decoded.get("action_id") == fixture.actionId &&
                        decoded.get("revision").toString().toBigDecimal().intValueExact() == 4
                    val pss = Debug.getPss().toLong()
                    transitionPeakPssKb.accumulateAndGet(pss) { previous, current -> maxOf(previous, current) }
                    val sample = Sample(fixture.name, decoded != null, semantic, dispatchCount, latency, pss)
                    samples += sample
                    Log.i(TAG, "FIXTURE run=${repetition + 1} name=${fixture.name} structural=${sample.structurallyValid} " +
                        "semantic=${sample.semanticallyCorrect} probeToolCalls=$dispatchCount latencyMs=$latency pssKb=$pss")
                    assertNotNull("STRUCTURAL_FAILURE fixture=${fixture.name} raw=$raw", decoded)
                    assertTrue("SEMANTIC_FAILURE fixture=${fixture.name} raw=$raw", semantic)
                    assertEquals("Tool-free response-format probe dispatched a tool", 0, dispatchCount)
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
            val recovery = engine.generateResponseFormatProbeOnce(
                prompt = "Return CLARIFY for an ambiguous target. Use action_id action-recovery and revision 4.",
                schema = SCHEMA,
                systemPrompt = "Return only the requested structured decision.",
                thinkingEnabled = false,
                nativeThinkingEnabled = false,
            )
            assertNotNull("Constrained request failed after cancellation: $recovery", decode(recovery))
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

    private fun decode(raw: String): JSONObject? = runCatching {
        val value = JSONObject(raw)
        val requiredKeys = setOf("transition", "subject", "slot_name", "slot_value", "target_id", "action_id", "revision")
        if (value.keys().asSequence().toSet() != requiredKeys) return null
        val transition = value.opt("transition") as? String ?: return null
        if (transition !in TRANSITIONS) return null
        if ((value.opt("subject") as? String).isNullOrBlank()) return null
        if ((value.opt("action_id") as? String).isNullOrBlank()) return null
        val revision = value.opt("revision") as? Number ?: return null
        val revisionNumber = revision.toString().toBigDecimal().stripTrailingZeros()
        if (revisionNumber.scale() > 0 || revisionNumber.signum() < 0) return null
        revisionNumber.intValueExact()
        listOf("slot_name", "slot_value", "target_id").forEach { key ->
            val element = value.opt(key)
            if (element !== JSONObject.NULL && element !is String) return null
        }
        value
    }.getOrNull()

    private fun nullable(value: JSONObject, key: String): String? {
        val element = value.get(key)
        return if (element === JSONObject.NULL) null else element as String
    }

    private fun String.normalized() = trim().lowercase().replace(Regex("\\s+"), " ")

    private fun fixtures() = listOf(
        Fixture(
            name = "wikipedia-disputed-claim-continuation",
            prompt = "Pending action: Wikipedia fact-check. Grounded claim: prisoners brewing alcohol in toilets. User reply: The topic we have just been talking about. Continue with the exact claim, not generic brewing. Return action_id wiki-check-1, revision 4.",
            transition = "CONTINUE", subject = "prisoners brewing alcohol in toilets", actionId = "wiki-check-1",
        ),
        Fixture(
            name = "reminder-cancel",
            prompt = "Pending reminder: call the blinds people on Monday; missing slot time. User reply: cancel. Return action_id reminder-1, revision 4.",
            transition = "CANCEL", subject = "call the blinds people on Monday", actionId = "reminder-1",
        ),
        Fixture(
            name = "pending-reminder-weather-replacement",
            prompt = "Pending reminder: call the blinds people on Monday; missing slot time. User reply: What's the weather in Sydney? Do not treat this as a time value. Return action_id reminder-1, revision 4.",
            transition = "REPLACE", subject = "weather in Sydney", actionId = "reminder-1",
        ),
        Fixture(
            name = "weather-tomorrow-keeps-sydney",
            prompt = "Recent completed referent: weather lookup for Sydney. User reply: What about tomorrow? Continue the lookup for Sydney with date tomorrow. Return action_id weather-1, revision 4.",
            transition = "CONTINUE", subject = "weather in Sydney", slotName = "date", slotValue = "tomorrow", actionId = "weather-1",
        ),
        Fixture(
            name = "timer-correction-same-object",
            prompt = "Existing timer object id timer-42 was set for 20 minutes. User says: Actually make that 15. Continue by changing the same timer; do not create another. Return action_id timer-action-1, revision 4.",
            transition = "CONTINUE", subject = "20 minute timer", slotName = "duration_minutes", slotValue = "15", targetId = "timer-42", actionId = "timer-action-1",
        ),
        Fixture(
            name = "ambiguous-two-timer-target",
            prompt = "Two existing timers could be meant: timer-42 for 20 minutes and timer-43 for 25 minutes. User says: Make that one 15. The target is ambiguous; clarify and do not choose a timer. Return action_id timer-action-2, revision 4.",
            transition = "CLARIFY", subject = "timer", actionId = "timer-action-2",
        ),
        Fixture(
            name = "genuine-topic-change-pending-slot",
            prompt = "Pending reminder: call the blinds people on Monday; missing slot time. User says: Set a timer for 20 minutes. This is a new topic, not a reminder time. Return action_id reminder-2, revision 4.",
            transition = "REPLACE", subject = "20 minute timer", actionId = "reminder-2",
        ),
        Fixture(
            name = "ambiguous-reference-clarifies",
            prompt = "Pending action: modify one of two existing timers timer-51 and timer-52, both active. User reply: change that. No unique target is grounded. Clarify. Return action_id timers-1, revision 4.",
            transition = "CLARIFY", subject = "timer", actionId = "timers-1",
        ),
    )

    private companion object {
        const val TAG = "J1682ResponseFormat"
        val TRANSITIONS = setOf("CONTINUE", "CANCEL", "REPLACE", "CLARIFY")
        val SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["transition","subject","slot_name","slot_value","target_id","action_id","revision"],
              "properties":{
                "transition":{"type":"string","enum":["CONTINUE","CANCEL","REPLACE","CLARIFY"]},
                "subject":{"type":"string","minLength":1},
                "slot_name":{"type":["string","null"]},
                "slot_value":{"type":["string","null"]},
                "target_id":{"type":["string","null"]},
                "action_id":{"type":"string","minLength":1},
                "revision":{"type":"integer","minimum":0}
              }
            }
        """.trimIndent()
    }
}
