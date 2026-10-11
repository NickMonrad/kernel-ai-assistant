package com.kernel.ai.core.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class LiteRtInferenceEngineToolContinuationTest {
    private data class SentRequest(
        val message: com.google.ai.edge.litertlm.Message,
        val callback: MessageCallback,
    )

    private data class Fixture(
        val manager: ToolManager,
        val initialConversation: Conversation,
        val replacementConversation: Conversation,
        val configSlot: io.mockk.CapturingSlot<ConversationConfig>,
    )

    private data class SdkCallback(
        val callbackClass: Class<*>,
        val instance: Any,
    )

    private val context = mockk<Context>(relaxed = true)
    private val hardwareProfileDetector = mockk<HardwareProfileDetector>(relaxed = true)
    private val inferenceEngine = LiteRtInferenceEngine(context, hardwareProfileDetector)
    private val requests = Channel<SentRequest>(Channel.UNLIMITED)
    private val debugLogMessages = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        mockkObject(InferenceGenerationService.Companion)
        every { InferenceGenerationService.start(any()) } just runs
        every { InferenceGenerationService.stop(any()) } just runs
        mockkStatic(Log::class)
        debugLogMessages.clear()
        every { Log.d(any<String>(), any<String>()) } answers {
            debugLogMessages += secondArg<String>()
            0
        }
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        requests.close()
        unmockkStatic(Log::class)
        unmockkObject(InferenceGenerationService.Companion)
    }

    @Test
    fun `provider abort skips native tool continuation settles conversation and permits next turn`() = runBlocking {
        val toolCalls = mutableListOf<String>()
        val provider = tool(object : ToolSet {
            @Tool(description = "Loads gateway instructions")
            fun load_skill(@ToolParam(description = "Skill name") skill_name: String): Map<String, String> {
                toolCalls += "load_skill:$skill_name"
                inferenceEngine.abortGenerationBeforeToolContinuation()
                return mapOf("result" to "instructions")
            }
        })
        val closeStarted = CompletableDeferred<Unit>()
        val allowClose = CountDownLatch(1)
        val fixture = installConversations(provider) {
            closeStarted.complete(Unit)
            allowClose.await(5, TimeUnit.SECONDS)
        }
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)

        val abortedTurn = async { inferenceEngine.generate("perform an action").toList() }
        val initialRequest = nextRequest()
        assertEquals(Role.USER, initialRequest.message.role)
        val sdkCallback = createSdkCallback(initialRequest.callback, sdkConversation)
        invokeSdkMessage(
            sdkCallback,
            toolCallJson("load_skill", "skill_name", "run_intent"),
        )
        assertTrue(requests.tryReceive().isFailure, "aborted provider callback must not queue a native continuation")
        completeSdkCallback(sdkCallback)

        assertTrue(requests.tryReceive().isFailure, "aborted tool result must not be sent to LiteRT")
        try {
            withTimeout(3_000) { closeStarted.await() }
            assertFalse(abortedTurn.isCompleted, "abort must await native conversation close")
            assertEquals(listOf("load_skill:run_intent"), toolCalls)

            val nextTurn = async { inferenceEngine.generate("what is 2 + 2").toList() }
            assertNull(
                withTimeoutOrNull(250) { requests.receive() },
                "next turn must wait until the old native conversation has closed",
            )
            allowClose.countDown()
            assertTrue(withTimeout(3_000) { abortedTurn.await() }.isEmpty())

            val nextRequest = nextRequest()
            assertEquals(Role.USER, nextRequest.message.role)
            assertTrue(fixture.configSlot.isCaptured)
            assertFalse(fixture.configSlot.captured.automaticToolCalling)
            invokeSdkCallback(nextRequest.callback, sdkConversation, assistantTextJson("4"))
            val nextResults = withTimeout(3_000) { nextTurn.await() }
            assertEquals("4", nextResults.filterIsInstance<GenerationResult.Token>().joinToString("") { it.text })
            assertTrue(nextResults.any { it is GenerationResult.Complete })
            verify(exactly = 1) {
                fixture.initialConversation.sendMessageAsync(
                    any<Contents>(),
                    any<MessageCallback>(),
                    any<Map<String, Any>>(),
                )
            }
            verify(exactly = 0) { fixture.initialConversation.cancelProcess() }
            verify(exactly = 1) { fixture.initialConversation.close() }
            verify(exactly = 1) {
                fixture.replacementConversation.sendMessageAsync(
                    any<Contents>(),
                    any<MessageCallback>(),
                    any<Map<String, Any>>(),
                )
            }
        } finally {
            allowClose.countDown()
        }
    }

    @Test
    fun `debug abort probe invokes provider callback without native continuation and next turn uses replacement`() = runBlocking {
        val toolCalls = mutableListOf<String>()
        val provider = tool(object : ToolSet {
            @Tool(description = "Loads gateway instructions")
            fun load_skill(@ToolParam(description = "Skill name") skill_name: String): Map<String, String> {
                toolCalls += "load_skill:$skill_name"
                inferenceEngine.abortGenerationBeforeToolContinuation()
                return mapOf("result" to "instructions")
            }
        })
        val fixture = installConversations(provider)

        val probeResults = withTimeout(3_000) {
            inferenceEngine.generateDebugLoadSkillAbortProbe().toList()
        }

        assertTrue(probeResults.isEmpty())
        assertEquals(listOf("load_skill:run_intent"), toolCalls)
        assertTrue(requests.tryReceive().isFailure, "synthetic tool callback must not enqueue a native request")
        verify(exactly = 0) {
            fixture.initialConversation.sendMessageAsync(
                any<Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
        verify(exactly = 0) {
            fixture.initialConversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Message>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
        verify(exactly = 1) { fixture.initialConversation.close() }
        verify(exactly = 0) { fixture.initialConversation.cancelProcess() }

        val followUp = async { inferenceEngine.generate("Explain why shadows form in one sentence.").toList() }
        val request = nextRequest()
        assertEquals(Role.USER, request.message.role)
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)
        invokeSdkCallback(request.callback, sdkConversation, assistantTextJson("Light is blocked."))
        val followUpResults = withTimeout(3_000) { followUp.await() }
        assertEquals("Light is blocked.", followUpResults.filterIsInstance<GenerationResult.Token>().joinToString("") { it.text })
        assertTrue(followUpResults.any { it is GenerationResult.Complete })
        verify(exactly = 1) {
            fixture.replacementConversation.sendMessageAsync(
                any<Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
    }

    @Test
    fun `debug abort probe fails closed when provider does not abort`() = runBlocking {
        val provider = tool(object : ToolSet {
            @Tool(description = "Loads gateway instructions")
            fun load_skill(@ToolParam(description = "Skill name") skill_name: String): Map<String, String> =
                mapOf("result" to "instructions")
        })
        val fixture = installConversations(provider)

        val failure = runCatching {
            withTimeout(3_000) { inferenceEngine.generateDebugLoadSkillAbortProbe().toList() }
        }.exceptionOrNull()

        assertInstanceOf(InferenceException::class.java, failure)
        assertTrue(requireNotNull(failure).message.orEmpty().contains("did not exceed its result budget"))
        assertTrue(requests.tryReceive().isFailure, "probe must not continue a tool result into LiteRT")
        verify(exactly = 0) {
            fixture.initialConversation.sendMessageAsync(
                any<Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
        verify(exactly = 0) {
            fixture.initialConversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Message>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
        verify(exactly = 0) { fixture.initialConversation.close() }
        verify(exactly = 0) { fixture.initialConversation.cancelProcess() }
    }

    @Test
    fun `direct terminal tool result still continues to the model`() = runBlocking {
        val executed = mutableListOf<String>()
        val provider = tool(object : ToolSet {
            @Tool(description = "Runs a device intent")
            fun run_intent(@ToolParam(description = "Intent name") intent_name: String): Map<String, String> {
                executed += intent_name
                return mapOf("result" to "action completed")
            }
        })
        val fixture = installConversations(provider)
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)

        val turn = async { inferenceEngine.generate("set an alarm").toList() }
        val initialRequest = nextRequest()
        val sdkCallback = createSdkCallback(initialRequest.callback, sdkConversation)
        invokeSdkMessage(sdkCallback, toolCallJson("run_intent", "intent_name", "set_alarm"))
        assertTrue(requests.tryReceive().isFailure, "tool result must wait for the native stage's onDone callback")
        completeSdkCallback(sdkCallback)

        val toolResponseRequest = nextRequest()
        assertToolResponse(toolResponseRequest, "run_intent", "action completed")
        invokeSdkCallback(toolResponseRequest.callback, sdkConversation, assistantTextJson("Alarm set."))

        val results = withTimeout(3_000) { turn.await() }
        assertEquals(listOf("set_alarm"), executed)
        assertEquals("Alarm set.", results.filterIsInstance<GenerationResult.Token>().joinToString("") { it.text })
        assertTrue(results.any { it is GenerationResult.Complete })
        verify(exactly = 1) {
            fixture.initialConversation.sendMessageAsync(
                any<Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
        verify(exactly = 1) {
            fixture.initialConversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Message>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
    }

    @Test
    fun `debug send trace correlates tool response continuation and redacts payloads`() = runBlocking {
        val promptMarker = "PRIVATE_PROMPT_MARKER"
        val argumentMarker = "PRIVATE_ARGUMENT_MARKER"
        val resultMarker = "PRIVATE_RESULT_MARKER"
        val provider = tool(object : ToolSet {
            @Tool(description = "Reads a system field")
            fun get_system_info(@ToolParam(description = "Field name") field: String): Map<String, String> {
                assertEquals(argumentMarker, field)
                return mapOf("result" to resultMarker)
            }
        })
        val fixture = installConversations(provider)
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)
        val turn = async {
            inferenceEngine.generateWithDebugTrace(promptMarker, "trace-42").toList()
        }

        val initialRequest = nextRequest()
        val sdkCallback = createSdkCallback(initialRequest.callback, sdkConversation)
        invokeSdkMessage(sdkCallback, toolCallJson("get_system_info", "field", argumentMarker))
        completeSdkCallback(sdkCallback)

        val toolResponseRequest = nextRequest()
        assertToolResponse(toolResponseRequest, "get_system_info", resultMarker)
        invokeSdkCallback(toolResponseRequest.callback, sdkConversation, assistantTextJson("done"))
        val results = withTimeout(3_000) { turn.await() }

        val trace = debugLogMessages.filter { it.startsWith("generation_diagnostic ") }
        assertEquals(6, trace.size)
        assertTrue(trace[0].contains("generation_id=trace-42 send_attempt=1 stage=initial_prompt event=send_start"))
        assertTrue(trace[1].contains("send_attempt=1 stage=initial_prompt event=tool_call_received"))
        assertTrue(trace[1].contains("tool_names=get_system_info count=1"))
        assertTrue(trace[2].contains("send_attempt=1 stage=initial_prompt event=tool_dispatch_result"))
        assertTrue(trace[2].contains("tool=get_system_info returned=true"))
        assertTrue(
            trace[2].contains(
                "serialized_result_tokens_estimate=" +
                    ContextWindowManager().estimateTokens("""{"result":"$resultMarker"}"""),
            ),
        )
        assertTrue(trace[3].contains("send_attempt=1 stage=initial_prompt event=on_done tool_response_pending=true"))
        assertTrue(trace[4].contains("send_attempt=2 stage=tool_response event=send_start"))
        assertTrue(trace[5].contains("send_attempt=2 stage=tool_response event=on_done tool_response_pending=false"))
        val traceText = trace.joinToString("\n")
        assertFalse(traceText.contains(promptMarker))
        assertFalse(traceText.contains(argumentMarker))
        assertFalse(traceText.contains(resultMarker))
        assertEquals("done", results.filterIsInstance<GenerationResult.Token>().joinToString("") { it.text })
    }

    @Test
    fun `debug send trace identifies native Status 9 callback stage without exception detail`() = runBlocking {
        val promptMarker = "PRIVATE_STATUS_PROMPT"
        val argumentMarker = "PRIVATE_STATUS_ARGUMENT"
        val resultMarker = "PRIVATE_STATUS_RESULT"
        val exceptionMarker = "PRIVATE_NATIVE_EXCEPTION_DETAIL"
        val provider = tool(object : ToolSet {
            @Tool(description = "Reads a system field")
            fun get_system_info(@ToolParam(description = "Field name") field: String): Map<String, String> {
                assertEquals(argumentMarker, field)
                return mapOf("result" to resultMarker)
            }
        })
        val fixture = installConversations(provider)
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)
        val turn = async {
            runCatching {
                inferenceEngine.generateWithDebugTrace(promptMarker, "status9-trace").toList()
            }.exceptionOrNull()
        }

        val initialRequest = nextRequest()
        val sdkCallback = createSdkCallback(initialRequest.callback, sdkConversation)
        invokeSdkMessage(sdkCallback, toolCallJson("get_system_info", "field", argumentMarker))
        completeSdkCallback(sdkCallback)
        val toolResponseRequest = nextRequest()
        toolResponseRequest.callback.onError(IllegalStateException("Status Code: 9 $exceptionMarker"))

        assertTrue(withTimeout(3_000) { turn.await() } != null)
        val trace = debugLogMessages.filter { it.startsWith("generation_diagnostic ") }
        assertTrue(
            trace.any {
                it.contains(
                    "generation_id=status9-trace send_attempt=2 stage=tool_response event=on_error " +
                        "exception=IllegalStateException status=9",
                )
            },
        )
        val traceText = trace.joinToString("\n")
        assertFalse(traceText.contains(promptMarker))
        assertFalse(traceText.contains(argumentMarker))
        assertFalse(traceText.contains(resultMarker))
        assertFalse(traceText.contains(exceptionMarker))
    }

    @Test
    fun `debug send trace identifies synchronous continuation send exception without exception detail`() = runBlocking {
        val promptMarker = "PRIVATE_THROW_PROMPT"
        val argumentMarker = "PRIVATE_THROW_ARGUMENT"
        val resultMarker = "PRIVATE_THROW_RESULT"
        val exceptionMarker = "PRIVATE_THROW_DETAIL"
        val provider = tool(object : ToolSet {
            @Tool(description = "Reads a system field")
            fun get_system_info(@ToolParam(description = "Field name") field: String): Map<String, String> {
                assertEquals(argumentMarker, field)
                return mapOf("result" to resultMarker)
            }
        })
        val fixture = installConversations(provider)
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)
        val turn = async {
            runCatching {
                inferenceEngine.generateWithDebugTrace(promptMarker, "throw-trace").toList()
            }.exceptionOrNull()
        }

        val initialRequest = nextRequest()
        val sdkCallback = createSdkCallback(initialRequest.callback, sdkConversation)
        invokeSdkMessage(sdkCallback, toolCallJson("get_system_info", "field", argumentMarker))
        every {
            fixture.initialConversation.sendMessageAsync(
                any<Message>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        } throws IllegalStateException("Status Code: 9 $exceptionMarker")
        completeSdkCallback(sdkCallback)

        assertTrue(withTimeout(3_000) { turn.await() } != null)
        assertTrue(requests.tryReceive().isFailure, "the thrown tool-response send must not enqueue a request")
        val trace = debugLogMessages.filter { it.startsWith("generation_diagnostic ") }
        assertTrue(
            trace.any {
                it.contains(
                    "generation_id=throw-trace send_attempt=2 stage=tool_response event=send_throw " +
                        "exception=IllegalStateException status=9",
                )
            },
        )
        val traceText = trace.joinToString("\n")
        assertFalse(traceText.contains(promptMarker))
        assertFalse(traceText.contains(argumentMarker))
        assertFalse(traceText.contains(resultMarker))
        assertFalse(traceText.contains(exceptionMarker))
    }

    @Test
    fun `one-shot generation still executes configured tools`() = runBlocking {
        val executed = mutableListOf<String>()
        val provider = tool(object : ToolSet {
            @Tool(description = "Runs a device intent")
            fun run_intent(@ToolParam(description = "Intent name") intent_name: String): Map<String, String> {
                executed += intent_name
                return mapOf("result" to "action completed")
            }
        })
        val fixture = installConversations(provider)
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)

        val response = async {
            inferenceEngine.generateOnce("set an alarm")
        }
        val initialRequest = nextRequest()
        assertEquals(Role.USER, initialRequest.message.role)
        val sdkCallback = createSdkCallback(initialRequest.callback, sdkConversation)
        invokeSdkMessage(sdkCallback, toolCallJson("run_intent", "intent_name", "set_alarm"))
        assertTrue(requests.tryReceive().isFailure, "one-shot tool result must wait for the SDK terminal callback")
        completeSdkCallback(sdkCallback)

        val toolResponseRequest = nextRequest()
        assertToolResponse(toolResponseRequest, "run_intent", "action completed")
        invokeSdkCallback(toolResponseRequest.callback, sdkConversation, assistantTextJson("Alarm set."))

        assertEquals("Alarm set.", withTimeout(3_000) { response.await() })
        assertEquals(listOf("set_alarm"), executed)
        verify(exactly = 1) {
            fixture.initialConversation.sendMessageAsync(
                any<Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
        verify(exactly = 1) {
            fixture.initialConversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Message>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
    }

    @Test
    fun `load skill then run intent keeps both tool continuations`() = runBlocking {
        val executed = mutableListOf<String>()
        val provider = tool(object : ToolSet {
            @Tool(description = "Loads gateway instructions")
            fun load_skill(@ToolParam(description = "Skill name") skill_name: String): Map<String, String> {
                executed += "load_skill:$skill_name"
                return mapOf("result" to "run_intent instructions")
            }

            @Tool(description = "Runs a device intent")
            fun run_intent(@ToolParam(description = "Intent name") intent_name: String): Map<String, String> {
                executed += "run_intent:$intent_name"
                return mapOf("result" to "action completed")
            }
        })
        val fixture = installConversations(provider)
        val sdkConversation = Conversation(0L, fixture.manager, automaticToolCalling = false)

        val turn = async { inferenceEngine.generate("set an alarm").toList() }
        val initialRequest = nextRequest()
        invokeSdkCallback(initialRequest.callback, sdkConversation, toolCallJson("load_skill", "skill_name", "run_intent"))

        val loadSkillResponse = nextRequest()
        assertToolResponse(loadSkillResponse, "load_skill", "run_intent instructions")
        invokeSdkCallback(loadSkillResponse.callback, sdkConversation, toolCallJson("run_intent", "intent_name", "set_alarm"))

        val runIntentResponse = nextRequest()
        assertToolResponse(runIntentResponse, "run_intent", "action completed")
        invokeSdkCallback(runIntentResponse.callback, sdkConversation, assistantTextJson("Alarm set."))

        val results = withTimeout(3_000) { turn.await() }
        assertEquals(listOf("load_skill:run_intent", "run_intent:set_alarm"), executed)
        assertEquals("Alarm set.", results.filterIsInstance<GenerationResult.Token>().joinToString("") { it.text })
        assertTrue(results.any { it is GenerationResult.Complete })
        verify(exactly = 1) {
            fixture.initialConversation.sendMessageAsync(
                any<Contents>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
        verify(exactly = 2) {
            fixture.initialConversation.sendMessageAsync(
                any<com.google.ai.edge.litertlm.Message>(),
                any<MessageCallback>(),
                any<Map<String, Any>>(),
            )
        }
    }

    private fun installConversations(
        provider: ToolProvider,
        onInitialClose: () -> Unit = {},
    ): Fixture {
        val manager = ToolManager(listOf(provider))
        val initialConversation = mockConversation(manager, onInitialClose)
        val replacementConversation = mockConversation(manager)
        val runtime = mockk<Engine>(relaxed = true)
        val configSlot = slot<ConversationConfig>()
        every { runtime.createConversation(capture(configSlot)) } returns replacementConversation
        setPrivateField(inferenceEngine, "conversation", initialConversation)
        setPrivateField(inferenceEngine, "engine", runtime)
        setPrivateField(
            inferenceEngine,
            "currentConfig",
            ModelConfig(
                modelPath = "/models/test.litertlm",
                backendType = BackendType.CPU,
                toolProvider = provider,
            ),
        )
        return Fixture(manager, initialConversation, replacementConversation, configSlot)
    }

    private fun mockConversation(manager: ToolManager, onClose: () -> Unit = {}): Conversation =
        mockk(relaxed = true) {
            every { toolManager } returns manager
            every { close() } answers { onClose() }
            every {
                sendMessageAsync(
                    any<Contents>(),
                    any<MessageCallback>(),
                    any<Map<String, Any>>(),
                )
            } answers {
                requests.trySend(SentRequest(Message.user(firstArg<Contents>()), secondArg())).getOrThrow()
            }
            every {
                sendMessageAsync(
                    any<com.google.ai.edge.litertlm.Message>(),
                    any<MessageCallback>(),
                    any<Map<String, Any>>(),
                )
            } answers {
                requests.trySend(SentRequest(firstArg(), secondArg())).getOrThrow()
            }
        }

    private suspend fun nextRequest(): SentRequest = withTimeout(3_000) { requests.receive() }

    /** Exercise LiteRT-LM 0.17.1's real JNI-to-MessageCallback adapter without calling native code. */
    private fun createSdkCallback(callback: MessageCallback, conversation: Conversation): SdkCallback {
        val callbackClass = conversation.javaClass.declaredClasses.single {
            it.simpleName == "JniMessageCallbackImpl"
        }
        val constructor = callbackClass.declaredConstructors.minBy { it.parameterCount }.apply {
            isAccessible = true
        }
        val arguments = arrayOfNulls<Any>(constructor.parameterCount).apply {
            this[0] = conversation
            this[1] = callback
        }
        return SdkCallback(callbackClass, constructor.newInstance(*arguments))
    }

    private fun invokeSdkMessage(sdkCallback: SdkCallback, messageJson: String) {
        sdkCallback.callbackClass.getDeclaredMethod("onMessage", String::class.java).apply {
            isAccessible = true
        }.invoke(sdkCallback.instance, messageJson)
    }

    private fun completeSdkCallback(sdkCallback: SdkCallback) {
        sdkCallback.callbackClass.getDeclaredMethod("onDone").apply {
            isAccessible = true
        }.invoke(sdkCallback.instance)
    }

    private fun invokeSdkCallback(callback: MessageCallback, conversation: Conversation, messageJson: String) {
        val sdkCallback = createSdkCallback(callback, conversation)
        invokeSdkMessage(sdkCallback, messageJson)
        completeSdkCallback(sdkCallback)
    }

    private fun assertToolResponse(request: SentRequest, expectedName: String, expectedResult: String) {
        assertEquals(Role.TOOL, request.message.role)
        val response = assertInstanceOf(Content.ToolResponse::class.java, request.message.contents.contents.single())
        assertEquals(expectedName, response.name)
        val result = assertInstanceOf(com.google.gson.JsonObject::class.java, response.response)
        assertEquals(expectedResult, result.get("result").asString)
    }

    private fun toolCallJson(toolName: String, argumentName: String, argumentValue: String): String =
        """{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"$toolName","arguments":{"$argumentName":"$argumentValue"}}}]}"""

    private fun assistantTextJson(text: String): String =
        """{"role":"assistant","content":[{"type":"text","text":"$text"}]}"""

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }
}
