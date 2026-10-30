package com.kernel.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.ToolProvider
import com.google.gson.JsonParser
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.LiteRtInferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run explicitly per supported physical model/backend lane; never part of the default device suite. */
class ConstrainedTransitionResponseFormatDeviceTest {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ToolsEntryPoint {
        fun toolProvider(): ToolProvider
    }

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

    @Test
    fun nativeResponseFormatRepeatedSemanticFixturesAndLifecycle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assertEquals("com.kernel.ai.debug", context.packageName)

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

        val model = when (requestedModel) {
            "E2B" -> KernelModel.GEMMA_4_E2B
            else -> KernelModel.GEMMA_4_E4B
        }
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

        val toolProvider = EntryPointAccessors.fromApplication(context, ToolsEntryPoint::class.java).toolProvider()
        val engine = LiteRtInferenceEngine(context, HardwareProfileDetector(context))
        val config = ModelConfig(
            modelPath = modelFile.absolutePath,
            backendType = backend,
            systemPrompt = "For the transition probe, return only the requested structured decision. For ordinary chat, reply briefly.",
            thinkingEnabled = false,
            toolProvider = toolProvider,
        )
        val samples = mutableListOf<Sample>()
        val baselinePeakPssKb = AtomicLong(0L)
        val transitionPeakPssKb = AtomicLong(0L)
        val samplingTransition = AtomicBoolean(false)
        var pssSampler: Job? = null
        try {
            val initStarted = SystemClock.elapsedRealtime()
            engine.initialize(config)
            val initMs = SystemClock.elapsedRealtime() - initStarted
            assertEquals("Requested backend must not silently fall back", backend, engine.activeBackend.value)
            Log.i(TAG, "ENGINE_READY device=${Build.MODEL} model=$requestedModel backend=${engine.activeBackend.value} initMs=$initMs")
            baselinePeakPssKb.set(Debug.getPss().toLong())
            pssSampler = launch(Dispatchers.Default) {
                while (isActive) {
                    val pss = Debug.getPss().toLong()
                    val target = if (samplingTransition.get()) transitionPeakPssKb else baselinePeakPssKb
                    target.accumulateAndGet(pss) { previous, current -> maxOf(previous, current) }
                    delay(100L)
                }
            }

            val baselineRuns = mutableListOf<Pair<String, Long>>()
            repeat(repeats) {
                val baseline = ordinaryGeneration(engine, "Say READY in one word.")
                assertTrue("Ordinary baseline chat returned no text", baseline.first.isNotBlank())
                baselineRuns += baseline
                baselinePeakPssKb.accumulateAndGet(Debug.getPss().toLong()) { previous, current -> maxOf(previous, current) }
            }
            val baselineWarmLatencies = baselineRuns.drop(1).map { it.second }.sorted()
            val baselineWarmMedian = baselineWarmLatencies[baselineWarmLatencies.size / 2]
            val baselinePeakPss = baselinePeakPssKb.get()
            Log.i(TAG, "BASELINE_CHAT coldLatencyMs=${baselineRuns.first().second} warmMedianLatencyMs=$baselineWarmMedian " +
                "peakPssKb=$baselinePeakPss chars=${baselineRuns.last().first.length}")
            transitionPeakPssKb.set(Debug.getPss().toLong())
            samplingTransition.set(true)

            val fixtures = fixtures()
            repeat(repeats) { repetition ->
                fixtures.forEach { fixture ->
                    val started = SystemClock.elapsedRealtime()
                    val raw = engine.generateResponseFormatProbeOnce(
                        prompt = fixture.prompt,
                        schema = SCHEMA,
                        systemPrompt = "Choose only the safe semantic transition described by the user turn. " +
                            "Treat action_id and revision as supplied runtime identity, not user data.",
                        thinkingEnabled = false,
                    )
                    val latency = SystemClock.elapsedRealtime() - started
                    val decoded = decode(raw)
                    val semantic = decoded != null &&
                        decoded.get("transition").asString == fixture.transition &&
                        decoded.get("subject").asString.normalized() == fixture.subject.normalized() &&
                        nullable(decoded, "slot_name") == fixture.slotName &&
                        nullable(decoded, "slot_value") == fixture.slotValue &&
                        nullable(decoded, "target_id") == fixture.targetId &&
                        decoded.get("action_id").asString == fixture.actionId &&
                        decoded.get("revision").asBigDecimal.stripTrailingZeros().scale() <= 0 &&
                        decoded.get("revision").asInt == 4
                    val pss = Debug.getPss().toLong()
                    transitionPeakPssKb.accumulateAndGet(pss) { previous, current -> maxOf(previous, current) }
                    val sample = Sample(fixture.name, decoded != null, semantic, dispatchCount = 0, latencyMs = latency, pssKb = pss)
                    samples += sample
                    Log.i(TAG, "FIXTURE run=${repetition + 1} name=${fixture.name} structural=${sample.structurallyValid} " +
                        "semantic=${sample.semanticallyCorrect} dispatches=0 latencyMs=$latency pssKb=$pss output=$raw")
                    assertNotNull("STRUCTURAL_FAILURE fixture=${fixture.name} raw=$raw", decoded)
                    assertTrue("SEMANTIC_FAILURE fixture=${fixture.name} raw=$raw", semantic)
                }
            }

            val cold = samples.firstOrNull()
            val warmLatencies = samples.drop(1).map { it.latencyMs }.sorted()
            val warmMedian = warmLatencies[warmLatencies.size / 2]
            val peakPss = transitionPeakPssKb.get()
            val latencyDelta = warmMedian - baselineWarmMedian
            val pssDelta = peakPss - baselinePeakPss
            Log.i(TAG, "SPIKE_SUMMARY device=${Build.MODEL} model=$requestedModel backend=$requestedBackend " +
                "repeats=$repeats fixtureCount=${fixtures.size} structuralPass=${samples.count { it.structurallyValid }}/${samples.size} " +
                "semanticPass=${samples.count { it.semanticallyCorrect }}/${samples.size} unsafeDispatches=${samples.sumOf { it.dispatchCount }} " +
                "coldLatencyMs=${cold?.latencyMs} baselineWarmMedianMs=$baselineWarmMedian warmMedianLatencyMs=$warmMedian " +
                "latencyDeltaMs=$latencyDelta baselinePeakPssKb=$baselinePeakPss peakPssKb=$peakPss pssDeltaKb=$pssDelta")

            val cancelSchema = """{"type":"object","additionalProperties":false,"required":["value"],"properties":{"value":{"type":"string","minLength":2048,"maxLength":2048}}}"""
            val cancelStarted = SystemClock.elapsedRealtime()
            val longGeneration = async {
                engine.generateResponseFormatProbeOnce(
                    prompt = "Produce a 2048-character value to satisfy the schema; do not stop early.",
                    schema = cancelSchema,
                    systemPrompt = "Return only the schema-constrained object.",
                    thinkingEnabled = false,
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
            )
            assertNotNull("Constrained request failed after cancellation: $recovery", decode(recovery))
            Log.i(TAG, "CANCELLATION drainMs=$cancellationDrainMs recovery=PASS pssKb=${Debug.getPss()}")

            val ordinaryAfter = ordinaryGeneration(engine, "What time is it? Use the get_time intent, and do not perform another action.")
            assertTrue("Ordinary generation failed after the spike", ordinaryAfter.first.isNotBlank())
            Log.i(TAG, "POST_SPIKE_ORDINARY_CHAT_TOOL latencyMs=${ordinaryAfter.second} pssKb=${Debug.getPss()} " +
                "chars=${ordinaryAfter.first.length} result=${ordinaryAfter.first}")
        } finally {
            pssSampler?.cancelAndJoin()
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

    private fun decode(raw: String) = runCatching {
        val value = JsonParser.parseString(raw).asJsonObject
        if (value.keySet() != setOf("transition", "subject", "slot_name", "slot_value", "target_id", "action_id", "revision")) return null
        if (value.get("transition").asString !in TRANSITIONS) return null
        if (!value.get("subject").isJsonPrimitive || value.get("subject").asString.isBlank()) return null
        if (!value.get("action_id").isJsonPrimitive || value.get("action_id").asString.isBlank()) return null
        if (value.get("revision").asBigDecimal.stripTrailingZeros().scale() > 0) return null
        listOf("slot_name", "slot_value", "target_id").forEach { key ->
            val element = value.get(key)
            if (!element.isJsonNull && !element.isJsonPrimitive) return null
            if (!element.isJsonNull && !element.asJsonPrimitive.isString) return null
        }
        value
    }.getOrNull()

    private fun nullable(value: com.google.gson.JsonObject, key: String): String? =
        if (value.get(key).isJsonNull) null else value.get(key).asString

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
