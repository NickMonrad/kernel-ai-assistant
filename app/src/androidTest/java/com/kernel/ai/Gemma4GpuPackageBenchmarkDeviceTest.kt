package com.kernel.ai

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.LiteRtInferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.StructuredOutputSpec
import com.kernel.ai.core.memory.worker.WORK_NAME_BACKFILL
import com.kernel.ai.core.memory.worker.WORK_NAME_NEXTCLOUD_SYNC
import com.kernel.ai.core.memory.worker.WORK_NAME_NEXTCLOUD_SYNC_PERIODIC
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Repeatable, test-only #1451 A/B runner. Run once per current or GPU package with identical
 * instrumentation arguments, app build, runtime, prompt corpus, device and thermal start state.
 * It never downloads, replaces, or deletes a model.
 */
class Gemma4GpuPackageBenchmarkDeviceTest {
    private lateinit var report: JSONObject
    private lateinit var caseResults: JSONArray
    private lateinit var reportFile: File
    private var peakPssMiB = 0.0
    private var peakRssHwmMiB = 0.0

    @Test
    fun benchmarkConfiguredPackage(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        val benchmarkArgumentNames = listOf(
            "model_path",
            "candidate",
            "source_commit",
            "expected_bytes",
            "expected_sha256",
        )
        assumeTrue(
            "Optional #1451 benchmark skipped; pass explicit model, source, and both artifact pins.",
            benchmarkArgumentNames.any { !args.getString(it).isNullOrBlank() },
        )
        val modelPathArgument = args.getString("model_path")
        val candidate = args.getString("candidate").orEmpty()
        val sourceCommit = args.getString("source_commit")
        val expectedSha256 = args.getString("expected_sha256")
        val expectedBytesArgument = args.getString("expected_bytes")
        val expectedBytes = expectedBytesArgument?.toLongOrNull()
        require(!modelPathArgument.isNullOrBlank()) { "Pass -e model_path=<absolute path>" }
        require(candidate.isNotBlank()) { "Pass -e candidate=<label>" }
        require(!sourceCommit.isNullOrBlank()) { "Pass -e source_commit=<full app git SHA>" }
        require(expectedBytes != null && expectedBytes > 0L) { "Pass -e expected_bytes=<positive byte count>" }
        require(!expectedSha256.isNullOrBlank()) { "Pass -e expected_sha256=<64-character SHA-256>" }
        require(BuildConfig.PR1451_BENCHMARK_ISOLATION) {
            "Build with -Ppr1451BenchmarkIsolation=true for #1451 startup isolation"
        }

        val modelFile = File(requireNotNull(modelPathArgument))
        val runId = (args.getString("run_id") ?: "${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9_-]"), "-")
        val outputDir = File(context.filesDir, "1451").apply { check(mkdirs() || isDirectory) }

        report = JSONObject()
            .put("schema", "jandal-gemma-package-benchmark/v1")
            .put("issue", 1451)
            .put("run_id", runId)
            .put("candidate", candidate)
            .put("source_commit", sourceCommit ?: JSONObject.NULL)
            .put("app_source_head", BuildConfig.GIT_SHA_FULL)
            .put("model_path", modelFile.absolutePath)
            .put("model_bytes", modelFile.takeIf(File::isFile)?.length() ?: JSONObject.NULL)
            .put("expected_bytes", expectedBytes ?: JSONObject.NULL)
            .put("expected_bytes_argument", expectedBytesArgument ?: JSONObject.NULL)
            .put("expected_sha256", expectedSha256 ?: JSONObject.NULL)
            .put("device_model", android.os.Build.MODEL)
            .put("device_build", android.os.Build.DISPLAY)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("runtime", "LiteRT-LM Android 0.17.1")
            .put("requested_backend", BackendType.GPU.name)
            .put("startup_work_isolation", BuildConfig.PR1451_BENCHMARK_ISOLATION)
            .put("startup_work_quiesced", false)
            .put("speculative_decoding_requested", false)
            .put("requested_max_tokens", REQUESTED_MAX_TOKENS)
            .put("native_benchmark_info_source", "LiteRtInferenceEngine logcat from Conversation.getBenchmarkInfo()")
            .put(
                "native_benchmark_info_fields",
                JSONArray(
                    listOf(
                        "initTimeInSecond",
                        "timeToFirstTokenInSecond",
                        "lastPrefillTokenCount",
                        "lastPrefillTokensPerSecond",
                        "lastDecodeTokenCount",
                        "lastDecodeTokensPerSecond",
                    ),
                ),
            )
            .put(
                "native_metrics_note",
                "Native initTimeInSecond is the native initialization phase sum, distinct from app wall-clock engine initialization. Native TTFT and prefill/decode token counts and rates are emitted after completed streamed generations; capture LiteRtInferenceEngine logcat. Callback chunks are not model tokens; visible-character throughput remains separate. BenchmarkInfo does not include KV allocation/peak or GPU delegate-utilization; this benchmark does not collect embedded package capability/template metadata.",
            )
            .put("status", "preflight_pending")
        caseResults = JSONArray()
        reportFile = File(outputDir, "$runId.json")
        peakPssMiB = 0.0
        peakRssHwmMiB = 0.0

