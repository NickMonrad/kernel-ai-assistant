package com.kernel.ai.debug.llamaprobe

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun compareLiteRtThenLlamaSequentiallyAndVerifyCancelReuse() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(InstrumentationRegistry.getArguments().getString(ProbeIsolatedRunner.ARG_PROBE_ONLY) == "true") {
            "Run only with probeOnly=true to suppress Jandal application startup"
        }
        val gemmaFile = gemmaModelFile(context, InstrumentationRegistry.getArguments().getString("gemmaModelPath"))
        val llamaFile = llamaModelFile(context, InstrumentationRegistry.getArguments().getString("llamaModelPath"))
        require(gemmaFile.isFile) { "Gemma model file is missing" }
        require(llamaFile.isFile) { "Qwen GGUF file is missing" }

        val preflight = memorySnapshot(context)
        val results = JSONObject()
            .put("probe_pid", Process.myPid())
            .put("preflight", preflight)
            .put("retry_count", 0)
            .put("repair_count", 0)
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
        var gemmaColdLoadMs = 0L
        val gemmaSamples = JSONObject().put("model_file", gemmaFile.name)
        val gemmaEngine = newLiteRtEngine(context)
        try {
            val gemmaLoadStart = System.nanoTime()
            gemmaEngine.initialize(gemmaConfig)
            gemmaColdLoadMs = elapsedMs(gemmaLoadStart)
            gemmaSamples.put("cold_load_ms", gemmaColdLoadMs)
                .put("backend", gemmaEngine.activeBackend.value?.name ?: "unknown")
                .put("resolved_context_tokens", gemmaEngine.resolvedMaxTokens.value)
            results.put("after_gemma_cold_load", ensureMemoryHealthy(context, "Gemma cold load"))
            val gemmaPlan = generateLiteRt(context, gemmaEngine, MealPlanProbePrompts.PLAN_USER)
            val gemmaPlanValid = runCatching { parser.parsePlanDraft(gemmaPlan.text, expectedDays = 2) }.isSuccess
            gemmaSamples.put("plan", gemmaPlan.toJson(gemmaPlanValid, gemmaColdLoadMs))
            gemmaEngine.updateSystemPrompt(MealPlanProbePrompts.RECIPE_SYSTEM)
            val gemmaRecipe = generateLiteRt(context, gemmaEngine, MealPlanProbePrompts.RECIPE_USER)
            val gemmaRecipeValidation = validateRecipe(gemmaRecipe.text)
            gemmaSamples.put(
                "recipe",
                gemmaRecipe.toJson(gemmaRecipeValidation.first, gemmaColdLoadMs)
                    .put("normalized_ingredients", gemmaRecipeValidation.second),
            )
            gemmaSamples.put(
                "plan_and_recipe_wall_ms",
                gemmaColdLoadMs + gemmaPlan.totalMs + gemmaRecipe.totalMs,
            )
            results.put("after_gemma_generations", ensureMemoryHealthy(context, "Gemma plan and recipe"))
        } finally {
            gemmaEngine.shutdown()
        }
        val gemmaWarmEngine = newLiteRtEngine(context)
        val gemmaWarmLoadStart = System.nanoTime()
        try {
            gemmaWarmEngine.initialize(gemmaConfig)
            gemmaSamples.put("warm_reload_ms", elapsedMs(gemmaWarmLoadStart))
                .put("warm_reload_backend", gemmaWarmEngine.activeBackend.value?.name ?: "unknown")
                .put("warm_reload_context_tokens", gemmaWarmEngine.resolvedMaxTokens.value)
            results.put("after_gemma_warm_reload", ensureMemoryHealthy(context, "Gemma warm reload"))
        } finally {
            gemmaWarmEngine.shutdown()
        }
        results.put("gemma", gemmaSamples)
        Log.i(TAG, results.toString())

        val llamaCold = LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
        val llamaColdLoadMs = llamaCold.loadMs
        val afterLlamaColdLoad = memorySnapshot(context)
        llamaCold.close()
        check(!afterLlamaColdLoad.getBoolean("low_memory")) {
            "Android reports lowMemory after llama.cpp cold load; stopping before warm reload"
        }
        val llama = LlamaCppSession.load(llamaFile, contextSize = LLAMA_CONTEXT_TOKENS)
        val llamaSamples = JSONObject()
            .put("cold_load_ms", llamaColdLoadMs)
            .put("warm_reload_ms", llama.loadMs)
            .put("backend", "CPU")
            .put("context_tokens", LLAMA_CONTEXT_TOKENS)
        try {
            results.put("after_llama_warm_load", ensureMemoryHealthy(context, "llama.cpp warm load"))

            val llamaPlan = llama.generate(
                MealPlanProbePrompts.PLAN_USER,
                MealPlanProbePrompts.PLAN_SYSTEM,
                MealPlanProbeSchemas.plan(expectedDays = 2),
            )
            val llamaPlanValid = runCatching { parser.parsePlanDraft(llamaPlan.text, expectedDays = 2) }.isSuccess
            llamaSamples.put("plan", llamaPlan.toJson(llamaPlanValid, llama.loadMs, ensureMemoryHealthy(context, "llama plan")))
            val llamaRecipe = llama.generate(
                MealPlanProbePrompts.RECIPE_USER,
                MealPlanProbePrompts.RECIPE_SYSTEM,
                MealPlanProbeSchemas.recipe(expectedServings = 4),
            )
            val llamaRecipeValidation = validateRecipe(llamaRecipe.text)
            llamaSamples.put(
                "recipe",
                llamaRecipe.toJson(llamaRecipeValidation.first, llama.loadMs, ensureMemoryHealthy(context, "llama recipe"))
                    .put("normalized_ingredients", llamaRecipeValidation.second),
            )
            llamaSamples.put("plan_and_recipe_wall_ms", llama.loadMs + llamaPlan.totalMs + llamaRecipe.totalMs)

            val cancellation = async(Dispatchers.Default) {
                llama.generate(
                    prompt = "Prepare a detailed multi-course meal plan with every step described in detail.",
                    systemPrompt = MealPlanProbePrompts.PLAN_SYSTEM,
                    schemaJson = MealPlanProbeSchemas.plan(expectedDays = 2),
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
            val reused = llama.generate(
                MealPlanProbePrompts.PLAN_USER,
                MealPlanProbePrompts.PLAN_SYSTEM,
                MealPlanProbeSchemas.plan(expectedDays = 2),
            )
            val reuseValid = runCatching { parser.parsePlanDraft(reused.text, expectedDays = 2) }.isSuccess
            llamaSamples.put("cancelled_then_reused", reused.toJson(reuseValid, llama.loadMs, ensureMemoryHealthy(context, "llama reuse")))
            results.put("after_llama_generations", ensureMemoryHealthy(context, "llama cancellation and reuse"))
        } finally {
            llama.close()
        }
        results.put("llama", llamaSamples)
        Log.i(TAG, results.toString())

        assertTrue("LiteRT plan failed application parsing", gemmaSamples.getJSONObject("plan").getBoolean("app_valid"))
        assertTrue("LiteRT recipe failed application parsing", gemmaSamples.getJSONObject("recipe").getBoolean("app_valid"))
        assertTrue("llama.cpp plan failed application parsing", llamaSamples.getJSONObject("plan").getBoolean("app_valid"))
        assertTrue("llama.cpp recipe failed application parsing", llamaSamples.getJSONObject("recipe").getBoolean("app_valid"))
        assertEquals(6, llamaSamples.getJSONObject("recipe").getInt("normalized_ingredients"))
        assertTrue("llama.cpp failed to reuse after cancellation", llamaSamples.getJSONObject("cancelled_then_reused").getBoolean("app_valid"))
        assertEquals(0, results.getInt("retry_count"))
        assertEquals(0, results.getInt("repair_count"))
    }

    private fun newLiteRtEngine(context: Context) = LiteRtInferenceEngine(context, HardwareProfileDetector(context))

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

    private fun gemmaModelFile(context: Context, override: String?): File = override?.let(::File)
        ?: listOf(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B)
            .map { File(File(context.filesDir, "models"), it.fileName) }
            .firstOrNull(File::isFile)
        ?: error("No installed Gemma E4B/E2B model exists under the app models directory")

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

    private fun Sample.toJson(appValid: Boolean, modelLoadMs: Long): JSONObject = JSONObject()
        .put("app_valid", appValid)
        .put("ttft_ms", ttftMs)
        .put("decode_ms", decodeMs)
        .put("total_ms", totalMs)
        .put("cold_load_ms", modelLoadMs)
        .put("wall_to_usable_ms", if (appValid) modelLoadMs + totalMs else JSONObject.NULL)
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

    private fun LlamaGeneration.toJson(appValid: Boolean, modelLoadMs: Long, memory: JSONObject): JSONObject = JSONObject()
        .put("app_valid", appValid)
        .put("ttft_ms", timeToFirstTokenMs)
        .put("decode_ms", decodeMs)
        .put("decode_tokens_per_second", decodeTokensPerSecond)
        .put("total_ms", totalMs)
        .put("cold_load_ms", modelLoadMs)
        .put("wall_to_usable_ms", if (appValid) modelLoadMs + totalMs else JSONObject.NULL)
        .put("prompt_tokens", promptTokens)
        .put("generated_tokens", generatedTokens)
        .put("memory", memory)

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    companion object {
        private const val TAG = "MealPlanProbe"
        private const val CONTEXT_TOKENS = 4096
        private const val LLAMA_CONTEXT_TOKENS = 4096
        private const val MODEL_FILE_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    }
}
