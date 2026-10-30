package com.kernel.ai.core.inference

import android.content.Context
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LiteRtInferenceEngineResponseFormatTest {
    private val context = mockk<Context>(relaxed = true)
    private val hardware = mockk<HardwareProfileDetector>(relaxed = true)
    private val engine = LiteRtInferenceEngine(context, hardware)

    @BeforeEach
    fun setUp() {
        mockkObject(InferenceGenerationService.Companion)
        every { InferenceGenerationService.start(any()) } just runs
        every { InferenceGenerationService.stop(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(InferenceGenerationService.Companion)
    }

    @Test
    fun `response-format probe creates isolated tool-free constrained conversation`() = runBlocking {
        val runtime = mockk<Engine>(relaxed = true)
        val normalConversation = mockk<Conversation>(relaxed = true)
        val probeConversation = mockk<Conversation>(relaxed = true)
        val provider = mockk<ToolProvider>(relaxed = true)
        val configSlot = slot<ConversationConfig>()
        every { runtime.createConversation(capture(configSlot)) } returns probeConversation
        var capturedResponseFormat: ResponseFormat? = null
        every {
            probeConversation.sendMessageAsync(
                any<Contents>(),
                any<MessageCallback>(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } answers {
            capturedResponseFormat = lastArg<ResponseFormat?>()
            val callback = secondArg<MessageCallback>()
            callback.onMessage(mockk<Message> {
                every { toolCalls } returns emptyList()
                every { toString() } returns RESPONSE
            })
            callback.onDone()
        }
        setPrivateField(engine, "engine", runtime)
        setPrivateField(engine, "conversation", normalConversation)
        setPrivateField(
            engine,
            "currentConfig",
            ModelConfig(
                modelPath = "/models/e2b.litertlm",
                backendType = BackendType.CPU,
                toolProvider = provider,
            ),
        )

        val result = engine.generateResponseFormatProbeOnce("resolve pending action", SCHEMA)

        assertEquals(RESPONSE, result)
        assertTrue(configSlot.isCaptured)
        assertEquals(true, configSlot.captured.enableResponseFormat)
        assertEquals(false, configSlot.captured.automaticToolCalling)
        assertTrue(configSlot.captured.tools.isEmpty())
        assertTrue(capturedResponseFormat != null)
        verify(exactly = 1) { probeConversation.close() }
        verify(exactly = 1) { InferenceGenerationService.start(context) }
        verify(exactly = 1) { InferenceGenerationService.stop(context) }
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private companion object {
        const val SCHEMA = """{"type":"object","required":["transition"],"properties":{"transition":{"type":"string","enum":["CONTINUE","CANCEL","REPLACE","CLARIFY"]}}}"""
        const val RESPONSE = """{"transition":"CONTINUE"}"""
    }
}
