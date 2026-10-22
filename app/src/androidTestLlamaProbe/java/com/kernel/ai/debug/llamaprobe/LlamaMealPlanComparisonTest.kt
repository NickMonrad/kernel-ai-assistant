package com.kernel.ai.debug.llamaprobe

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        val session = stage("llama_first_token_load") {
            LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
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
        val session = stage("llama_prefill_cancel_load") {
            LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
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
        val llamaOnly = arguments.getString("llamaOnly") == "true"
        stage("probe_isolation") { assertProbeIsolation(context) }
        val gemmaFileCandidate = if (llamaOnly) null else {
            gemmaModelFile(context, arguments.getString("gemmaModelPath"))
        }
        val llamaFile = llamaModelFile(context, arguments.getString("llamaModelPath"))
        gemmaFileCandidate?.let { require(it.isFile) { "Gemma model file is missing" } }
        require(llamaFile.isFile) { "LFM2.5 GGUF file is missing" }
        val preflight = stage("memory_preflight") { memorySnapshot(context) }
        val results = JSONObject()
            .put("probe_pid", Process.myPid())
            .put("preflight", preflight)
        var retryCount = 0
        var repairCount = 0
        Log.i(TAG, results.toString())

        check(!preflight.getBoolean("low_memory")) { "Android reports lowMemory before inference; stopping preflight" }

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
            Log.i(TAG, results.toString())
        }

        val llamaColdStart = System.nanoTime()
        val llama = stage("llama_load") { LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS) }
        val llamaColdLoadMs = llama.loadMs
        val llamaSamples = JSONObject()
            .put("cold_load_ms", llamaColdLoadMs)
            .put("backend", "CPU")
            .put("context_tokens", LLAMA_CONTEXT_TOKENS)
        try {
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
        val reloaded = stage("llama_reload") { LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS) }
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
        results.put("retry_count", retryCount).put("repair_count", repairCount)
        Log.i(TAG, results.toString())


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
    ): LlamaGeneration {
        val streamed = StringBuilder()
        val generation = session.generate(
            prompt = prompt,
            systemPrompt = systemPrompt,
            schemaJson = schemaJson,
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

    private fun ensureMemoryHealthy(context: Context, stage: String): JSONObject {
        val snapshot = memorySnapshot(context)
        check(!snapshot.getBoolean("low_memory")) {
            "Android reports lowMemory after $stage; stopping the comparison"
        }
        return snapshot
    }

    private fun memorySnapshot(context: Context): JSONObject {
        val memory = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager.getMemoryInfo(memory)
        val pssKb = activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: -1
        return JSONObject()
            .put("avail_mem_bytes", memory.availMem)
            .put("low_memory", memory.lowMemory)
            .put("threshold_bytes", memory.threshold)
            .put("total_mem_bytes", memory.totalMem)
            .put("process_pss_kb", pssKb)
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
        .put("decode_ms", decodeMs)
        .put("decode_tokens_per_second", decodeTokensPerSecond)
        .put("total_ms", totalMs)
        .put("wall_to_usable_ms", if (appValid) elapsedMs(wallStartNanos) else JSONObject.NULL)
        .put("attempt_wall_ms", elapsedMs(wallStartNanos))
        .put("retry_count", retries)
        .put("repair_count", retries)
        .put("prompt_tokens", promptTokens)
        .put("generated_tokens", generatedTokens)
        .put("memory", memory)

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
        private const val MODEL_FILE_NAME = "LFM2.5-2.6B-Q4_K_M.gguf"
        private const val PROBE_APPLICATION_ID = "com.kernel.ai.debug.llamaprobe"
    }
}
