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
import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.skills.mealplan.MealPlanJsonParser
import com.kernel.ai.core.skills.mealplan.MealPlanQuantityValidator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaMealPlanComparisonTest {
    private val parser = MealPlanJsonParser()
    private val quantityValidator = MealPlanQuantityValidator()

    @Test
    fun probePackageIsIsolatedFromPersistedAppJobs() {
        assertProbeIsolation(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun compareLiteRtThenLlamaSequentiallyAndVerifyCancelReuse() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertProbeIsolation(context)
        val gemmaFile = gemmaModelFile(context, InstrumentationRegistry.getArguments().getString("gemmaModelPath"))
        val llamaFile = llamaModelFile(context, InstrumentationRegistry.getArguments().getString("llamaModelPath"))
        require(gemmaFile.isFile) { "Gemma model file is missing" }
        require(llamaFile.isFile) { "Qwen GGUF file is missing" }

        val preflight = memorySnapshot(context)
        val results = JSONObject()
            .put("probe_pid", Process.myPid())
            .put("preflight", preflight)
        var retryCount = 0
        var repairCount = 0
        Log.i(TAG, results.toString())

        check(!preflight.getBoolean("low_memory")) { "Android reports lowMemory before inference; stopping preflight" }

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
            gemmaEngine.initialize(gemmaConfig)
            val gemmaColdLoadMs = elapsedMs(coldStart)
            gemmaSamples.put("cold_load_ms", gemmaColdLoadMs)
                .put("backend", gemmaEngine.activeBackend.value?.name ?: "unknown")
                .put("resolved_context_tokens", gemmaEngine.resolvedMaxTokens.value)
            results.put("after_gemma_cold_load", ensureMemoryHealthy(context, "Gemma cold load"))

            val plan = generateWithOneRepair(
                prompt = MealPlanProbePrompts.PLAN_USER,
                generate = { prompt -> generateLiteRt(context, gemmaEngine, prompt) },
                textOf = Sample::text,
                validate = { text -> runCatching { parser.parsePlanDraft(text, expectedDays = 3) }.isSuccess to 0 },
            )
            retryCount += plan.retries
            repairCount += plan.retries
            gemmaSamples.put("plan", plan.value.toJson(plan.valid, coldStart, gemmaColdLoadMs, plan.retries))
            gemmaEngine.updateSystemPrompt(MealPlanProbePrompts.RECIPE_SYSTEM)
            val recipeStart = System.nanoTime()
            val recipe = generateWithOneRepair(
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
                    .put("normalized_ingredients", recipe.normalizedIngredients),
            )

            gemmaSamples.put("plan_and_recipe_wall_ms", elapsedMs(coldStart))

            gemmaEngine.updateSystemPrompt(MealPlanProbePrompts.PLAN_SYSTEM)
            val warmStart = System.nanoTime()
            val warmPlan = generateLiteRt(context, gemmaEngine, MealPlanProbePrompts.PLAN_USER)
            val warmPlanValid = runCatching { parser.parsePlanDraft(warmPlan.text, expectedDays = 3) }.isSuccess
            gemmaSamples.put("warm_plan", warmPlan.toJson(warmPlanValid, warmStart, coldLoadMs = 0))
                .put("warm_generation_wall_ms", elapsedMs(warmStart))
            results.put("after_gemma_generations", ensureMemoryHealthy(context, "Gemma plan and recipe"))
        } finally {
            gemmaEngine.shutdown()
        }
        results.put("gemma", gemmaSamples)
        Log.i(TAG, results.toString())

        val llamaColdStart = System.nanoTime()
        val llama = LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
        val llamaColdLoadMs = llama.loadMs
        val llamaSamples = JSONObject()
            .put("cold_load_ms", llamaColdLoadMs)
            .put("backend", "CPU")
            .put("context_tokens", LLAMA_CONTEXT_TOKENS)
        try {
            results.put("after_llama_cold_load", ensureMemoryHealthy(context, "llama.cpp cold load"))
            val plan = generateWithOneRepair(
                prompt = MealPlanProbePrompts.PLAN_USER,
                generate = { prompt ->
                    llama.generate(prompt, MealPlanProbePrompts.PLAN_SYSTEM, MealPlanProbeSchemas.plan())
                },
                textOf = LlamaGeneration::text,
                validate = { text -> runCatching { parser.parsePlanDraft(text, expectedDays = 3) }.isSuccess to 0 },
            )
            retryCount += plan.retries
            repairCount += plan.retries
            llamaSamples.put(
                "plan",
                plan.value.toJson(plan.valid, llamaColdStart, ensureMemoryHealthy(context, "llama plan"), plan.retries),
            )
            val recipeStart = System.nanoTime()
            val recipe = generateWithOneRepair(
                prompt = MealPlanProbePrompts.RECIPE_USER,
                generate = { prompt ->
                    llama.generate(prompt, MealPlanProbePrompts.RECIPE_SYSTEM, MealPlanProbeSchemas.recipe(expectedServings = 4))
                },
                textOf = LlamaGeneration::text,
                validate = ::validateRecipe,
            )
            retryCount += recipe.retries
            repairCount += recipe.retries
            llamaSamples.put(
                "recipe",
                recipe.value.toJson(recipe.valid, recipeStart, ensureMemoryHealthy(context, "llama recipe"), recipe.retries)
                    .put("normalized_ingredients", recipe.normalizedIngredients),
            )

            llamaSamples.put("plan_and_recipe_wall_ms", elapsedMs(llamaColdStart))

            val invalidSchemaFailure = runCatching {
                llama.generate("probe", "probe", """{"type":"array","items":{"type":"string"},"uniqueItems":true}""")
            }.exceptionOrNull()
            llamaSamples.put("invalid_schema_rejected", invalidSchemaFailure is UnsupportedSchemaException)
            assertTrue("unsupported schema was not rejected", invalidSchemaFailure is UnsupportedSchemaException)
            assertTrue("invalid schema left generation active", !llama.isGenerating)
            val nativeFailure = runCatching {
                llama.generate(
                    MealPlanProbePrompts.PLAN_USER,
                    MealPlanProbePrompts.PLAN_SYSTEM,
                    MealPlanProbeSchemas.plan(),
                    maxTokens = 1,
                )
            }.exceptionOrNull()
            llamaSamples.put("native_error_recovered", nativeFailure is IllegalStateException && !llama.isGenerating)
            assertTrue("native incomplete-generation error was not surfaced", nativeFailure is IllegalStateException)
            llama.awaitNativeIdle()
            assertTrue("native failure left generation active", !llama.isGenerating)

            val warmStart = System.nanoTime()
            val warmPlan = llama.generate(
                MealPlanProbePrompts.PLAN_USER,
                MealPlanProbePrompts.PLAN_SYSTEM,
                MealPlanProbeSchemas.plan(),
            )
            val warmPlanValid = runCatching { parser.parsePlanDraft(warmPlan.text, expectedDays = 3) }.isSuccess
            llamaSamples.put(
                "warm_plan",
                warmPlan.toJson(warmPlanValid, warmStart, ensureMemoryHealthy(context, "llama warm plan")),
            ).put("warm_generation_wall_ms", elapsedMs(warmStart))

            val cancellation = async(Dispatchers.Default) {
                llama.generate(
                    prompt = "Prepare a detailed multi-course meal plan with every step described in detail.",
                    systemPrompt = MealPlanProbePrompts.PLAN_SYSTEM,
                    schemaJson = MealPlanProbeSchemas.plan(),
                    maxTokens = 1024,
                )
            }
            withTimeout(30_000) {
                while (!llama.isGenerating) delay(25)
            }
            cancellation.cancel()
            cancellation.join()
            llama.awaitNativeIdle()
            ensureMemoryHealthy(context, "llama cancellation")
            assertTrue("generation job was not cancelled", cancellation.isCancelled)
            assertTrue("native generation remains active after cancellation", !llama.isGenerating)
            val reuseStart = System.nanoTime()
            val reused = llama.generate(
                MealPlanProbePrompts.PLAN_USER,
                MealPlanProbePrompts.PLAN_SYSTEM,
                MealPlanProbeSchemas.plan(),
            )
            val reuseValid = runCatching { parser.parsePlanDraft(reused.text, expectedDays = 3) }.isSuccess
            llamaSamples.put(
                "cancelled_then_reused",
                reused.toJson(reuseValid, reuseStart, ensureMemoryHealthy(context, "llama reuse")),
            )
            results.put("after_llama_generations", ensureMemoryHealthy(context, "llama cancellation and reuse"))
        } finally {
            llama.close()
        }

        val reloadStart = System.nanoTime()
        val reloaded = LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
        try {
            val reloadedPlan = reloaded.generate(
                MealPlanProbePrompts.PLAN_USER,
                MealPlanProbePrompts.PLAN_SYSTEM,
                MealPlanProbeSchemas.plan(),
            )
            val reloadedValid = runCatching { parser.parsePlanDraft(reloadedPlan.text, expectedDays = 3) }.isSuccess
            llamaSamples.put("reload_load_ms", reloaded.loadMs)
                .put("reload_to_usable_ms", elapsedMs(reloadStart))
                .put("after_unload_reload", reloadedPlan.toJson(reloadedValid, reloadStart, ensureMemoryHealthy(context, "llama unload/reload")))
        } finally {
            reloaded.close()
        }
        results.put("llama", llamaSamples)
        results.put("retry_count", retryCount).put("repair_count", repairCount)
        Log.i(TAG, results.toString())


        assertTrue("LiteRT plan failed application parsing", gemmaSamples.getJSONObject("plan").getBoolean("app_valid"))
        assertTrue("LiteRT recipe failed application parsing", gemmaSamples.getJSONObject("recipe").getBoolean("app_valid"))
        assertTrue("LiteRT warm plan failed application parsing", gemmaSamples.getJSONObject("warm_plan").getBoolean("app_valid"))

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
    private data class ValidatedOutput<T>(
        val value: T,
        val valid: Boolean,
        val normalizedIngredients: Int,
        val retries: Int,
    )

    private suspend fun <T> generateWithOneRepair(
        prompt: String,
        generate: suspend (String) -> T,
        textOf: (T) -> String,
        validate: (String) -> Pair<Boolean, Int>,
    ): ValidatedOutput<T> {
        var retries = 0
        while (true) {
            val attemptPrompt = if (retries == 0) prompt else {
                "$prompt\n\nThe previous response failed Jandal application validation. Correct the JSON and return only the corrected output."
            }
            val result = generate(attemptPrompt)
            val (valid, normalizedIngredients) = validate(textOf(result))
            if (valid || retries == 1) return ValidatedOutput(result, valid, normalizedIngredients, retries)
            retries++
        }
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

    private fun validateRecipe(text: String): Pair<Boolean, Int> = runCatching {
        val recipe = parser.parseRecipeDraft(text, expectedServings = 4)
        val items = quantityValidator.validateAndNormalize(recipe)
        val normalized = items.count { it.normalizationStatus == GroceryNormalizationStatus.NORMALIZED }
        val expected = listOf(
            Triple("chicken breast", "500", "g"),
            Triple("kumara", "400", "g"),
            Triple("carrot", "2", "whole"),
            Triple("red onion", "1", "whole"),
            Triple("olive oil", "15", "ml"),
            Triple("lemon", "1", "whole"),
        )
        val exactItems = expected.all { (name, amount, unit) ->
            val item = items.firstOrNull { it.ingredientName?.lowercase()?.contains(name) == true }
            item?.quantity == amount && item.unit == unit
        }
        (items.size == expected.size && normalized == expected.size && exactItems) to normalized
    }.getOrElse { false to 0 }

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
        if (override != null) return File(override)
        val externalModels = requireNotNull(context.getExternalFilesDir("models")) {
            "App-specific external model storage is unavailable"
        }
        return File(externalModels, MODEL_FILE_NAME)
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
        private const val CONTEXT_TOKENS = 4096
        private const val LLAMA_CONTEXT_TOKENS = 4096
        private const val MODEL_FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val PROBE_APPLICATION_ID = "com.kernel.ai.debug.llamaprobe"
    }
}