        saveReport()

        var artifactPreflight: Pr1451ArtifactCheck? = null
        var engineForCleanup: LiteRtInferenceEngine? = null
        val baseConfig = ModelConfig(
            modelPath = modelFile.absolutePath,
            backendType = BackendType.GPU,
            maxTokens = REQUESTED_MAX_TOKENS,
            systemPrompt = TEST_SYSTEM_PROMPT,
            thinkingEnabled = false,
            speculativeDecodingEnabled = false,
        )

        try {
            val reloadOrderGate = Pr1451ReloadOrderGate()

            awaitPr1451StartupWorkQuiescence(context)
            report.put("startup_work_quiesced", true)
            saveReport()
            val verifiedRuntime = Pr1451ArtifactVerifier.verifyBeforeRuntime(
                file = modelFile,
                appSourceHead = BuildConfig.GIT_SHA_FULL,
                suppliedSourceCommit = sourceCommit,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                recordPreflight = { check ->
                    artifactPreflight = check
                    recordPreflight(check)
                    saveReport()
                },
                createRuntime = { LiteRtInferenceEngine(context, HardwareProfileDetector(context)) },
            )
            val engine = verifiedRuntime.runtime
            engineForCleanup = engine
            report.put("status", "running")
            report.put("memory_before_init", memorySnapshot())
            saveReport()

            val loadStart = SystemClock.elapsedRealtimeNanos()
            withTimeout(INIT_TIMEOUT_MS) { engine.initialize(baseConfig) }
            report.put("first_engine_init_ms", elapsedMs(loadStart))
            report.put("first_init_scope", "first initialize in this process; OS/page cache is not flushed")
            report.put("loaded_model_path", engine.loadedModelPath)
            report.put("resolved_max_tokens", engine.resolvedMaxTokens.value)
            report.put("backend_after_first_init", engine.activeBackend.value?.name)
            report.put("gpu_backend_confirmed_after_first_init", engine.activeBackend.value == BackendType.GPU)
            assertGpuBackend(engine)
            report.put("memory_after_cold_init", memorySnapshot())
            saveReport()

            val normal = captureGeneration(
                engine,
                "normal_conversation",
                "In one sentence, explain why a cup of tea cools when left on a bench.",
            )
            assertTrue("Normal conversation returned no visible response", normal.visibleText.isNotBlank())

            withTimeout(RESET_TIMEOUT_MS) { engine.resetConversation() }
            val firstTurn = captureGeneration(
                engine,
                "multi_turn_context_setup",
                "Remember this exact identifier for this conversation only: JANDAL-1451-ORBIT. Reply only ACK.",
            )
            assertTrue("Context setup returned no response", firstTurn.visibleText.isNotBlank())
            val secondTurn = captureGeneration(
                engine,
                "multi_turn_context_recall",
                "What exact identifier did I ask you to remember? Reply with only the identifier.",
            )
            assertTrue("Conversation did not retain the fixed identifier", "JANDAL-1451-ORBIT" in secondTurn.visibleText)

            withTimeout(RESET_TIMEOUT_MS) {
                engine.reconfigureConversation(baseConfig.copy(thinkingEnabled = true))
            }
            val thinking = captureGeneration(
                engine,
                "thinking_mode",
                "Briefly compare rain and snow, then give one concise conclusion.",
            )
            assertTrue("Thinking-mode response was empty", thinking.visibleText.isNotBlank())
            assertTrue("No thinking-channel output was observed", thinking.thinkingChars > 0)
            report.put("thinking_channel_observed", true)
            saveReport()

            val fixtureTools = Gemma4BenchmarkToolSet()
            withTimeout(RESET_TIMEOUT_MS) {
                engine.reconfigureConversation(baseConfig.copy(toolProvider = tool(fixtureTools)))
            }
            fixtureTools.clearCalls()
            val directStart = fixtureTools.calls().size
            val directTool = captureGeneration(
                engine,
                "direct_tool_call",
                "Use fixture_lookup with key '1451-direct-proof'. Do not guess. State the returned value exactly.",
            )
            val directCalls = fixtureTools.calls().drop(directStart)
            assertTrue("Direct fixture tool was not executed: $directCalls", "fixture_lookup" in directCalls)
            assertTrue("Direct tool result was not surfaced", directTool.visibleText.contains("fixture-value:1451-direct-proof"))

            fixtureTools.clearCalls()
            val skillStart = fixtureTools.calls().size
            captureGeneration(
                engine,
                "load_skill_then_execute",
                "First call load_skill for 'benchmark_fixture'. Then follow its returned instructions exactly and call fixture_lookup. Do not answer from memory.",
            )
            val skillCalls = fixtureTools.calls().drop(skillStart)
            val loadSkillIndex = skillCalls.indexOf("load_skill")
            val fixtureIndex = skillCalls.indexOf("fixture_lookup")
            assertTrue("load_skill was not executed: $skillCalls", loadSkillIndex >= 0)
            assertTrue("fixture_lookup did not follow load_skill: $skillCalls", fixtureIndex > loadSkillIndex)

            fixtureTools.clearCalls()
            val callsPerRepeatTurn = JSONArray()
            repeat(2) { index ->
                val beforeCalls = fixtureTools.calls().count { it == "fixture_lookup" }
                captureGeneration(
                    engine,
                    "repeated_tool_call_${index + 1}",
                    "Call fixture_lookup with key '1451-repeat-$index' and report the returned value.",
                )
                val afterCalls = fixtureTools.calls().count { it == "fixture_lookup" }
                assertTrue(
                    "Tool call missing on turn ${index + 1}: ${fixtureTools.calls()}",
                    afterCalls > beforeCalls,
                )
                callsPerRepeatTurn.put(afterCalls - beforeCalls)
            }
            report.put("tool_call_order", JSONArray(skillCalls))
            report.put("repeated_tool_calls_per_turn", callsPerRepeatTurn)
            saveReport()

            withTimeout(RESET_TIMEOUT_MS) { engine.reconfigureConversation(baseConfig) }
            val structuredSpec = StructuredOutputSpec(
                toolName = "emit_schema_property_probe",
                toolDescription = "Return the three requested string values.",
                jsonSchema = """
                    {
                      "type":"object",
                      "required":["type","required","nullable"],
                      "properties":{
                        "type":{"type":"string"},
                        "required":{"type":"string"},
                        "nullable":{"type":"string"}
                      }
                    }
                """.trimIndent(),
            )
            val structuredBackend = engine.activeBackend.value
            report.put("backend_before_structured_schema_case", structuredBackend?.name)
            Pr1451ReloadEvidence.requireGpuBackend(structuredBackend?.name)
            val structuredStart = SystemClock.elapsedRealtimeNanos()
            val structuredJson = withTimeout(GENERATION_TIMEOUT_MS) {
                engine.generateStructuredOnce(
                    prompt = "Return type='schema-type-proof', required='schema-required-proof', nullable='schema-nullable-proof'.",
                    spec = structuredSpec,
                    thinkingEnabled = false,
                )
            }
            val structuredObject = JSONObject(structuredJson)
            listOf("type", "required", "nullable").forEach { key ->
                assertTrue("Structured response omitted schema property '$key': $structuredJson", structuredObject.has(key))
                assertTrue("Schema property '$key' was empty", structuredObject.optString(key).isNotBlank())
            }
            caseResults.put(
                JSONObject()
                    .put("case", "structured_schema_property_names")
                    .put("duration_ms", elapsedMs(structuredStart))
                    .put("response_chars", structuredJson.length)
                    .put("required_property_names", JSONArray(listOf("type", "required", "nullable"))),
            )
            report.put("cases", caseResults)
            report.put("memory_after_structured_output", memorySnapshot())
            saveReport()

            val cancellation = captureCancellation(engine)
            report.put("cancellation", cancellation)
            saveReport()

            withTimeout(RESET_TIMEOUT_MS) { engine.resetConversation() }
            val resetReuse = captureGeneration(
                engine,
                "reset_then_reuse",
                "Reply with only the word READY.",
            )
            assertTrue("Engine did not generate after reset/reuse", resetReuse.visibleText.isNotBlank())

            val backgroundResume = resumeApplication(context, instrumentation, engine)
            report.put("background_resume", backgroundResume)
            saveReport()
            val postResumeSameSession = captureGeneration(
                engine,
                "post_resume_same_initial_gpu_session",
                "Reply with only the word RESUMED.",
            )
            assertTrue("Engine did not generate after same-session background/resume", postResumeSameSession.visibleText.isNotBlank())

            val thermal = captureThermalRun(context, engine)
            report.put("thermal_run", thermal)
            reloadOrderGate.markInitialGpuMeasurementsComplete()

            val warmReload = captureDiagnosticReload(
                context = context,
                engine = engine,
                baseConfig = baseConfig,
                reloadOrderGate = reloadOrderGate,
                reportPrefix = "warm_reload",
                timingField = "warm_engine_reload_ms",
                legacyReloadPrefix = "warm_reload",
            )

            val backgroundResumeBeforeReload = resumeApplication(context, instrumentation, engine)
            report.put("background_resume_before_diagnostic_reload", backgroundResumeBeforeReload)
            saveReport()
            val backgroundReload = captureDiagnosticReload(
                context = context,
                engine = engine,
                baseConfig = baseConfig,
                reloadOrderGate = reloadOrderGate,
                reportPrefix = "background_reload",
                timingField = "background_resume_engine_reload_ms",
                legacyReloadPrefix = "background_resume_reload",
            )
            report.put("memory_peak", JSONObject().put("pss_mib", peakPssMiB).put("rss_hwm_mib", peakRssHwmMiB))
            saveReport()

            val postRunCheck = Pr1451ArtifactVerifier.verifyUnchangedAfterRun(
                file = modelFile,
                appSourceHead = BuildConfig.GIT_SHA_FULL,
                suppliedSourceCommit = sourceCommit,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                preflight = requireNotNull(artifactPreflight),
            )
            recordPostRun(postRunCheck)
            val runSummary = Pr1451ReloadEvidence.summarize(
                armStatus = if (postRunCheck.passed) "passed" else "failed",
                warmReload = warmReload,
                backgroundReload = backgroundReload,
            )
            report.put("reload_gpu_stability_status", runSummary.reloadGpuStabilityStatus)
            report.put("status", runSummary.armStatus)
            saveReport()
            assertTrue(
                "Model artifact pins changed during benchmark: ${postRunCheck.artifactCheck.failureMessage()}",
                postRunCheck.passed,
            )
        } catch (failure: Throwable) {
            report.put("status", "failed")
            report.put("failure_type", failure::class.java.name)
            report.put("failure_message", failure.message ?: "")
            Log.e(TAG, "PR1451 benchmark failed: ${failure.message}", failure)
            throw failure
        } finally {
            engineForCleanup?.let { engine ->
                runCatching { withTimeout(SHUTDOWN_TIMEOUT_MS) { engine.shutdown() } }
                    .onFailure { report.put("shutdown_failure", it.message ?: it::class.java.name) }
            }
            if (artifactPreflight?.passed == true && !report.has("model_sha256_after_run")) {
                runCatching {
                    Pr1451ArtifactVerifier.verifyUnchangedAfterRun(
                        file = modelFile,
                        appSourceHead = BuildConfig.GIT_SHA_FULL,
                        suppliedSourceCommit = sourceCommit,
                        expectedBytes = expectedBytes,
                        expectedSha256 = expectedSha256,
                        preflight = requireNotNull(artifactPreflight),
                    )
                }.onSuccess(::recordPostRun)
                    .onFailure {
                        report.put("post_run_artifact_failure", it.message ?: it::class.java.name)
                        report.put("status", "failed")
                    }
            }
            report.put("memory_peak", JSONObject().put("pss_mib", peakPssMiB).put("rss_hwm_mib", peakRssHwmMiB))
            saveReport()
            Log.i(TAG, "PR1451_RESULT candidate=$candidate run=$runId status=${report.optString("status")} report=${reportFile.absolutePath}")
        }
    }

    private suspend fun captureGeneration(
        engine: LiteRtInferenceEngine,
        caseName: String,
        prompt: String,
    ): GenerationCapture {
        val activeBackend = engine.activeBackend.value
        report.put("backend_before_$caseName", activeBackend?.name)
        Pr1451ReloadEvidence.requireGpuBackend(activeBackend?.name)
        val started = SystemClock.elapsedRealtimeNanos()
        var ttftMs: Double? = null
        var visibleChunks = 0
        var visibleChars = 0
        var thinkingChunks = 0
        var thinkingChars = 0
        val visibleText = StringBuilder()
        withTimeout(GENERATION_TIMEOUT_MS) {
            engine.generate(prompt).collect { result ->
                when (result) {
                    is GenerationResult.Token -> {
                        if (ttftMs == null) ttftMs = elapsedMs(started)
                        visibleChunks++
                        visibleChars += result.text.length
                        visibleText.append(result.text)
                    }
                    is GenerationResult.Thinking -> {
                        thinkingChunks++
                        thinkingChars += result.text.length
                    }
                    is GenerationResult.Complete -> Unit
                    is GenerationResult.Error -> error("$caseName generation failed: ${result.message}")
                }
            }
        }
        val durationMs = elapsedMs(started)
        val sample = memorySnapshot()
        val row = JSONObject()
            .put("case", caseName)
            .put("duration_ms", durationMs)
            .put("ttft_first_visible_callback_ms", ttftMs ?: JSONObject.NULL)
            .put("visible_callback_chunks_not_tokens", visibleChunks)
            .put("visible_chars", visibleChars)
            .put("visible_chars_per_second", if (durationMs > 0) visibleChars * 1000.0 / durationMs else 0.0)
            .put("thinking_callback_chunks", thinkingChunks)
            .put("thinking_chars", thinkingChars)
            .put("memory_after", sample)
            .put("active_backend", activeBackend?.name)
        caseResults.put(row)
        report.put("cases", caseResults)
        saveReport()
        Log.i(TAG, "PR1451_CASE ${row}")
        return GenerationCapture(row, visibleText.toString(), thinkingChars)
    }

    private suspend fun captureCancellation(engine: LiteRtInferenceEngine): JSONObject = coroutineScope {
        val activeBackend = engine.activeBackend.value
        report.put("backend_before_cancellation", activeBackend?.name)
        Pr1451ReloadEvidence.requireGpuBackend(activeBackend?.name)
        val chunks = Collections.synchronizedList(mutableListOf<String>())
        val completed = async(Dispatchers.Default) {
            engine.generate(
                "Write a detailed 1000-word explanation of how rain forms, with many distinct sentences. Continue until cancelled.",
            ).collect { event ->
                if (event is GenerationResult.Token) chunks += event.text
            }
        }
        try {
            withTimeout(GENERATION_TIMEOUT_MS) { engine.isGenerating.first { it } }
            delay(CANCELLATION_WAIT_MS)
            assertFalse("Long generation finished before cancellation was requested", completed.isCompleted)
            val cancelAt = SystemClock.elapsedRealtimeNanos()
            engine.cancelGeneration()
            withTimeout(CANCELLATION_TIMEOUT_MS) { completed.await() }
            withTimeout(CANCELLATION_TIMEOUT_MS) { engine.isGenerating.first { !it } }
            val row = JSONObject()
                .put("cancel_requested", true)
                .put("cancel_to_flow_completion_ms", elapsedMs(cancelAt))
                .put("visible_callback_chunks_not_tokens", chunks.size)
                .put("visible_chars", chunks.sumOf(String::length))
                .put("active_backend", activeBackend?.name)
            caseResults.put(JSONObject().put("case", "cancellation").put("evidence", row))
            report.put("cases", caseResults)
            Log.i(TAG, "PR1451_CANCEL ${row}")
            row
        } finally {
            if (!completed.isCompleted) {
                engine.cancelGeneration()
                completed.cancelAndJoin()
            }
        }
    }

    private suspend fun captureThermalRun(context: Context, engine: LiteRtInferenceEngine): JSONObject {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val statuses = Collections.synchronizedList(mutableListOf<Int>())
        val executor = Executors.newSingleThreadExecutor()
        val listener = PowerManager.OnThermalStatusChangedListener { statuses += it }
        statuses += powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(executor, listener)
        try {
            repeat(THERMAL_GENERATIONS) { index ->
                captureGeneration(
                    engine,
                    "thermal_repeat_${index + 1}",
                    "In two concise sentences, describe one practical way to conserve household water.",
                )
                statuses += powerManager.currentThermalStatus
            }
        } finally {
            powerManager.removeThermalStatusListener(listener)
            executor.shutdownNow()
        }
        val statusSnapshot = synchronized(statuses) { statuses.toList() }
        return JSONObject()
            .put("generation_count", THERMAL_GENERATIONS)
            .put("thermal_status_samples", JSONArray(statusSnapshot))
            .put("thermal_status_max", statusSnapshot.maxOrNull() ?: PowerManager.THERMAL_STATUS_NONE)
            .put("active_backend", engine.activeBackend.value?.name)
    }

    private fun assertGpuBackend(engine: LiteRtInferenceEngine) {
        Pr1451ReloadEvidence.requireGpuBackend(engine.activeBackend.value?.name)
    }

    private fun resumeApplication(
        context: Context,
        instrumentation: Instrumentation,
        engine: LiteRtInferenceEngine,
    ): JSONObject {
        val uiDevice = UiDevice.getInstance(instrumentation)
        assertTrue("Could not send app to background", uiDevice.pressHome())
        SystemClock.sleep(BACKGROUND_HOLD_MS)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Debug app has no launcher activity", launchIntent)
        context.startActivity(requireNotNull(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        uiDevice.waitForIdle(UI_IDLE_TIMEOUT_MS)
        val powerManager = context.getSystemService(PowerManager::class.java)
        assertTrue("Screen did not return to interactive state", powerManager.isInteractive)
        return JSONObject()
            .put("home_pressed", true)
            .put("resumed_package", context.packageName)
            .put("screen_interactive", powerManager.isInteractive)
            .put("engine_ready_before_reload", engine.isReady.value)
            .put("backend_before_reload", engine.activeBackend.value?.name ?: JSONObject.NULL)
    }

    private suspend fun captureDiagnosticReload(
        context: Context,
        engine: LiteRtInferenceEngine,
        baseConfig: ModelConfig,
        reloadOrderGate: Pr1451ReloadOrderGate,
        reportPrefix: String,
        timingField: String,
        legacyReloadPrefix: String,
    ): Pr1451ReloadObservation {
        reloadOrderGate.requireDiagnosticReloadAllowed()
        var initStartedNanos: Long? = null
        var initialized = false
        var failureMessage: String? = null
        try {
            val shutdownCompleted = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                engine.shutdown()
                true
            } ?: false
            check(shutdownCompleted) { "Timed out shutting down before $reportPrefix" }

            initStartedNanos = SystemClock.elapsedRealtimeNanos()
            val initializationCompleted = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                engine.initialize(baseConfig)
                true
            } ?: false
            check(initializationCompleted) { "Timed out initializing $reportPrefix" }
            initialized = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failureMessage = "${failure::class.java.simpleName}: ${failure.message.orEmpty()}"
        }

        var memorySnapshotFailure: String? = null
        val memoryAfterReload = try {
            memorySnapshot()
        } catch (failure: Exception) {
            memorySnapshotFailure = "${failure::class.java.simpleName}: ${failure.message.orEmpty()}"
            JSONObject()
        }
        var availableMemoryFailure: String? = null
        val availableMemoryMiB = try {
            availableSystemMemoryMiB(context)
        } catch (failure: Exception) {
            availableMemoryFailure = "${failure::class.java.simpleName}: ${failure.message.orEmpty()}"
            null
        }
        val observation = Pr1451ReloadEvidence.assess(
            backend = if (initialized) engine.activeBackend.value?.name else null,
            availableSystemMemoryMiB = availableMemoryMiB,
            processPssMiB = (memoryAfterReload.opt("pss_mib") as? Number)?.toDouble(),
            processRssMiB = (memoryAfterReload.opt("rss_mib") as? Number)?.toDouble(),
            failureMessage = failureMessage,
        )
        report.put(timingField, initStartedNanos?.let(::elapsedMs) ?: JSONObject.NULL)
        report.put("backend_after_$legacyReloadPrefix", observation.backend ?: JSONObject.NULL)
        report.put("gpu_backend_confirmed_after_$legacyReloadPrefix", observation.gpuRetained == true)
        report.put("memory_after_$legacyReloadPrefix", memoryAfterReload)
        observation.reportFields(reportPrefix).forEach { (key, value) ->
            report.put(key, value ?: JSONObject.NULL)
        }
        report.put("${reportPrefix}_memory_snapshot_failure", memorySnapshotFailure ?: JSONObject.NULL)
        report.put("${reportPrefix}_available_memory_read_failure", availableMemoryFailure ?: JSONObject.NULL)
        saveReport()
        Log.i(
            TAG,
            "PR1451_RELOAD phase=$reportPrefix backend=${observation.backend ?: "unknown"} " +
                "available_mib=${observation.availableSystemMemoryMiB} " +
                "gpu_headroom_mib=${observation.gpuMemoryHeadroomMiB} " +
                "pss_mib=${observation.processPssMiB} rss_mib=${observation.processRssMiB} " +
                "gpu_retained=${observation.gpuRetained} outcome=${observation.outcome} " +
                "failure=${observation.failureMessage}",
        )
        return observation
    }

    private fun availableSystemMemoryMiB(context: Context): Long? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024L * 1024L)
    }

    private suspend fun awaitPr1451StartupWorkQuiescence(context: Context) {
        check(BuildConfig.PR1451_BENCHMARK_ISOLATION) {
            "PR1451 startup-work isolation must be enabled before quiescence checks"
        }
        val workManager = WorkManager.getInstance(context)
        val uniqueWorkNames = listOf(
            WORK_NAME_BACKFILL,
            WORK_NAME_NEXTCLOUD_SYNC,
            WORK_NAME_NEXTCLOUD_SYNC_PERIODIC,
        )
        withContext(Dispatchers.IO) {
            val deadline = SystemClock.elapsedRealtime() + STARTUP_WORK_QUIESCENCE_TIMEOUT_MS
            uniqueWorkNames.forEach { name ->
                workManager.cancelUniqueWork(name).result.get(WORK_MANAGER_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            while (true) {
                val unfinished = uniqueWorkNames.flatMap { name ->
                    workManager.getWorkInfosForUniqueWork(name)
                        .get(WORK_MANAGER_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .filterNot { workInfo ->
                            workInfo.state == WorkInfo.State.SUCCEEDED ||
                                workInfo.state == WorkInfo.State.FAILED ||
                                workInfo.state == WorkInfo.State.CANCELLED
                        }
                        .map { "$name:${it.state}" }
                }
                if (unfinished.isEmpty()) return@withContext
                check(SystemClock.elapsedRealtime() < deadline) {
                    "PR1451 startup work did not quiesce: ${unfinished.joinToString()}"
                }
                Thread.sleep(STARTUP_WORK_POLL_INTERVAL_MS)
            }
        }
    }

    private fun recordPreflight(check: Pr1451ArtifactCheck) {
        report.put("app_source_head", check.appSourceHead)
        report.put("app_source_head_valid", check.appSourceHeadValid)
        report.put("source_commit", check.suppliedSourceCommit ?: JSONObject.NULL)
        report.put("source_commit_valid", check.suppliedSourceCommitValid)
        report.put("source_commit_matches_app_head", check.sourceCommitMatches)
        report.put("expected_bytes", check.expectedBytes ?: JSONObject.NULL)
        report.put("expected_bytes_valid", check.expectedBytesValid)
        report.put("model_bytes_before_init", check.actualBytes ?: JSONObject.NULL)
        report.put("model_size_matches_expected", check.sizeMatches)
        report.put("expected_sha256", check.expectedSha256 ?: JSONObject.NULL)
        report.put("expected_sha256_valid", check.expectedSha256Valid)
        report.put("model_sha256_before_init", check.actualSha256 ?: JSONObject.NULL)
        report.put("sha256_matches_expected_before_init", check.sha256Matches)
        report.put("artifact_preflight_passed", check.passed)
        report.put("artifact_preflight_failure", if (check.passed) JSONObject.NULL else check.failureMessage())
    }

    private fun recordPostRun(check: Pr1451PostRunCheck) {
        val artifact = check.artifactCheck
        report.put("model_bytes_after_run", artifact.actualBytes ?: JSONObject.NULL)
        report.put("model_sha256_after_run", artifact.actualSha256 ?: JSONObject.NULL)
        report.put("model_sha256", artifact.actualSha256 ?: JSONObject.NULL)
        report.put("sha256_matches_expected", artifact.sha256Matches)
        report.put("model_unchanged_since_preflight", check.unchangedSincePreflight)
        report.put("artifact_post_run_passed", check.passed)
        report.put("artifact_post_run_failure", if (check.passed) JSONObject.NULL else artifact.failureMessage())
        if (!check.passed) report.put("status", "failed")
    }

    private fun memorySnapshot(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val pss = info.totalPss / 1024.0
        val (rss, rssHwm) = processRssMiB()
        peakPssMiB = maxOf(peakPssMiB, pss)
        peakRssHwmMiB = maxOf(peakRssHwmMiB, rssHwm ?: 0.0)
        return JSONObject()
            .put("pss_mib", pss)
            .put("rss_mib", rss ?: JSONObject.NULL)
            .put("rss_high_water_mib", rssHwm ?: JSONObject.NULL)
    }

    private fun processRssMiB(): Pair<Double?, Double?> {
        val values = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.mapNotNull { line ->
                    when {
                        line.startsWith("VmRSS:") -> "rss" to line.trim().split(Regex("\\s+"))[1].toLong() / 1024.0
                        line.startsWith("VmHWM:") -> "hwm" to line.trim().split(Regex("\\s+"))[1].toLong() / 1024.0
                        else -> null
                    }
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        return values["rss"] to values["hwm"]
    }

    private fun saveReport() {
        report.put("cases", caseResults)
        reportFile.writeText(report.toString(2))
    }

    private fun elapsedMs(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0

    private data class GenerationCapture(
        val evidence: JSONObject,
        val visibleText: String,
        val thinkingChars: Int,
    )

    private companion object {
        const val TAG = "Gemma4Package1451"
        const val REQUESTED_MAX_TOKENS = 2048
        const val INIT_TIMEOUT_MS = 180_000L
        const val GENERATION_TIMEOUT_MS = 90_000L
        const val RESET_TIMEOUT_MS = 30_000L
        const val SHUTDOWN_TIMEOUT_MS = 30_000L
        const val CANCELLATION_TIMEOUT_MS = 30_000L
        const val CANCELLATION_WAIT_MS = 500L
        const val BACKGROUND_HOLD_MS = 1_500L
        const val UI_IDLE_TIMEOUT_MS = 10_000L
        const val THERMAL_GENERATIONS = 3
        const val STARTUP_WORK_QUIESCENCE_TIMEOUT_MS = 30_000L
        const val WORK_MANAGER_OPERATION_TIMEOUT_MS = 5_000L
        const val WORK_MANAGER_QUERY_TIMEOUT_MS = 5_000L
        const val STARTUP_WORK_POLL_INTERVAL_MS = 100L
        const val TEST_SYSTEM_PROMPT = "You are a concise on-device assistant. Follow tool instructions and answer directly."
    }
}

/** Deterministic test-only tools; no Android side effects and no production tool wiring changes. */
class Gemma4BenchmarkToolSet : ToolSet {
    private val invocations = Collections.synchronizedList(mutableListOf<String>())

    fun calls(): List<String> = synchronized(invocations) { invocations.toList() }

    fun clearCalls() = invocations.clear()

    @Tool(description = "Return the benchmark fixture value for a requested key. Use this tool instead of guessing.")
    fun fixtureLookup(@ToolParam(description = "The exact fixture lookup key.") key: String): Map<String, String> {
        invocations += "fixture_lookup"
        return mapOf("value" to "fixture-value:$key")
    }

    @Tool(description = "Load instructions for the benchmark_fixture skill when the user asks you to do so.")
    fun loadSkill(@ToolParam(description = "The skill name to load.") skillName: String): Map<String, String> {
        invocations += "load_skill"
        return mapOf(
            "instructions" to "For skill $skillName, call fixture_lookup with key 1451-loaded-skill-proof, then report the returned value exactly.",
        )
    }
}
