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
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Routes user messages to either a skill or plain conversation using FunctionGemma-270M.
 *
 * FunctionGemma runs on CPU (XNNPACK, 4 threads) and is always-hot — loaded at app start.
 * It never shares its session with the main conversation (isolated Engine + Conversation).
 *
 * Output contract:
 * - Valid JSON object with "name" field → [RouteDecision.SkillCall]
 * - Anything else (including "CONVERSATION") → [RouteDecision.PlainConversation]
 * - Engine not yet initialised → [RouteDecision.RouterNotReady]
 */
@Singleton
class FunctionGemmaRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("UnusedPrivateMember")
    private val hardwareProfileDetector: HardwareProfileDetector,
) {
    sealed class RouteDecision {
        data class SkillCall(val rawJson: String) : RouteDecision()
        object PlainConversation : RouteDecision()
        object RouterNotReady : RouteDecision()
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
     * @param functionGemmaPath Absolute path to the `.litertlm` model file on disk.
     * @param functionDeclarationsJson JSON array of available skill declarations injected
     *        into the system prompt so the model knows which functions it may call.
     */
    suspend fun initialize(functionGemmaPath: String, functionDeclarationsJson: String) {
        withContext(LlmDispatcher) {
            _isReady.value = false
            try {
                Log.i(TAG, "FunctionGemmaRouter: initialising from $functionGemmaPath")

                val engineConfig = EngineConfig(
                    modelPath = functionGemmaPath,
                    backend = BackendType.CPU.toBackend(context),
                    maxNumTokens = MAX_TOKENS,
                )
                val eng = Engine(engineConfig)
                eng.initialize()

                val systemInstruction = buildSystemPrompt(functionDeclarationsJson)
                val samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                val convConfig = ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = Contents.of(Content.Text(systemInstruction)),
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
     * Routes [userMessage] to a skill call or plain conversation.
     *
     * Must only be called after [initialize] completes successfully (i.e., [isReady] is true).
     * If the router is not ready, returns [RouteDecision.RouterNotReady] so the caller can
     * fall through to Gemma-4.
     *
     * Runs on [LlmDispatcher] — safe to call from any coroutine context.
     */
    suspend fun route(userMessage: String): RouteDecision = withContext(LlmDispatcher) {
        val conv = conversation
        if (conv == null) {
            Log.w(TAG, "FunctionGemmaRouter: route() called before initialisation — falling back")
            return@withContext RouteDecision.RouterNotReady
        }

        try {
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
            Log.d(TAG, "FunctionGemmaRouter: raw output=\"$raw\"")
            parseRouteDecision(raw)
        } catch (e: Exception) {
            Log.e(TAG, "FunctionGemmaRouter: inference failed — ${e.message}", e)
            RouteDecision.RouterNotReady
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
     * Parses the raw model output and returns the appropriate [RouteDecision].
     *
     * Strips code fences (same pattern as `SkillExecutor.parseSkillCall`) then tries to
     * deserialise as a JSON object with a non-blank "name" field.
     */
    private fun parseRouteDecision(raw: String): RouteDecision {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = org.json.JSONObject(cleaned)
            val name = json.optString("name").takeIf { it.isNotBlank() }
            if (name != null) {
                Log.d(TAG, "FunctionGemmaRouter: skill call detected — name=$name")
                RouteDecision.SkillCall(cleaned)
            } else {
                Log.d(TAG, "FunctionGemmaRouter: JSON has no 'name' field — plain conversation")
                RouteDecision.PlainConversation
            }
        } catch (e: Exception) {
            // Not valid JSON — treat as plain conversation (includes "CONVERSATION" literal)
            Log.d(TAG, "FunctionGemmaRouter: non-JSON output — plain conversation")
            RouteDecision.PlainConversation
        }
    }

    private fun buildSystemPrompt(functionDeclarationsJson: String): String = """
You are a function router. Given a user message, decide if it requires a function call or a normal conversation response.

Available functions:
$functionDeclarationsJson

If the user message requires a function call, respond with ONLY a JSON object:
{"name": "function_name", "arguments": {"param": "value"}}

If the user message is a normal conversation, respond with ONLY the word: CONVERSATION

Do not add any explanation or extra text.
    """.trimIndent()

    private companion object {
        /**
         * Token budget for the router. The model file is the `ekv1024` variant, compiled with
         * a 1024-token KV cache — match that here. 256 was too small for the system prompt +
         * function declarations, causing "Input token ids are too long" on every inference call.
         */
        private const val MAX_TOKENS = 1024
    }
}
