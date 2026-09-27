package com.kernel.ai

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.LiteRtInferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Physical-device regression for #1566.
 *
 * Exercises the real LiteRT engine through a failed native initialization, then retries the same
 * [LiteRtInferenceEngine] instance against the already-downloaded E4B model. The test never clears
 * app data, mutates the installed model, or invokes the download manager.
 *
 * Run this class explicitly on the S23 Ultra; it is intentionally not a general connected-suite
 * smoke because loading E4B is expensive and device-specific.
 */
class LiteRtInferenceRecoveryDeviceTest {
    @Test
    fun nativeInitFailureCanRecoverUsingExistingE4B() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals(
            "This evidence test must target the debug app that owns the installed model",
            "com.kernel.ai.debug",
            context.packageName,
        )

        val e4b = KernelModel.GEMMA_4_E4B.localFile(context)
        assertTrue("Gemma E4B must already be installed at ${e4b.absolutePath}", e4b.isFile)

        val beforeSize = e4b.length()
        val beforeModified = e4b.lastModified()

        // A path that definitely does not exist reaches the production Engine.initialize() path
        // without modifying or copying the installed model.
        val missingModel = File(context.cacheDir, "pr1567-missing-${System.nanoTime()}.litertlm")
        if (missingModel.exists()) {
            assertTrue("Unable to remove stale missing-model fixture", missingModel.delete())
        }

        val engine = LiteRtInferenceEngine(context, HardwareProfileDetector(context))

        try {
            var initializationFailure: Throwable? = null
            try {
                withTimeout(INIT_TIMEOUT_MS) {
                    engine.initialize(
                        ModelConfig(
                            modelPath = missingModel.absolutePath,
                            backendType = BackendType.GPU,
                            maxTokens = TEST_MAX_TOKENS,
                            systemPrompt = TEST_SYSTEM_PROMPT,
                            thinkingEnabled = false,
                        ),
                    )
                }
            } catch (failure: Throwable) {
                initializationFailure = failure
            }

            assertNotNull("Expected the disposable model path to fail initialization", initializationFailure)
            assertFalse("Engine must remain not-ready after failed initialization", engine.isReady.value)
            Log.i(
                TAG,
                "PR1567_NATIVE_FAILURE type=${initializationFailure!!::class.java.simpleName} " +
                    "message=${initializationFailure!!.message}",
            )

            withTimeout(INIT_TIMEOUT_MS) {
                engine.initialize(
                    ModelConfig(
                        modelPath = e4b.absolutePath,
                        backendType = BackendType.GPU,
                        maxTokens = TEST_MAX_TOKENS,
                        systemPrompt = TEST_SYSTEM_PROMPT,
                        thinkingEnabled = false,
                    ),
                )
            }

            assertTrue("Engine must become ready after retrying with installed E4B", engine.isReady.value)
            assertEquals(e4b.absolutePath, engine.loadedModelPath)

            val output = StringBuilder()
            var generationError: String? = null
            withTimeout(GENERATION_TIMEOUT_MS) {
                engine.generate("Reply with only the word OK.").collect { result ->
                    when (result) {
                        is GenerationResult.Token -> output.append(result.text)
                        is GenerationResult.Error -> generationError = result.message
                        else -> Unit
                    }
                }
            }

            assertTrue(
                "Generation failed after recovery: $generationError",
                generationError == null,
            )
            assertTrue(
                "Expected at least one real model token after recovery",
                output.toString().isNotBlank(),
            )

            assertEquals("E4B size changed during recovery test", beforeSize, e4b.length())
            assertEquals(
                "E4B timestamp changed during recovery test",
                beforeModified,
                e4b.lastModified(),
            )

            Log.i(
                TAG,
                "PR1567_RECOVERY_RESULT backend=${engine.activeBackend.value} " +
                    "modelBytes=${e4b.length()} modelModified=${e4b.lastModified()} " +
                    "generatedChars=${output.length}",
            )
        } finally {
            withTimeout(SHUTDOWN_TIMEOUT_MS) {
                engine.shutdown()
            }
        }
    }

    private companion object {
        const val TAG = "PR1567Recovery"
        const val TEST_MAX_TOKENS = 1000
        const val INIT_TIMEOUT_MS = 180_000L
        const val GENERATION_TIMEOUT_MS = 90_000L
        const val SHUTDOWN_TIMEOUT_MS = 30_000L
        const val TEST_SYSTEM_PROMPT = "Reply briefly and directly."
    }
}
