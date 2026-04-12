package com.kernel.ai.core.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Handles user messages via FunctionGemma-270M using the LiteRT-LM native ToolSet API.
 *
 * FunctionGemma is fine-tuned to use the SDK's `@Tool`/`ToolSet`/`ToolProvider` mechanism.
 * Tools are registered with [ConversationConfig] so the model genuinely sees them — no
 * manual JSON schema injection or text-output parsing required.
 *
 * FunctionGemma runs on CPU (XNNPACK) and is always-hot — loaded at app start.
 * It never shares its session with the main conversation (isolated Engine + Conversation).
 *
 * Output contract:
 * - Non-null [String] → final model text response after any tool calls completed internally
 * - `null`            → router not ready; caller should fall through to Gemma-4
 */
@Singleton
class FunctionGemmaRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("UnusedPrivateMember")
    private val hardwareProfileDetector: HardwareProfileDetector,
    private val toolSet: KernelAIToolSet,
) {
    /**
     * Result of a [handle] call.
     *
     * [ToolHandled] — a `@Tool` method was invoked; display [response] to the user.
     * [PlainResponse] — FunctionGemma responded but called no tool; fall through to Gemma-4.
     * [NotReady] — router not yet initialised; fall through to Gemma-4.
     */
    sealed class HandleResult {
        data class ToolHandled(val response: String) : HandleResult()
        data class PlainResponse(val response: String) : HandleResult()
        object NotReady : HandleResult()
    }

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Initialises FunctionGemma on [LlmDispatcher].
     *
     * Always forces CPU/XNNPACK backend — FunctionGemma-270M is the always-hot router and
     * must not compete with the main model on GPU/NPU resources.
     *
     * Tools are registered via [KernelAIToolSet] through the SDK's native `ToolProvider`
     * mechanism — the model sees the tool declarations directly with no system-prompt hacking.
     *
     * @param functionGemmaPath Absolute path to the `.litertlm` model file on disk.
     */
    suspend fun initialize(functionGemmaPath: String) {
        withContext(LlmDispatcher) {
            _isReady.value = false
            // Close any previously-held resources before re-initialising to avoid native memory leaks.
            try { conversation?.close() } catch (e: Exception) { Log.w(TAG, "FunctionGemmaRouter: close prev conv: ${e.message}") }
            try { engine?.close() } catch (e: Exception) { Log.w(TAG, "FunctionGemmaRouter: close prev engine: ${e.message}") }
            conversation = null
            engine = null
            try {
                Log.i(TAG, "FunctionGemmaRouter: initialising from $functionGemmaPath")

                val engineConfig = EngineConfig(
                    modelPath = functionGemmaPath,
                    backend = BackendType.CPU.toBackend(context),
                    maxNumTokens = MAX_TOKENS,
                )
                val eng = Engine(engineConfig)
                eng.initialize()

                val toolProvider = tool(toolSet)
                val samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                val convConfig = ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = buildSystemPrompt(),
                    tools = listOf(toolProvider),
                )
                val conv = eng.createConversation(convConfig)

                engine = eng
                conversation = conv
                _isReady.value = true
                Log.i(TAG, "FunctionGemmaRouter: ready (CPU/XNNPACK, maxTokens=$MAX_TOKENS)")
            } catch (e: Exception) {
                Log.e(TAG, "FunctionGemmaRouter: initialisation failed — ${e.message}", e)
                _isReady.value = false
            }
        }
    }

    /**
     * Sends [userMessage] to FunctionGemma. The SDK invokes any matching `@Tool` methods
     * automatically and feeds their results back to the model before generating the final
     * text reply.
     *
     * Must only be called after [initialize] completes successfully (i.e., [isReady] is true).
     * Returns `null` if the router is not ready — caller should fall through to Gemma-4.
     *
     * Runs on [LlmDispatcher] — safe to call from any coroutine context.
     */
    suspend fun handle(userMessage: String): HandleResult = withContext(LlmDispatcher) {
        val conv = conversation
        if (conv == null) {
            Log.w(TAG, "FunctionGemmaRouter: handle() called before initialisation — falling back")
            return@withContext HandleResult.NotReady
        }

        try {
            toolSet.resetTurnState()
            val sb = StringBuilder()
            val latch = CompletableDeferred<Unit>()

            conv.sendMessageAsync(
                Contents.of(Content.Text(userMessage)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        if (text.isNotEmpty() && !text.startsWith("<ctrl")) sb.append(text)
                    }

                    override fun onDone() {
                        latch.complete(Unit)
                    }

                    override fun onError(throwable: Throwable) {
                        latch.completeExceptionally(throwable)
                    }
                },
            )
            latch.await()

            val raw = sb.toString().trim()
            Log.d(TAG, "FunctionGemmaRouter: response=\"$raw\" toolCalled=${toolSet.wasToolCalled()}")
            when {
                toolSet.wasToolCalled() -> HandleResult.ToolHandled(raw.ifEmpty { "Done." })
                raw.isNotEmpty() -> HandleResult.PlainResponse(raw)
                else -> HandleResult.PlainResponse("")   // router ready, model returned nothing; fall through to Gemma-4
            }
        } catch (e: Exception) {
            Log.e(TAG, "FunctionGemmaRouter: inference failed — ${e.message}", e)
            HandleResult.NotReady
        }
    }

    /** Releases native resources. Call from [androidx.lifecycle.ViewModel.onCleared]. */
    fun release() {
        try { conversation?.close() } catch (e: Exception) { Log.w(TAG, "FunctionGemmaRouter: close conversation: ${e.message}") }
        try { engine?.close() } catch (e: Exception) { Log.w(TAG, "FunctionGemmaRouter: close engine: ${e.message}") }
        conversation = null
        engine = null
        _isReady.value = false
        Log.i(TAG, "FunctionGemmaRouter: released")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the system instruction matching Edge Gallery's style.
     * No manual function declarations — those are provided via [ConversationConfig.tools].
     */
    private fun buildSystemPrompt(): Contents {
        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH))
        val dayStr = now.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
        return Contents.of(
            listOf(
                Content.Text("You are a model that can do function calling with the following functions"),
                Content.Text("Current date and time: $dateStr, Day: $dayStr"),
            ),
        )
    }

    private companion object {
        /**
         * Token budget for the router. The model file is the `ekv1024` variant, compiled with
         * a 1024-token KV cache — match that here.
         */
        private const val MAX_TOKENS = 1024
    }
}
