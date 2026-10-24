package com.kernel.ai.debug.llamaprobe

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.BuildConfig
import com.kernel.ai.KernelAIApplication
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.LiteRtInferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.skills.mealplan.MealPlanJsonParser
import com.kernel.ai.core.skills.mealplan.MealPlanQuantityValidator
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaMealPlanComparisonTest {
    private val parser = MealPlanJsonParser()
    private val quantityValidator = MealPlanQuantityValidator()
    private val recipeValidator = RecipeProbeValidator(parser, quantityValidator)

    @Test
    fun probePackageIsIsolatedFromPersistedAppJobs() {
        assertProbeIsolation(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun openClRuntimePreflightFindsAdreno740() {
        assumeTrue("invoke only with llamaProbeOpencl", BuildConfig.IS_OPENCL_PROBE)
        val info = JSONObject(LlamaCppSession.openClRuntimeInfo())
        Log.i(TAG, "opencl_runtime_preflight=$info")
        assertEquals("enumerated", info.getString("status"))
        val platforms = info.getJSONArray("platforms")
        val adreno = (0 until platforms.length())
            .asSequence()
            .flatMap { platforms.getJSONObject(it).getJSONArray("devices").let { devices ->
                (0 until devices.length()).map { deviceIndex -> devices.getJSONObject(deviceIndex) }
            } }
            .firstOrNull { it.getString("name").contains("Adreno", ignoreCase = true) }
        assertTrue("OpenCL GPU device missing: $info", adreno != null)
        assertTrue("Adreno 740 missing: $adreno", adreno!!.getString("version").contains("Adreno(TM) 740"))
        assertTrue("OpenCL GPU not available: $adreno", adreno.getBoolean("available"))
        assertTrue("OpenCL compiler unavailable: $adreno", adreno.getBoolean("compiler_available"))
        assertTrue(
            "llama.cpp OpenCL backend was not registered: $info",
            info.getJSONArray("llama_backend_registry").length() > 0,
        )
        val registries = info.getJSONArray("llama_backend_registry")
        val registeredOpenClGpu = (0 until registries.length())
            .asSequence()
            .filter {
                registries.getJSONObject(it).getString("name").equals("OpenCL", ignoreCase = true)
            }
            .flatMap { registryIndex ->
                val devices = registries.getJSONObject(registryIndex).getJSONArray("devices")
                (0 until devices.length()).map { deviceIndex -> devices.getJSONObject(deviceIndex) }
            }
            .firstOrNull { it.getInt("type") == 1 || it.getInt("type") == 2 }
        assertTrue("llama.cpp OpenCL registry did not expose a GPU: $info", registeredOpenClGpu != null)
    }

    @Test
    fun openClMemoryAndThermalGatesAreScopedToOpenCl() {
        val lowMemory = JSONObject()
            .put("avail_mem_bytes", MIN_START_AVAILABLE_MEMORY_BYTES - 1)
            .put("thermal_status", MAX_THERMAL_STATUS)
        requireOpenClStartGates(lowMemory, isOpenClProbe = false, stage = "CPU baseline")
        assertTrue(
            "OpenCL pairing gate did not reject below-threshold memory",
            runCatching {
                requireOpenClStartGates(lowMemory, isOpenClProbe = true, stage = "comparison")
            }.exceptionOrNull() is IllegalStateException,
        )

        val highThermal = JSONObject()
            .put("avail_mem_bytes", MIN_START_AVAILABLE_MEMORY_BYTES)
            .put("thermal_status", MAX_THERMAL_STATUS + 1)
        assertTrue(
            "OpenCL pairing gate did not reject high thermal status",
            runCatching {
                requireOpenClStartGates(highThermal, isOpenClProbe = true, stage = "comparison")
            }.exceptionOrNull() is IllegalStateException,
        )
        requireOpenClThermalGate(highThermal, isOpenClProbe = false, stage = "CPU baseline")
        assertTrue(
            "OpenCL post-stage gate did not reject high thermal status",
            runCatching {
                requireOpenClThermalGate(highThermal, isOpenClProbe = true, stage = "plan")
            }.exceptionOrNull() is IllegalStateException,
        )
    }

    @Test
    fun pinnedModelVerifierRejectsSizeAndDigestMismatch() {
        val bytes = "pinned-gguf-fixture".toByteArray()
        val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val file = File.createTempFile(
            "lfm2-probe",
            ".gguf",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        )
        try {
            file.writeBytes(bytes)
            verifyPinnedModel(file, bytes.size.toLong(), expectedSha, "fixture")
            assertTrue(
                "Pinned model verifier accepted the wrong size",
                runCatching {
                    verifyPinnedModel(file, bytes.size.toLong() + 1, expectedSha, "fixture")
                }.exceptionOrNull() is IllegalArgumentException,
            )
            assertTrue(
                "Pinned model verifier accepted the wrong digest",
                runCatching {
                    verifyPinnedModel(file, bytes.size.toLong(), "0".repeat(64), "fixture")
                }.exceptionOrNull() is IllegalArgumentException,
            )
        } finally {
            file.delete()
        }
    }

    private fun requireOpenClStartGates(snapshot: JSONObject, isOpenClProbe: Boolean, stage: String) {
        if (!isOpenClProbe) return
        check(snapshot.getLong("avail_mem_bytes") >= MIN_START_AVAILABLE_MEMORY_BYTES) {
            "Available memory is below the bounded OpenCL $stage gate"
        }
        check(snapshot.getInt("thermal_status") <= MAX_THERMAL_STATUS) {
            "Thermal status is above the bounded OpenCL $stage gate"
        }
    }

    private fun requireOpenClThermalGate(snapshot: JSONObject, isOpenClProbe: Boolean, stage: String) {
        if (!isOpenClProbe) return
        check(snapshot.getInt("thermal_status") <= MAX_THERMAL_STATUS) {
            "Thermal status exceeded the bounded OpenCL gate after $stage"
        }
    }

    private suspend fun loadLfmSession(
        model: File,
        contextSize: Int = LLAMA_CONTEXT_TOKENS,
        draftModel: File? = null,
    ): LlamaCppSession {
        if (BuildConfig.IS_OPENCL_PROBE) verifyPinnedLfmModel(model)
        return LlamaCppSession.load(model, contextSize = contextSize, draftModel = draftModel)
    }

    @Test
    fun boundedLfmFirstTokenStream() = runBlocking {
        assumeTrue(
            "invoke only with -e llamaFirstTokenOnly true",
            InstrumentationRegistry.getArguments().getString("llamaFirstTokenOnly") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        stage("probe_isolation") { assertProbeIsolation(context) }
        val arguments = InstrumentationRegistry.getArguments()
        val llamaFile = llamaModelFile(context, arguments.getString("llamaModelPath"))
        require(llamaFile.isFile) { "LFM2.5 GGUF file is missing" }
        val preflight = memorySnapshot(context)
        Log.i(TAG, "first_token_preflight=$preflight")
        check(!preflight.getBoolean("low_memory")) {
            "Android reports lowMemory before the bounded first-token test"
        }
        requireOpenClStartGates(preflight, BuildConfig.IS_OPENCL_PROBE, "first-token")
        val session = stage("llama_first_token_load") {
            loadLfmSession(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
        }
        var nativeCallTimedOut = false
        try {
            val started = System.nanoTime()
            var streamChunks = 0
            var streamTimeToFirstChunkMs: Long? = null
            val generation = try {
                withTimeout(FIRST_TOKEN_TIMEOUT_MS) {
                    session.generate(
                        prompt = MealPlanProbePrompts.PLAN_USER,
                        systemPrompt = MealPlanProbePrompts.PLAN_SYSTEM,
                        schemaJson = MealPlanProbeSchemas.plan(),
                        maxTokens = 1,
                        reportProgress = true,
                        onText = {
                            if (streamChunks == 0) streamTimeToFirstChunkMs = elapsedMs(started)
                            streamChunks++
                        },
                    )
                }
            } catch (incomplete: IllegalStateException) {
                if (incomplete.message != "llama.cpp generation reached maxTokens before end-of-generation") {
                    throw incomplete
                }
                null
            } catch (timeout: TimeoutCancellationException) {
                nativeCallTimedOut = true
                Log.e(TAG, "first_token_stream event=timeout elapsed_ms=${elapsedMs(started)}")
                throw AssertionError(
                    "Bounded first-token test exceeded ${FIRST_TOKEN_TIMEOUT_MS}ms; inspect LlamaProbeNative markers",
                    timeout,
                )
            }
            val elapsed = elapsedMs(started)
            check(streamChunks > 0) { "The first constrained token produced no complete UTF-8 output chunk" }
            Log.i(
                TAG,
                "first_token_stream event=${if (generation == null) "token_cap" else "complete"} " +
                    "elapsed_ms=$elapsed stream_chunks=$streamChunks stream_ttft_ms=$streamTimeToFirstChunkMs",
            )
        } finally {
            if (!nativeCallTimedOut) stage("llama_first_token_shutdown") { session.close() }
        }
        Unit
    }

    @Test
    fun boundedLfmPrefillCancellationDrainsAndReusesSession() = runBlocking {
        assumeTrue(
            "invoke only with -e llamaPrefillCancelOnly true",
            InstrumentationRegistry.getArguments().getString("llamaPrefillCancelOnly") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        stage("probe_isolation") { assertProbeIsolation(context) }
        val llamaFile = llamaModelFile(context, InstrumentationRegistry.getArguments().getString("llamaModelPath"))
        require(llamaFile.isFile) { "LFM2.5 GGUF file is missing" }
        val preflight = stage("prefill_cancel_memory_preflight") { memorySnapshot(context) }
        Log.i(TAG, "prefill_cancel_preflight=$preflight")
        check(!preflight.getBoolean("low_memory")) {
            "Android reports lowMemory before the bounded prefill cancellation test"
        }
        requireOpenClStartGates(preflight, BuildConfig.IS_OPENCL_PROBE, "prefill cancellation")
        val session = stage("llama_prefill_cancel_load") {
            loadLfmSession(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
        }
        val cancelledRequestChunks = AtomicInteger()
        try {
            val pendingRequest = async(Dispatchers.Default) {
                session.generate(
                    prompt = MealPlanProbePrompts.PLAN_USER,
                    systemPrompt = MealPlanProbePrompts.PLAN_SYSTEM,
                    schemaJson = MealPlanProbeSchemas.plan(),
                    maxTokens = 1,
                    reportProgress = true,
                    onText = { cancelledRequestChunks.incrementAndGet() },
                )
            }
            delay(PREFILL_CANCEL_DELAY_MS)
            check(pendingRequest.isActive) {
                "The bounded prefill request completed before cancellation could be exercised"
            }
            Log.i(TAG, "prefill_cancel event=request_cancel delay_ms=$PREFILL_CANCEL_DELAY_MS")
            pendingRequest.cancel()
            pendingRequest.join()
            stage("llama_prefill_cancel_drain") { session.awaitNativeIdle() }
            assertEquals("Cancelled request emitted token chunks", 0, cancelledRequestChunks.get())
            assertEquals("Native request remained active after its worker drained", false, session.isGenerating)

            val reusedRequestChunks = AtomicInteger()
            try {
                stage("llama_session_reuse_after_prefill_abort") {
                    withTimeout(SESSION_REUSE_TIMEOUT_MS) {
                        session.generate(
                            prompt = "Return the one-letter JSON string a.",
                            systemPrompt = "Return only JSON.",
                            schemaJson = """{"type":"string"}""",
                            maxTokens = 1,
                            reportProgress = true,
                            onText = { reusedRequestChunks.incrementAndGet() },
                        )
                    }
                }
            } catch (incomplete: IllegalStateException) {
                if (incomplete.message != "llama.cpp generation reached maxTokens before end-of-generation") {
                    throw incomplete
                }
            }
            stage("llama_session_reuse_drain") { session.awaitNativeIdle() }
            assertEquals("Reused native request remained active after draining", false, session.isGenerating)
            Log.i(
                TAG,
                "prefill_cancel event=verified cancelled_chunks=${cancelledRequestChunks.get()} " +
                    "reuse_chunks=${reusedRequestChunks.get()}",
            )
        } finally {
            stage("llama_prefill_cancel_shutdown") { session.close() }
        }
        Unit
    }

    @Test
    fun compareLiteRtThenLlamaSequentiallyAndVerifyCancelReuse() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val dsparkCompare = arguments.getString("llamaDsparkCompare") == "true"
        val llamaOnly = arguments.getString("llamaOnly") == "true" || dsparkCompare
        stage("probe_isolation") { assertProbeIsolation(context) }
        val gemmaFileCandidate = if (llamaOnly) null else {
            gemmaModelFile(context, arguments.getString("gemmaModelPath"))
        }
        val llamaFile = llamaModelFile(context, arguments.getString("llamaModelPath"))
        val dsparkFile = if (dsparkCompare) dsparkModelFile(context, arguments.getString("dsparkModelPath")) else null
        gemmaFileCandidate?.let { require(it.isFile) { "Gemma model file is missing" } }
        require(llamaFile.isFile) { "LFM2.5 GGUF file is missing" }
        dsparkFile?.let(::verifyPinnedDsparkModel)
        if (dsparkCompare) verifyPinnedLfmModel(llamaFile)
        val preflight = stage("memory_preflight") { memorySnapshot(context) }
        val results = JSONObject()
            .put("probe_pid", Process.myPid())
            .put("preflight", preflight)
        var retryCount = 0
        var repairCount = 0
        logProbeResults(results)

        check(!preflight.getBoolean("low_memory")) { "Android reports lowMemory before inference; stopping preflight" }
        requireOpenClStartGates(preflight, BuildConfig.IS_OPENCL_PROBE, "comparison")

        if (!llamaOnly) {
            val gemmaFile = requireNotNull(gemmaFileCandidate)
            val gemmaConfig = ModelConfig(
                modelPath = gemmaFile.absolutePath,
                backendType = BackendType.GPU,
                maxTokens = CONTEXT_TOKENS,
                systemPrompt = MealPlanProbePrompts.PLAN_SYSTEM,
                thinkingEnabled = false,
                speculativeDecodingEnabled = false,
            )
            val gemmaSamples = JSONObject().put("model_file", gemmaFile.name)
            val gemmaEngine = newLiteRtEngine(context)
            try {
                val coldStart = System.nanoTime()
                stage("gemma_initialize") { gemmaEngine.initialize(gemmaConfig) }
                val gemmaColdLoadMs = elapsedMs(coldStart)
                gemmaSamples.put("cold_load_ms", gemmaColdLoadMs)
                    .put("backend", gemmaEngine.activeBackend.value?.name ?: "unknown")
                    .put("resolved_context_tokens", gemmaEngine.resolvedMaxTokens.value)
                results.put("after_gemma_cold_load", ensureMemoryHealthy(context, "Gemma cold load"))

                val plan = generateWithOneRepair(
                    stageName = "gemma_plan",
                    prompt = MealPlanProbePrompts.PLAN_USER,
                    generate = { prompt -> generateLiteRt(context, gemmaEngine, prompt) },
                    textOf = Sample::text,
                    validate = { text ->
                        ValidationAttempt(
                            valid = runCatching { parser.parsePlanDraft(text, expectedDays = 3) }.isSuccess,
                            normalizedIngredients = 0,
                        )
                    },
                )
                retryCount += plan.retries
                repairCount += plan.retries
                gemmaSamples.put("plan", plan.value.toJson(plan.valid, coldStart, gemmaColdLoadMs, plan.retries))
                gemmaEngine.updateSystemPrompt(MealPlanProbePrompts.RECIPE_SYSTEM)
                val recipeStart = System.nanoTime()
                val recipe = generateWithOneRepair(
                    stageName = "gemma_recipe",
                    prompt = MealPlanProbePrompts.RECIPE_USER,
                    generate = { prompt -> generateLiteRt(context, gemmaEngine, prompt) },
                    textOf = Sample::text,
                    validate = ::validateRecipe,
                )
                retryCount += recipe.retries
                repairCount += recipe.retries
                gemmaSamples.put(
                    "recipe",
                    recipe.value.toJson(recipe.valid, recipeStart, coldLoadMs = 0, retries = recipe.retries)
                        .put("normalized_ingredients", recipe.normalizedIngredients)
                        .put("validation_diagnostics", recipeValidationDiagnostics(recipe.attempts)),
                )

                gemmaSamples.put("plan_and_recipe_wall_ms", elapsedMs(coldStart))

                gemmaEngine.updateSystemPrompt(MealPlanProbePrompts.PLAN_SYSTEM)
                val warmStart = System.nanoTime()
                val warmPlan = stage("gemma_warm_plan") {
                    generateLiteRt(context, gemmaEngine, MealPlanProbePrompts.PLAN_USER)
                }
                val warmPlanValid = runCatching { parser.parsePlanDraft(warmPlan.text, expectedDays = 3) }.isSuccess
                gemmaSamples.put("warm_plan", warmPlan.toJson(warmPlanValid, warmStart, coldLoadMs = 0))
                    .put("warm_generation_wall_ms", elapsedMs(warmStart))
                results.put("after_gemma_generations", ensureMemoryHealthy(context, "Gemma plan and recipe"))
            } finally {
                stage("gemma_shutdown") { gemmaEngine.shutdown() }
            }
            results.put("gemma", gemmaSamples)
            logProbeResults(results)
        }

        val llamaColdStart = System.nanoTime()
        val llama = stage("llama_load") { loadLfmSession(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS) }
        val llamaColdLoadMs = llama.loadMs
        val llamaSamples = JSONObject()
            .put("cold_load_ms", llamaColdLoadMs)
            .put("backend", if (BuildConfig.IS_OPENCL_PROBE) "OpenCL" else "CPU")
            .put("context_tokens", LLAMA_CONTEXT_TOKENS)
        try {
            if (BuildConfig.IS_OPENCL_PROBE) {
                val backendInfo = JSONObject(llama.backendInfo())
                assertTrue(
                    "No model layers were offloaded to the OpenCL GPU: $backendInfo",
                    backendInfo.getInt("offloaded_gpu_layers") > 0,
                )
                assertTrue(
                    "Invalid OpenCL model layer count: $backendInfo",
                    backendInfo.getInt("total_model_layers") >= backendInfo.getInt("offloaded_gpu_layers"),
                )
                llamaSamples.put("backend_observation", backendInfo)
                Log.i(TAG, "opencl_model_backend=$backendInfo")
            }
            results.put("after_llama_cold_load", ensureMemoryHealthy(context, "llama.cpp cold load"))
            val plan = generateWithOneRepair(
                stageName = "llama_plan",
                prompt = MealPlanProbePrompts.PLAN_USER,
                generate = { prompt ->
                    generateStreamed(llama, prompt, MealPlanProbePrompts.PLAN_SYSTEM, MealPlanProbeSchemas.plan())
                },
                textOf = LlamaGeneration::text,
                validate = { text ->
                    ValidationAttempt(
                        valid = runCatching { parser.parsePlanDraft(text, expectedDays = 3) }.isSuccess,
                        normalizedIngredients = 0,
                    )
                },
            )
            retryCount += plan.retries
            repairCount += plan.retries
            if (BuildConfig.IS_OPENCL_PROBE) {
                llamaSamples.put("plan_backend_profile", assertOpenClComputeProfile(llama, "plan"))
            }
            llamaSamples.put(
                "plan",
                plan.value.toJson(plan.valid, llamaColdStart, ensureMemoryHealthy(context, "llama plan"), plan.retries),
            )
            val recipeStart = System.nanoTime()
            val recipe = generateWithOneRepair(
                stageName = "llama_recipe",
                prompt = MealPlanProbePrompts.RECIPE_USER,
                generate = { prompt ->
                    generateStreamed(
                        llama, prompt, MealPlanProbePrompts.RECIPE_SYSTEM,
                        MealPlanProbeSchemas.recipe(expectedServings = 4),
                    )
                },
                textOf = LlamaGeneration::text,
                validate = ::validateRecipe,
            )
            retryCount += recipe.retries
            repairCount += recipe.retries
            if (BuildConfig.IS_OPENCL_PROBE) {
                llamaSamples.put("recipe_backend_profile", assertOpenClComputeProfile(llama, "recipe"))
            }
            llamaSamples.put(
                "recipe",
                recipe.value.toJson(recipe.valid, recipeStart, ensureMemoryHealthy(context, "llama recipe"), recipe.retries)
                    .put("normalized_ingredients", recipe.normalizedIngredients)
                    .put("validation_diagnostics", recipeValidationDiagnostics(recipe.attempts)),
            )

            llamaSamples.put("plan_and_recipe_wall_ms", elapsedMs(llamaColdStart))

            val invalidSchemaFailure = stage("llama_unsupported_schema") {
                runCatching {
                    llama.generate("probe", "probe", """{"type":"array","items":{"type":"string"},"uniqueItems":true}""")
                }.exceptionOrNull()
            }
            llamaSamples.put("invalid_schema_rejected", invalidSchemaFailure is UnsupportedSchemaException)
            assertTrue("unsupported schema was not rejected", invalidSchemaFailure is UnsupportedSchemaException)
            assertTrue("invalid schema left generation active", !llama.isGenerating)
            val nativeFailure = stage("llama_native_error_recovery") {
                runCatching {
                    llama.generate(
                        MealPlanProbePrompts.PLAN_USER,
                        MealPlanProbePrompts.PLAN_SYSTEM,
                        MealPlanProbeSchemas.plan(),
                        maxTokens = 1,
                    )
                }.exceptionOrNull()
            }
            llamaSamples.put("native_error_recovered", nativeFailure is IllegalStateException && !llama.isGenerating)
            assertTrue("native incomplete-generation error was not surfaced", nativeFailure is IllegalStateException)
            stage("llama_native_idle") { llama.awaitNativeIdle() }
            assertTrue("native failure left generation active", !llama.isGenerating)

            val warmStart = System.nanoTime()
            val warmPlan = stage("llama_warm_plan") {
                generateStreamed(
                    llama, MealPlanProbePrompts.PLAN_USER, MealPlanProbePrompts.PLAN_SYSTEM,
                    MealPlanProbeSchemas.plan(),
                )
            }
            val warmPlanValid = runCatching { parser.parsePlanDraft(warmPlan.text, expectedDays = 3) }.isSuccess
            llamaSamples.put(
                "warm_plan",
                warmPlan.toJson(warmPlanValid, warmStart, ensureMemoryHealthy(context, "llama warm plan")),
            ).put("warm_generation_wall_ms", elapsedMs(warmStart))

            val acceptCallbacks = AtomicBoolean(true)
            val callbacksAfterCancellation = AtomicInteger(0)
            val callbacksBeforeCancellation = AtomicInteger(0)
            val cancellation = async(Dispatchers.Default) {
                llama.generate(
                    prompt = "Prepare a detailed multi-course meal plan with every step described in detail.",
                    systemPrompt = MealPlanProbePrompts.PLAN_SYSTEM,
                    schemaJson = MealPlanProbeSchemas.plan(),
                    maxTokens = 1024,
                    onText = {
                        if (acceptCallbacks.get()) callbacksBeforeCancellation.incrementAndGet()
                        else callbacksAfterCancellation.incrementAndGet()
                    },
                )
            }
            stage("llama_cancellation_wait") {
                withTimeout(30_000) {
                    while (!llama.isGenerating || callbacksBeforeCancellation.get() == 0) delay(25)
                }
            }
            stage("llama_cancellation_drain") {
                cancellation.cancel()
                acceptCallbacks.set(false)
                cancellation.join()
                llama.awaitNativeIdle()
            }
            ensureMemoryHealthy(context, "llama cancellation")
            assertTrue("generation job was not cancelled", cancellation.isCancelled)
            assertTrue("native generation remains active after cancellation", !llama.isGenerating)
            assertTrue("streaming did not produce text before cancellation", callbacksBeforeCancellation.get() > 0)
            assertEquals("token callbacks occurred after cancellation", 0, callbacksAfterCancellation.get())
            val reuseStart = System.nanoTime()
            val reused = stage("llama_reuse_after_cancellation") {
                generateStreamed(
                    llama, MealPlanProbePrompts.PLAN_USER, MealPlanProbePrompts.PLAN_SYSTEM,
                    MealPlanProbeSchemas.plan(),
                )
            }
            val reuseValid = runCatching { parser.parsePlanDraft(reused.text, expectedDays = 3) }.isSuccess
            llamaSamples.put(
                "cancelled_then_reused",
                reused.toJson(reuseValid, reuseStart, ensureMemoryHealthy(context, "llama reuse")),
            )
            results.put("after_llama_generations", ensureMemoryHealthy(context, "llama cancellation and reuse"))
        } finally {
            stage("llama_shutdown") { llama.close() }
        }

        val reloadStart = System.nanoTime()
        val reloaded = stage("llama_reload") { loadLfmSession(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS) }
        try {
            val reloadedPlan = stage("llama_plan_after_reload") {
                generateStreamed(
                    reloaded, MealPlanProbePrompts.PLAN_USER, MealPlanProbePrompts.PLAN_SYSTEM,
                    MealPlanProbeSchemas.plan(),
                )
            }
            val reloadedValid = runCatching { parser.parsePlanDraft(reloadedPlan.text, expectedDays = 3) }.isSuccess
            llamaSamples.put("reload_load_ms", reloaded.loadMs)
                .put("reload_to_usable_ms", elapsedMs(reloadStart))
                .put("after_unload_reload", reloadedPlan.toJson(reloadedValid, reloadStart, ensureMemoryHealthy(context, "llama unload/reload")))
        } finally {
            stage("llama_reload_shutdown") { reloaded.close() }
        }
        results.put("llama", llamaSamples)
        if (dsparkCompare) {
            val draftFile = requireNotNull(dsparkFile)
            val dsparkLoadStart = System.nanoTime()
            val dspark = stage("dspark_load") {
                loadLfmSession(
                    llamaFile,
                    contextSize = LLAMA_CONTEXT_TOKENS,
                    draftModel = draftFile,
                )
            }
            val dsparkSamples = JSONObject()
                .put("target_file", llamaFile.name)
                .put("draft_file", draftFile.name)
                .put("draft_sha256", DSPARK_SHA256)
                .put("cold_load_ms", dspark.loadMs)
                .put("cold_load_wall_ms", elapsedMs(dsparkLoadStart))
                .put("backend", "CPU")
                .put("context_tokens", LLAMA_CONTEXT_TOKENS)
            var dsparkRetries = 0
            try {
                results.put("after_dspark_cold_load", ensureMemoryHealthy(context, "DSpark cold load"))
                val dsparkPlanStart = dsparkLoadStart
                val dsparkPlan = generateWithOneRepair(
                    stageName = "dspark_plan",
                    prompt = MealPlanProbePrompts.PLAN_USER,
                    generate = { prompt ->
                        generateStreamed(
                            dspark, prompt, MealPlanProbePrompts.PLAN_SYSTEM,
                            MealPlanProbeSchemas.plan(), reportProgress = true,
                        )
                    },
                    textOf = LlamaGeneration::text,
                    validate = { text ->
                        ValidationAttempt(
                            valid = runCatching { parser.parsePlanDraft(text, expectedDays = 3) }.isSuccess,
                            normalizedIngredients = 0,
                        )
                    },
                )
                dsparkRetries += dsparkPlan.retries
                dsparkSamples.put(
                    "plan",
                    dsparkPlan.value.toJson(
                        dsparkPlan.valid, dsparkPlanStart,
                        ensureMemoryHealthy(context, "DSpark plan"), dsparkPlan.retries,
                    ),
                )

                val dsparkRecipeStart = System.nanoTime()
                val dsparkRecipe = generateWithOneRepair(
                    stageName = "dspark_recipe",
                    prompt = MealPlanProbePrompts.RECIPE_USER,
                    generate = { prompt ->
                        generateStreamed(
                            dspark, prompt, MealPlanProbePrompts.RECIPE_SYSTEM,
                            MealPlanProbeSchemas.recipe(expectedServings = 4), reportProgress = true,
                        )
                    },
                    textOf = LlamaGeneration::text,
                    validate = ::validateRecipe,
                )
                dsparkRetries += dsparkRecipe.retries
                dsparkSamples.put(
                    "recipe",
                    dsparkRecipe.value.toJson(
                        dsparkRecipe.valid, dsparkRecipeStart,
                        ensureMemoryHealthy(context, "DSpark recipe"), dsparkRecipe.retries,
                    ).put("normalized_ingredients", dsparkRecipe.normalizedIngredients)
                        .put("validation_diagnostics", recipeValidationDiagnostics(dsparkRecipe.attempts)),
                )
                dsparkSamples.put("plan_and_recipe_wall_ms", elapsedMs(dsparkPlanStart))

                val dsparkWarmStart = System.nanoTime()
                val dsparkWarmPlan = stage("dspark_warm_plan") {
                    generateStreamed(
                        dspark, MealPlanProbePrompts.PLAN_USER, MealPlanProbePrompts.PLAN_SYSTEM,
                        MealPlanProbeSchemas.plan(), reportProgress = true,
                    )
                }
                val dsparkWarmValid = runCatching {
                    parser.parsePlanDraft(dsparkWarmPlan.text, expectedDays = 3)
                }.isSuccess
                dsparkSamples.put(
                    "warm_plan",
                    dsparkWarmPlan.toJson(
                        dsparkWarmValid, dsparkWarmStart,
                        ensureMemoryHealthy(context, "DSpark warm plan"),
                    ),
                )

                val invalidDsparkSchema = stage("dspark_unsupported_schema") {
                    runCatching {
                        dspark.generate(
                            "probe", "probe",
                            """{"type":"array","items":{"type":"string"},"uniqueItems":true}""",
                        )
                    }.exceptionOrNull()
                }
                dsparkSamples.put(
                    "invalid_schema_rejected",
                    invalidDsparkSchema is UnsupportedSchemaException,
                )

                val cancelledChunksBefore = AtomicInteger()
                val cancelledChunksAfter = AtomicInteger()
                val markCancelled = AtomicBoolean(false)
                val pendingDsparkRequest = async(Dispatchers.Default) {
                    dspark.generate(
                        prompt = "Return a detailed multi-step meal plan as valid JSON.",
                        systemPrompt = MealPlanProbePrompts.PLAN_SYSTEM,
                        schemaJson = MealPlanProbeSchemas.plan(),
                        maxTokens = 512,
                        reportProgress = true,
                        onText = {
                            if (markCancelled.get()) cancelledChunksAfter.incrementAndGet()
                            else cancelledChunksBefore.incrementAndGet()
                        },
                    )
                }
                stage("dspark_cancel_request_start") {
                    withTimeout(30_000) {
                        while (!dspark.isGenerating) delay(25)
                    }
                }
                delay(PREFILL_CANCEL_DELAY_MS)
                check(pendingDsparkRequest.isActive) {
                    "DSpark request completed before cancellation could be exercised"
                }
                pendingDsparkRequest.cancel()
                markCancelled.set(true)
                pendingDsparkRequest.join()
                stage("dspark_cancel_drain") { dspark.awaitNativeIdle() }
                assertEquals(0, cancelledChunksAfter.get())
                assertEquals(false, dspark.isGenerating)
                dsparkSamples.put("cancellation_drained", true)
                    .put("chunks_before_cancel", cancelledChunksBefore.get())
                    .put("chunks_after_cancel", cancelledChunksAfter.get())

                val reuseStart = System.nanoTime()
                val reuse = stage("dspark_reuse_after_cancel") {
                    withTimeout(SESSION_REUSE_TIMEOUT_MS) {
                        generateStreamed(
                            dspark,
                            """Return {"ok":"done"} as JSON.""",
                            "Return only the requested JSON object.",
                            """{"type":"object","properties":{"ok":{"type":"string"}},"required":["ok"],"additionalProperties":false}""",
                            maxTokens = 128,
                            reportProgress = true,
                        )
                    }
                }
                dsparkSamples.put("reuse_after_cancel", reuse.toJson(
                    runCatching { JSONObject(reuse.text).getString("ok") == "done" }.getOrDefault(false),
                    reuseStart, ensureMemoryHealthy(context, "DSpark reuse"),
                ))
            } finally {
                stage("dspark_shutdown") { dspark.close() }
            }

            val dsparkReloadStart = System.nanoTime()
            val dsparkReloaded = stage("dspark_reload") {
                loadLfmSession(
                    llamaFile,
                    contextSize = LLAMA_CONTEXT_TOKENS,
                    draftModel = draftFile,
                )
            }
            try {
                val reloadedPlan = stage("dspark_plan_after_reload") {
                    generateStreamed(
                        dsparkReloaded, MealPlanProbePrompts.PLAN_USER, MealPlanProbePrompts.PLAN_SYSTEM,
                        MealPlanProbeSchemas.plan(), reportProgress = true,
                    )
                }
                val reloadedValid = runCatching {
                    parser.parsePlanDraft(reloadedPlan.text, expectedDays = 3)
                }.isSuccess
                dsparkSamples.put("reload_load_ms", dsparkReloaded.loadMs)
                    .put("reload_to_usable_ms", elapsedMs(dsparkReloadStart))
                    .put(
                        "after_unload_reload",
                        reloadedPlan.toJson(
                            reloadedValid, dsparkReloadStart,
                            ensureMemoryHealthy(context, "DSpark unload/reload"),
                        ),
                    )
            } finally {
                stage("dspark_reload_shutdown") { dsparkReloaded.close() }
            }
            val dsparkDraftedTokens = listOf("plan", "recipe", "warm_plan")
                .sumOf { dsparkSamples.getJSONObject(it).optInt("drafted_tokens", 0) }
            val dsparkAcceptedDraftTokens = listOf("plan", "recipe", "warm_plan")
                .sumOf { dsparkSamples.getJSONObject(it).optInt("accepted_draft_tokens", 0) }
            dsparkSamples.put("retry_count", dsparkRetries)
                .put("drafted_tokens", dsparkDraftedTokens)
                .put("accepted_draft_tokens", dsparkAcceptedDraftTokens)
                .put(
                    "draft_acceptance_rate",
                    if (dsparkDraftedTokens == 0) 0.0
                    else dsparkAcceptedDraftTokens.toDouble() / dsparkDraftedTokens,
                )
            results.put("dspark", dsparkSamples)
            assertTrue("DSpark meal plan failed application parsing", dsparkSamples.getJSONObject("plan").getBoolean("app_valid"))
            assertTrue("DSpark recipe failed application parsing", dsparkSamples.getJSONObject("recipe").getBoolean("app_valid"))
            assertEquals(6, dsparkSamples.getJSONObject("recipe").getInt("normalized_ingredients"))
            assertTrue("DSpark warm plan failed application parsing", dsparkSamples.getJSONObject("warm_plan").getBoolean("app_valid"))
            assertTrue("DSpark unsupported schema was not rejected", dsparkSamples.getBoolean("invalid_schema_rejected"))
            assertTrue("DSpark cancellation did not drain", dsparkSamples.getBoolean("cancellation_drained"))
            assertTrue("DSpark session did not recover after cancellation", dsparkSamples.getJSONObject("reuse_after_cancel").getBoolean("app_valid"))
            assertTrue("DSpark draft path was not exercised", dsparkSamples.getInt("drafted_tokens") > 0)
            assertTrue("DSpark unload/reload failed", dsparkSamples.getJSONObject("after_unload_reload").getBoolean("app_valid"))
        }
        results.put("retry_count", retryCount).put("repair_count", repairCount)
        logProbeResults(results)


        if (!llamaOnly) {
            val gemmaSamples = results.getJSONObject("gemma")
            assertTrue("LiteRT plan failed application parsing", gemmaSamples.getJSONObject("plan").getBoolean("app_valid"))
            assertTrue("LiteRT recipe failed application parsing", gemmaSamples.getJSONObject("recipe").getBoolean("app_valid"))
            assertTrue("LiteRT warm plan failed application parsing", gemmaSamples.getJSONObject("warm_plan").getBoolean("app_valid"))
        }

        assertTrue("llama.cpp plan failed application parsing", llamaSamples.getJSONObject("plan").getBoolean("app_valid"))
        assertTrue("llama.cpp recipe failed application parsing", llamaSamples.getJSONObject("recipe").getBoolean("app_valid"))
        assertEquals(6, llamaSamples.getJSONObject("recipe").getInt("normalized_ingredients"))
        assertTrue("llama.cpp warm plan failed application parsing", llamaSamples.getJSONObject("warm_plan").getBoolean("app_valid"))
        assertTrue("llama.cpp failed to reuse after cancellation", llamaSamples.getJSONObject("cancelled_then_reused").getBoolean("app_valid"))
        assertTrue("llama.cpp failed to reload", llamaSamples.getJSONObject("after_unload_reload").getBoolean("app_valid"))
        assertTrue("llama.cpp rejected schema test failed", llamaSamples.getBoolean("invalid_schema_rejected"))
        assertTrue("llama.cpp native error test failed", llamaSamples.getBoolean("native_error_recovered"))
    }

    private fun newLiteRtEngine(context: Context) = LiteRtInferenceEngine(context, HardwareProfileDetector(context))

    private fun logProbeResults(results: JSONObject) {
        val chunks = splitProbeJsonForLogcat(results.toString())
        chunks.forEachIndexed { index, chunk ->
            Log.i(TAG, "result_json part=${index + 1}/${chunks.size} data=$chunk")
        }
    }

    private data class ValidationAttempt(
        val valid: Boolean,
        val normalizedIngredients: Int,
        val recipeDiagnostic: RecipeProbeValidation? = null,
    )
    private data class ValidatedOutput<T>(
        val value: T,
        val valid: Boolean,
        val normalizedIngredients: Int,
        val retries: Int,
        val attempts: List<ValidationAttempt>,
    )

    private suspend fun <T> stage(name: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        Log.i(TAG, "stage=$name event=start")
        return try {
            withTimeout(STAGE_TIMEOUT_MS) { block() }.also {
                Log.i(TAG, "stage=$name event=complete elapsed_ms=${elapsedMs(started)}")
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.e(
                TAG,
                "stage=$name event=timeout elapsed_ms=${elapsedMs(started)} limit_ms=$STAGE_TIMEOUT_MS",
            )
            throw timeout
        } catch (failure: Throwable) {
            Log.e(
                TAG,
                "stage=$name event=failed elapsed_ms=${elapsedMs(started)} " +
                    "exception=${failure.javaClass.simpleName}",
            )
            throw failure
        }
    }

    private suspend fun <T> generateWithOneRepair(
        stageName: String,
        prompt: String,
        generate: suspend (String) -> T,
        textOf: (T) -> String,
        validate: (String) -> ValidationAttempt,
    ): ValidatedOutput<T> {
        var retries = 0
        val attempts = mutableListOf<ValidationAttempt>()
        while (true) {
            val attemptPrompt = if (retries == 0) prompt else {
                "$prompt\n\nThe previous response failed Jandal application validation. Correct the JSON and return only the corrected output."
            }
            val result = stage("${stageName}_attempt_${retries + 1}") { generate(attemptPrompt) }
            val validation = validate(textOf(result))
            attempts += validation
            if (validation.valid || retries == 1) {
                return ValidatedOutput(
                    value = result,
                    valid = validation.valid,
                    normalizedIngredients = validation.normalizedIngredients,
                    retries = retries,
                    attempts = attempts.toList(),
                )
            }
            retries++
        }
    }
    private suspend fun generateStreamed(
        session: LlamaCppSession,
        prompt: String,
        systemPrompt: String,
        schemaJson: String,
        maxTokens: Int = 1024,
        reportProgress: Boolean = false,
    ): LlamaGeneration {
        val streamed = StringBuilder()
        val generation = session.generate(
            prompt = prompt,
            systemPrompt = systemPrompt,
            schemaJson = schemaJson,
            maxTokens = maxTokens,
            reportProgress = reportProgress,
            onText = { chunk -> streamed.append(chunk) },
        )
        assertEquals("streamed text differs from the final native result", generation.text, streamed.toString())
        assertTrue("nonempty generation produced no stream chunks", generation.text.isEmpty() || generation.streamedChunks > 0)
        return generation
    }



    private suspend fun generateLiteRt(context: Context, engine: LiteRtInferenceEngine, prompt: String): Sample {
        val start = System.nanoTime()
        var firstTokenNs: Long? = null
        var chunks = 0
        var complete = false
        val text = StringBuilder()
        engine.generate(prompt).collect { result ->
            when (result) {
                is GenerationResult.Token -> {
                    if (firstTokenNs == null) firstTokenNs = System.nanoTime()
                    chunks++
                    text.append(result.text)
                }
                is GenerationResult.Thinking -> Unit
                is GenerationResult.Complete -> complete = true
                is GenerationResult.Error -> throw IllegalStateException("LiteRT generation failed: ${result.message}")
            }
        }
        check(complete) { "LiteRT generation ended without a completion event" }
        val done = System.nanoTime()
        val first = firstTokenNs ?: done
        return Sample(
            text = text.toString(),
            ttftMs = (first - start) / 1_000_000,
            decodeMs = (done - first) / 1_000_000,
            totalMs = (done - start) / 1_000_000,
            outputChunks = chunks,
            memory = ensureMemoryHealthy(context, "LiteRT generation"),
        )
    }

    private fun validateRecipe(text: String): ValidationAttempt {
        val diagnostic = recipeValidator.validate(text)
        return ValidationAttempt(
            valid = diagnostic.valid,
            normalizedIngredients = diagnostic.normalizedIngredientCount,
            recipeDiagnostic = diagnostic,
        )
    }

    private fun recipeValidationDiagnostics(attempts: List<ValidationAttempt>): JSONObject {
        val reports = attempts.mapNotNull { it.recipeDiagnostic }
        val categoryCounts = JSONObject()
        reports.groupingBy { it.category.wireName }.eachCount().forEach { (category, count) ->
            categoryCounts.put(category, count)
        }
        val attemptReports = JSONArray()
        reports.forEachIndexed { index, report ->
            val item = JSONObject()
                .put("attempt", index + 1)
                .put("category", report.category.wireName)
                .put("valid", report.valid)
                .put("parsed_ingredient_count", report.parsedIngredientCount ?: JSONObject.NULL)
                .put("normalized_ingredient_count", report.normalizedIngredientCount)
                .put("name_mismatch_count", report.nameMismatchCount)
                .put("unit_mismatch_count", report.unitMismatchCount)
                .put("quantity_mismatch_count", report.quantityMismatchCount)
                .put("validation_ms", report.validationMs)
            report.exceptionClass?.let { item.put("exception_class", it) }
            attemptReports.put(item)
        }
        return JSONObject()
            .put("attempts", attemptReports)
            .put("category_counts", categoryCounts)
    }

    private fun gemmaModelFile(context: Context, override: String?): File {
        override?.let(::File)?.let { return it }
        val modelFiles = listOf(
            KernelModel.GEMMA_4_E4B.fileName,
            "gemma-4-E4B-it-gpu.litertlm",
            KernelModel.GEMMA_4_E2B.fileName,
        )
        val directories = listOfNotNull(
            File(context.filesDir, "models"),
            context.getExternalFilesDir("models"),
        )
        return directories.asSequence()
            .flatMap { directory -> modelFiles.asSequence().map { fileName -> File(directory, fileName) } }
            .firstOrNull(File::isFile)
            ?: error("No installed Gemma E4B/E2B model exists under the app models directory")
    }

    private fun llamaModelFile(context: Context, override: String?): File {
        override?.let(::File)?.let { return it }
        val directories = listOfNotNull(
            File(context.filesDir, "models"),
            context.getExternalFilesDir("models"),
        )
        return directories.asSequence()
            .map { directory -> File(directory, MODEL_FILE_NAME) }
            .firstOrNull(File::isFile)
            ?: error("No LFM2.5 GGUF file exists under the app models directory")
    }

    private fun dsparkModelFile(context: Context, override: String?): File {
        override?.let(::File)?.let { return it }
        val directories = listOfNotNull(
            File(context.filesDir, "models"),
            context.getExternalFilesDir("models"),
        )
        return directories.asSequence()
            .map { directory -> File(directory, DSPARK_FILE_NAME) }
            .firstOrNull(File::isFile)
            ?: error("Official LFM2.5 DSpark Q4_K_M sidecar is missing from the probe model directory")
    }

    private fun verifyPinnedDsparkModel(file: File) {
        verifyPinnedModel(file, DSPARK_FILE_SIZE_BYTES, DSPARK_SHA256, "LFM2.5 DSpark sidecar")
    }

    private fun verifyPinnedModel(file: File, expectedSize: Long, expectedSha256: String, label: String) {
        require(file.isFile && file.length() == expectedSize) {
            "$label size does not match its pinned provenance"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualSha256 == expectedSha256) {
            "$label checksum does not match its pinned provenance"
        }
    }

    private fun verifyPinnedLfmModel(file: File) {
        verifyPinnedModel(file, LFM_FILE_SIZE_BYTES, LFM_SHA256, "LFM2.5 target")
    }

    private fun ensureMemoryHealthy(context: Context, stage: String): JSONObject {
        val snapshot = memorySnapshot(context)
        check(!snapshot.getBoolean("low_memory")) {
            "Android reports lowMemory after $stage; stopping the comparison"
        }
        requireOpenClThermalGate(snapshot, BuildConfig.IS_OPENCL_PROBE, stage)
        return snapshot
    }

    private fun memorySnapshot(context: Context): JSONObject {
        val memory = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager.getMemoryInfo(memory)
        val pssKb = activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: -1
        val powerManager = context.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("avail_mem_bytes", memory.availMem)
            .put("low_memory", memory.lowMemory)
            .put("threshold_bytes", memory.threshold)
            .put("total_mem_bytes", memory.totalMem)
            .put("process_pss_kb", pssKb)
            .put("thermal_status", powerManager.currentThermalStatus)
            .put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun Sample.toJson(
        appValid: Boolean,
        wallStartNanos: Long,
        coldLoadMs: Long,
        retries: Int = 0,
    ): JSONObject = JSONObject()
        .put("app_valid", appValid)
        .put("ttft_ms", ttftMs)
        .put("decode_ms", decodeMs)
        .put("total_ms", totalMs)
        .put("cold_load_ms", coldLoadMs)
        .put("wall_to_usable_ms", if (appValid) elapsedMs(wallStartNanos) else JSONObject.NULL)
        .put("attempt_wall_ms", elapsedMs(wallStartNanos))
        .put("retry_count", retries)
        .put("repair_count", retries)
        .put("stream_chunks", outputChunks)
        .put("memory", memory)

    private data class Sample(
        val text: String,
        val ttftMs: Long,
        val decodeMs: Long,
        val totalMs: Long,
        val outputChunks: Int,
        val memory: JSONObject,
    )

    private fun LlamaGeneration.toJson(
        appValid: Boolean,
        wallStartNanos: Long,
        memory: JSONObject,
        retries: Int = 0,
    ): JSONObject = JSONObject()
        .put("app_valid", appValid)
        .put("ttft_ms", timeToFirstTokenMs)
        .put("stream_ttft_ms", streamTimeToFirstChunkMs ?: JSONObject.NULL)
        .put("stream_chunks", streamedChunks)
        .put("prefill_ms", prefillMs)
        .put("prefill_tokens", prefillTokens)
        .put("prefill_tokens_per_second", prefillTokensPerSecond)
        .put("decode_ms", decodeMs)
        .put("decode_tokens_per_second", decodeTokensPerSecond)
        .put("total_ms", totalMs)
        .put("wall_to_usable_ms", if (appValid) elapsedMs(wallStartNanos) else JSONObject.NULL)
        .put("attempt_wall_ms", elapsedMs(wallStartNanos))
        .put("retry_count", retries)
        .put("repair_count", retries)
        .put("prompt_tokens", promptTokens)
        .put("generated_tokens", generatedTokens)
        .put("drafted_tokens", draftedTokens)
        .put("accepted_draft_tokens", acceptedDraftTokens)
        .put("memory", memory)

    private fun assertOpenClComputeProfile(session: LlamaCppSession, stage: String): JSONObject {
        val profile = JSONObject(session.backendInfo())
        assertTrue("$stage did not execute graph nodes on the OpenCL GPU: $profile",
            profile.getLong("gpu_compute_nodes") > 0)
        assertTrue("$stage CPU fallback counter missing: $profile", profile.has("cpu_fallback_nodes"))
        Log.i(TAG, "opencl_compute_$stage=$profile")
        return profile
    }

    private fun assertProbeIsolation(context: Context) {
        require(InstrumentationRegistry.getArguments().getString(ProbeIsolatedRunner.ARG_PROBE_ONLY) == "true") {
            "Run only with probeOnly=true"
        }
        require(context.packageName == PROBE_APPLICATION_ID) {
            "Run the dedicated llamaProbe package; existing app WorkManager jobs are not isolated in the normal debug package"
        }
        require(context.applicationContext is ProbeApplication) {
            "The isolated probe package must use ProbeApplication"
        }
        require(context.applicationContext !is KernelAIApplication) {
            "The isolated probe must not create KernelAIApplication"
        }
        require(!ProbeApplication.onCreateObserved) {
            "ProbeIsolatedRunner unexpectedly called ProbeApplication.onCreate"
        }
        val pendingJobs = requireNotNull(context.getSystemService(JobScheduler::class.java)).allPendingJobs
        require(pendingJobs.isEmpty()) {
            "The isolated probe package already has ${pendingJobs.size} scheduled jobs; stop before model initialization"
        }
    }

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    companion object {
        private const val TAG = "MealPlanProbe"
        private const val STAGE_TIMEOUT_MS = 5 * 60 * 1_000L
        private const val FIRST_TOKEN_TIMEOUT_MS = 60_000L
        private const val PREFILL_CANCEL_DELAY_MS = 2_000L
        private const val SESSION_REUSE_TIMEOUT_MS = 60_000L
        private const val CONTEXT_TOKENS = 4096
        private const val LLAMA_CONTEXT_TOKENS = 4096
        private const val LFM_FILE_SIZE_BYTES = 1_674_455_040L
        private const val MIN_START_AVAILABLE_MEMORY_BYTES = 4_800_000_000L
        private const val MAX_THERMAL_STATUS = 1
        private const val LFM_SHA256 = "02a8b7e17487d326e46d68ce0ba24211e1b80a14c4cd0597fa73c1cd697f52ed"
        private const val DSPARK_FILE_NAME = "LFM2.5-2.6B-DSpark-Q4_K_M.gguf"
        private const val DSPARK_FILE_SIZE_BYTES = 199_680_512L
        private const val DSPARK_SHA256 = "63786b768e43562591a934625a66539eab39beb09e6e3d38e17509e34b79a3cd"
        private const val MODEL_FILE_NAME = "LFM2.5-2.6B-Q4_K_M.gguf"
        private const val PROBE_APPLICATION_ID = "com.kernel.ai.debug.llamaprobe"
    }
}
