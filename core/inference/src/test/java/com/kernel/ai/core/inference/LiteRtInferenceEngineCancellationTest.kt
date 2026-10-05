package com.kernel.ai.core.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.MessageCallback
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class LiteRtInferenceEngineCancellationTest {
    private val context = mockk<Context>(relaxed = true)
    private val hardwareProfileDetector = mockk<HardwareProfileDetector>(relaxed = true)
    private val conversation = mockk<Conversation>(relaxed = true)
    private val callbacks = Channel<MessageCallback>(Channel.UNLIMITED)
    private val inferenceEngine = LiteRtInferenceEngine(context, hardwareProfileDetector)

    @BeforeEach
    fun setUp() {
        mockkObject(InferenceGenerationService.Companion)
        every { InferenceGenerationService.start(any()) } just runs
        every { InferenceGenerationService.stop(any()) } just runs
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every {
            conversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        } answers {
            callbacks.trySend(secondArg<MessageCallback>()).getOrThrow()
        }
        setPrivateField(inferenceEngine, "conversation", conversation)
    }

    @AfterEach
    fun tearDown() {
        callbacks.close()
        unmockkStatic(Log::class)
        unmockkObject(InferenceGenerationService.Companion)
    }

    @Test
    fun `cancel closes stream without native terminal callback and allows reset and reuse`() = runBlocking {
        val firstResults = async { inferenceEngine.generate("first request").toList() }
        val firstCallback = nextCallback()

        inferenceEngine.cancelGeneration()

        val cancelledResults = withTimeout(3_000) { firstResults.await() }
        assertTrue(cancelledResults.isEmpty())
        assertFalse(inferenceEngine.isGenerating.value)
        verify(exactly = 1) { conversation.cancelProcess() }
        verify(exactly = 1) { InferenceGenerationService.stop(context) }

        // Native callbacks may arrive after cancelProcess(), even after the caller sees completion.
        firstCallback.onMessage(mockk(relaxed = true))
        firstCallback.onDone()
        firstCallback.onError(kotlinx.coroutines.CancellationException("late native terminal"))
        assertTrue(cancelledResults.isEmpty())
        verify(exactly = 0) {
            Log.d("LiteRtInferenceEngine", match { it.contains("type=callback") })
        }

        val replacementConversation = mockk<Conversation>(relaxed = true)
        every {
            replacementConversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        } answers {
            callbacks.trySend(secondArg<MessageCallback>()).getOrThrow()
        }
        val runtime = mockk<Engine>(relaxed = true)
        every { runtime.createConversation(any()) } returns replacementConversation
        setPrivateField(inferenceEngine, "engine", runtime)
        setPrivateField(
            inferenceEngine,
            "currentConfig",
            ModelConfig(modelPath = "/models/test.litertlm", backendType = BackendType.CPU),
        )

        withTimeout(3_000) { inferenceEngine.resetConversation() }

        val nextResults = async { inferenceEngine.generate("request after reset").toList() }
        val nextCallback = nextCallback()
        nextCallback.onDone()
        val completedResults = withTimeout(3_000) { nextResults.await() }
        assertEquals(1, completedResults.count { it is GenerationResult.Complete })
        verify(exactly = 1) { replacementConversation.getBenchmarkInfo() }
    }

    @Test
    fun `native error still fails the caller flow`() = runBlocking {
        val result = async {
            try {
                inferenceEngine.generate("error request").toList()
                null
            } catch (throwable: Throwable) {
                throwable
            }
        }
        val callback = nextCallback()
        callback.onError(IllegalStateException("native failure"))

        val error = withTimeout(3_000) { result.await() }
        assertInstanceOf(InferenceException::class.java, error)
        assertEquals("Generation failed: native failure", error?.message)
        assertFalse(inferenceEngine.isGenerating.value)
        verify(exactly = 1) { InferenceGenerationService.stop(context) }
    }

    private suspend fun nextCallback(): MessageCallback = withTimeout(3_000) { callbacks.receive() }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }
}
