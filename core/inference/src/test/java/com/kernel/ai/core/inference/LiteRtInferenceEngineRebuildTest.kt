package com.kernel.ai.core.inference

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * #1920: every native `createConversation` is a GPU conversation rebuild. The counter is the
 * fake [Engine]'s `createConversation` call count, so these tests observe real rebuilds.
 */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class LiteRtInferenceEngineRebuildTest {
    private val context = mockk<Context>(relaxed = true)
    private val hardwareProfileDetector = mockk<HardwareProfileDetector>(relaxed = true)
    private val callbacks = Channel<MessageCallback>(Channel.UNLIMITED)
    private val inferenceEngine = LiteRtInferenceEngine(context, hardwareProfileDetector)
    private val config = ModelConfig(
        modelPath = "/models/test.litertlm",
        backendType = BackendType.CPU,
        systemPrompt = SYSTEM_PROMPT_A,
    )
    private val engineFake = mockk<Engine>(relaxed = true)
    private val warmConversation = mockk<Conversation>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(InferenceGenerationService.Companion)
        every { InferenceGenerationService.start(any()) } just runs
        every { InferenceGenerationService.stop(any()) } just runs
        mockkStatic(Settings.Global::class)
        every { Settings.Global.getInt(any<ContentResolver>(), "pr1756_gpu_restart", 0) } returns 0
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every {
            warmConversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        } answers {
            callbacks.trySend(secondArg<MessageCallback>()).getOrThrow()
        }
        every { engineFake.createConversation(any()) } returns mockk(relaxed = true)
        setPrivateField(inferenceEngine, "engine", engineFake)
        setPrivateField(inferenceEngine, "currentConfig", config)
        setPrivateField(inferenceEngine, "conversation", warmConversation)
    }

    @AfterEach
    fun tearDown() {
        callbacks.close()
        unmockkStatic(Log::class)
        unmockkStatic(Settings.Global::class)
        unmockkObject(InferenceGenerationService.Companion)
    }

    @Test
    fun `warm clean conversation - reset and unchanged prompt install cause no rebuild`() = runBlocking {
        withTimeout(3_000) { inferenceEngine.resetConversation() }
        withTimeout(3_000) { inferenceEngine.updateSystemPrompt(SYSTEM_PROMPT_A) }

        verify(exactly = 0) { engineFake.createConversation(any()) }
    }

    @Test
    fun `warm conversation after a turn - reset rebuilds exactly once`() = runBlocking {
        val results = async { inferenceEngine.generate("tell me a short story about a dog").toList() }
        withTimeout(3_000) { callbacks.receive() }.onDone()
        withTimeout(3_000) { results.await() }

        withTimeout(3_000) { inferenceEngine.resetConversation() }

        verify(exactly = 1) { engineFake.createConversation(any()) }
    }

    @Test
    fun `cold engine without conversation - reset rebuilds exactly once`() = runBlocking {
        setPrivateField(inferenceEngine, "conversation", null)

        withTimeout(3_000) { inferenceEngine.resetConversation() }

        verify(exactly = 1) { engineFake.createConversation(any()) }
    }

    @Test
    fun `changed system prompt on clean conversation rebuilds exactly once`() = runBlocking {
        withTimeout(3_000) { inferenceEngine.updateSystemPrompt("system prompt B") }

        verify(exactly = 1) { engineFake.createConversation(any()) }
    }

    @Test
    fun `one-shot generation on the shared conversation makes the next reset rebuild once`() = runBlocking {
        val title = async { inferenceEngine.generateOnce("generate a title") }
        withTimeout(3_000) { callbacks.receive() }.onDone()
        withTimeout(3_000) { title.await() }

        withTimeout(3_000) { inferenceEngine.resetConversation() }
        withTimeout(3_000) { inferenceEngine.resetConversation() }

        verify(exactly = 1) { engineFake.createConversation(any()) }
    }

    @Test
    fun `failed rebuild is retried by the next reset instead of skipped as clean`() = runBlocking {
        every { engineFake.createConversation(any()) } throws IllegalStateException("GPU OOM") andThen
            mockk<Conversation>(relaxed = true)

        val firstFailure = runCatching {
            withTimeout(3_000) { inferenceEngine.updateSystemPrompt("system prompt B") }
        }.exceptionOrNull()
        withTimeout(3_000) { inferenceEngine.resetConversation() }

        assertInstanceOf(IllegalStateException::class.java, firstFailure)
        verify(exactly = 2) { engineFake.createConversation(any()) }
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private companion object {
        const val SYSTEM_PROMPT_A = "system prompt A"
    }
}
