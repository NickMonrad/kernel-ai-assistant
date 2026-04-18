package com.kernel.ai.core.inference

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.QuantizationVerifier
import java.io.File
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiteRtInferenceEngine"

/**
 * LiteRT-LM implementation of [InferenceEngine].
 *
 * Engine is a thread-safe singleton; Conversation is NOT thread-safe.
 * All operations are dispatched to [LlmDispatcher] (single named thread)
 * to guarantee safety and keep the "llm-inference" thread visible in profiling.
 *
 * Backend selection: AUTO tries NPU → GPU → CPU. The first backend that
 * initialises successfully is used for the lifetime of the engine.
 *
 * NPU note: SamplerConfig must be null when using Backend.NPU (hardware
 * sampler is used instead). This matches Gallery reference behaviour.
 */
@OptIn(ExperimentalApi::class)
@Singleton
class LiteRtInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardwareProfileDetector: HardwareProfileDetector,
) : InferenceEngine {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _activeBackend = MutableStateFlow<BackendType?>(null)
    override val activeBackend: StateFlow<BackendType?> = _activeBackend.asStateFlow()

    private val _resolvedMaxTokens = MutableStateFlow(0)
    override val resolvedMaxTokens: StateFlow<Int> = _resolvedMaxTokens.asStateFlow()

    private val _evictionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val evictionEvents: Flow<Unit> = _evictionEvents

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentConfig: ModelConfig? = null

    /** Ensures only one generation (chat or isolated) runs at a time. */
    private val generationMutex = Mutex()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Suspends until the screen is on and the device is interactive.
     *
     * GPU hardware is suspended when the screen is off — calling [createEngineWithFallback]
     * while the screen is off hangs indefinitely. This guard is called at the top of
     * [initialize] to prevent that. Polls [PowerManager.isInteractive] every 500ms so that
     * [LlmDispatcher] remains free for other queued work while waiting.
     */
    private suspend fun waitForScreenInteractive() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        Log.i(TAG, "Screen is off — waiting before GPU init (#609)")
        while (!pm.isInteractive) {
            delay(500)
        }
        Log.i(TAG, "Screen is on — proceeding with GPU init")
    }

    override suspend fun initialize(config: ModelConfig) {
        withContext(LlmDispatcher) {
            // GPU hardware is suspended when the screen is off; delay init until screen is on.
            waitForScreenInteractive()
            _isReady.value = false

            // Apply hardware-aware defaults when AUTO is specified.
            val profile = hardwareProfileDetector.profile
            val resolvedConfig = if (config.backendType == BackendType.AUTO) {
                config.copy(
                    backendType = profile.recommendedBackend,
                    maxTokens = safeTokenCount(config.maxTokens.coerceAtMost(profile.recommendedMaxTokens)),
                )
            } else {
                config.copy(maxTokens = safeTokenCount(config.maxTokens))
            }

            Log.i(TAG, "Initializing engine — model: ${resolvedConfig.modelPath}, " +
                "backend: ${resolvedConfig.backendType}, tier: ${profile.tier}, " +
                "maxTokens: ${resolvedConfig.maxTokens} (requested: ${config.maxTokens})")

            // Sanity-check quantization before spending 10-30s initializing.
            QuantizationVerifier.verify(
                modelFile = File(resolvedConfig.modelPath),
                expectedBytes = estimateExpectedBytes(resolvedConfig.modelPath),
            )

            // Start foreground service to keep process at high OOM priority during
            // the ~20s GPU model load. Without this Samsung lmkd demotes the process
            // to oom_score_adj 700 (cached) and kills it for memory watermark reasons.
            InferenceLoadingService.start(context)
            try {
                val (eng, backendType) = createEngineWithFallback(resolvedConfig)
                engine = eng
                conversation = eng.createConversation(buildConversationConfig(backendType, resolvedConfig))
                resetConstrainedDecodingFlag()
                currentConfig = resolvedConfig
                _activeBackend.value = backendType
                _resolvedMaxTokens.value = resolvedConfig.maxTokens
                _isReady.value = true

                Log.i(TAG, "Engine ready — backend: $backendType, maxTokens: ${resolvedConfig.maxTokens}")
            } finally {
                InferenceLoadingService.stop(context)
            }
        }
    }

    override suspend fun resetConversation() {
        withContext(LlmDispatcher) {
            val eng = engine ?: return@withContext
            val config = currentConfig ?: return@withContext
            val backend = _activeBackend.value ?: BackendType.CPU

            safeClose(conversation, "conversation")
            conversation = eng.createConversation(buildConversationConfig(backend, config))
            resetConstrainedDecodingFlag()
            _isGenerating.value = false
        }
    }

    override suspend fun updateSystemPrompt(systemPrompt: String) {
        withContext(LlmDispatcher) {
            val eng = engine ?: return@withContext
            val config = currentConfig ?: return@withContext
            val backend = _activeBackend.value ?: BackendType.CPU

            currentConfig = config.copy(systemPrompt = systemPrompt)
            safeClose(conversation, "conversation")
            conversation = eng.createConversation(buildConversationConfig(backend, currentConfig!!))
            resetConstrainedDecodingFlag()
            _isGenerating.value = false
            Log.i(TAG, "System prompt updated and conversation reset")
        }
    }

    override suspend fun shutdown() {
        withContext(LlmDispatcher) {
            _isReady.value = false
            _isGenerating.value = false
            safeCancel(conversation)
            safeClose(conversation, "conversation")
            safeClose(engine, "engine")
            conversation = null
            engine = null
            _activeBackend.value = null
            _resolvedMaxTokens.value = 0
            currentConfig = null
            Log.i(TAG, "Engine shut down")
        }
    }

    /**
     * Fire-and-forget release triggered by Android memory pressure.
     * Marks the engine as not-ready immediately (so callers stop sending work),
     * then tears down the session and weights on [LlmDispatcher].
     * The engine can be reloaded lazily via [initialize] on the next use.
     */
    override fun releaseForMemoryPressure() {
        if (!_isReady.value) return // Already unloaded — nothing to do
        _isReady.value = false
        _isGenerating.value = false
        _evictionEvents.tryEmit(Unit) // Notify observers before async teardown
        CoroutineScope(LlmDispatcher + SupervisorJob()).launch {
            safeCancel(conversation)
            safeClose(conversation, "conversation")
            safeClose(engine, "engine")
            conversation = null
            engine = null
            _activeBackend.value = null
            currentConfig = null
            Log.i(TAG, "Engine released due to memory pressure")
        }
    }

    // -------------------------------------------------------------------------
    // Generation
    // -------------------------------------------------------------------------

    override fun generate(userMessage: String): Flow<GenerationResult> = callbackFlow {
        generationMutex.lock()
        val conv = conversation
        if (conv == null) {
            generationMutex.unlock()
            close(InferenceException("Engine not initialized — call initialize() first"))
            return@callbackFlow
        }

        _isGenerating.value = true
        val start = System.currentTimeMillis()
        var firstTokenMs: Long = -1
        var thinkingCharCount = 0

        try {
            conv.sendMessageAsync(
                Contents.of(Content.Text(userMessage)),
                object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Route thinking tokens separately (Gemma thinking mode)
                    val thinkingText = message.channels["thought"]
                    if (!thinkingText.isNullOrEmpty()) {
                        thinkingCharCount += thinkingText.length
                        trySend(GenerationResult.Thinking(thinkingText))
                    }

                    val text = message.toString()
                    if (text.isNotEmpty() && !text.startsWith("<ctrl")) {
                        if (firstTokenMs < 0) {
                            firstTokenMs = System.currentTimeMillis() - start
                            Log.i(TAG, "TTFT (Time to First Token): ${firstTokenMs}ms [backend=${_activeBackend.value}]")
                        }
                        trySend(GenerationResult.Token(text))
                    }
                }

                override fun onDone() {
                    val durationMs = System.currentTimeMillis() - start
                    if (thinkingCharCount > 0) {
                        Log.d("KernelAI", "Thinking tokens: $thinkingCharCount chars")
                    }
                    Log.i(TAG, "Generation complete: total=${durationMs}ms, TTFT=${firstTokenMs}ms [backend=${_activeBackend.value}]")
                    _isGenerating.value = false
                    trySend(GenerationResult.Complete(durationMs = durationMs))
                    close()
                }

                override fun onError(throwable: Throwable) {
                    _isGenerating.value = false
                    if (throwable is CancellationException) {
                        Log.i(TAG, "Generation cancelled by user")
                        close()
                    } else {
                        Log.e(TAG, "Generation error", throwable)
                        close(InferenceException("Generation failed: ${throwable.message}", throwable))
                    }
                }
            },
        )
        } catch (e: Exception) {
            _isGenerating.value = false
            generationMutex.unlock()
            close(InferenceException("sendMessageAsync failed: ${e.message}", e))
            return@callbackFlow
        }

        // When the Flow collector cancels (e.g. user navigates away), stop inference.
        awaitClose {
            _isGenerating.value = false
            conv.cancelProcess()
            generationMutex.unlock()
        }
    }.flowOn(LlmDispatcher)

    override fun cancelGeneration() {
        conversation?.cancelProcess()
        _isGenerating.value = false
    }

    /**
     * Generate a response using an **isolated conversation** that does not share KV
     * cache state with the active chat. Acquires [generationMutex] — if the engine
     * is currently generating, this suspends until the active generation completes.
     */
    override suspend fun generateOnce(prompt: String, systemPrompt: String?): String = withContext(LlmDispatcher) {
        // LiteRT only supports one session at a time — reuse the existing conversation
        // rather than calling createConversation() (which throws FAILED_PRECONDITION if
        // a session already exists). The title prompt is self-contained so it works
        // correctly within the existing session context.
        val conv = conversation ?: return@withContext ""

        // Use tryLock + retry loop to avoid potential deadlock with the single-threaded
        // dispatcher. The generate() flow's awaitClose needs to run on this same
        // dispatcher to unlock the mutex.
        var acquired = false
        repeat(20) { attempt ->
            if (generationMutex.tryLock()) {
                acquired = true
                return@repeat
            }
            Log.d(TAG, "generateOnce: mutex busy, retry ${attempt + 1}/20")
            kotlinx.coroutines.delay(250L)
        }
        if (!acquired) {
            Log.w(TAG, "generateOnce: failed to acquire mutex after 5s — engine busy")
            return@withContext ""
        }

        try {
            val sb = StringBuilder()
            val latch = CompletableDeferred<Unit>()
            conv.sendMessageAsync(
                Contents.of(Content.Text(prompt)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        if (text.isNotEmpty() && !text.startsWith("<ctrl")) sb.append(text)
                    }
                    override fun onDone() { latch.complete(Unit) }
                    override fun onError(throwable: Throwable) {
                        latch.completeExceptionally(throwable)
                    }
                },
            )
            latch.await()
            sb.toString()
        } finally {
            generationMutex.unlock()
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun createEngineWithFallback(config: ModelConfig): Pair<Engine, BackendType> {
        val orderedBackends: List<BackendType> = when (config.backendType) {
            BackendType.CPU -> listOf(BackendType.CPU)
            BackendType.GPU -> listOf(BackendType.GPU, BackendType.CPU)
            BackendType.NPU -> listOf(BackendType.NPU, BackendType.GPU, BackendType.CPU)
            BackendType.AUTO -> listOf(BackendType.GPU, BackendType.CPU)
        }

        var lastException: Exception? = null
        for (backendType in orderedBackends) {
            try {
                Log.d(TAG, "Trying backend: $backendType")
                val engineConfig = EngineConfig(
                    modelPath = config.modelPath,
                    backend = backendType.toBackend(context),
                    maxNumTokens = config.maxTokens,
                    cacheDir = context.cacheDir.absolutePath,
                )
                val eng = Engine(engineConfig)
                eng.initialize()
                Log.i(TAG, "Backend $backendType initialized successfully")
                return Pair(eng, backendType)
            } catch (e: Exception) {
                Log.w(TAG, "Backend $backendType failed: ${e.message}")
                lastException = e
            }
        }

        throw InferenceException(
            "All backends failed for ${config.modelPath}. Last error: ${lastException?.message}",
            lastException,
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun buildConversationConfig(
        backendType: BackendType,
        config: ModelConfig,
    ): ConversationConfig {
        // NPU uses hardware sampler — setting SamplerConfig causes a crash
        val samplerConfig = if (backendType == BackendType.NPU) null else SamplerConfig(
            topK = config.topK,
            topP = config.topP.toDouble(),
            temperature = config.temperature.toDouble(),
        )
        val systemInstruction = config.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { Contents.of(Content.Text(it)) }

        val tools = config.toolProvider?.let { listOf(it) } ?: emptyList()

        // When thinking is enabled, register the thought channel so the model emits
        // chain-of-thought tokens via message.channels["thought"]. Omitting the channel
        // disables thinking entirely — the model skips reasoning and responds directly.
        val channels = if (config.thinkingEnabled) {
            listOf(Channel("thought", "<|think|>", "<|/think|>"))
        } else {
            emptyList()
        }

        // Enable constrained decoding for well-formed tool calls (Google Gallery pattern).
        // Must be set before createConversation() and reset after via resetConstrainedDecodingFlag().
        if (tools.isNotEmpty()) {
            ExperimentalFlags.enableConversationConstrainedDecoding = true
        }

        return ConversationConfig(
            samplerConfig = samplerConfig,
            systemInstruction = systemInstruction,
            tools = tools,
            channels = channels,
        )
    }

    /** Reset the experimental flag after each createConversation() call (Gallery pattern). */
    private fun resetConstrainedDecodingFlag() {
        ExperimentalFlags.enableConversationConstrainedDecoding = false
    }

    private fun safeCancel(conv: com.google.ai.edge.litertlm.Conversation?) {
        try { conv?.cancelProcess() } catch (e: Exception) { Log.w(TAG, "cancelProcess: ${e.message}") }
    }

    private fun safeClose(closeable: AutoCloseable?, label: String) {
        try { closeable?.close() } catch (e: Exception) { Log.w(TAG, "close $label: ${e.message}") }
    }

    /**
     * Returns a rough expected byte count for the given model path.
     * Falls back to 0 (skips quantization check) if the model isn't in [KernelModel].
     */
    private fun estimateExpectedBytes(modelPath: String): Long {
        val fileName = File(modelPath).name
        return com.kernel.ai.core.inference.download.KernelModel.entries
            .firstOrNull { it.fileName == fileName }
            ?.approxSizeBytes ?: 0L
    }

    companion object {
        /**
         * Avoids exact powers-of-2 token counts that trigger a buffer-alignment bug
         * in LiteRT's GPU `reshape::Eval` operation (observed on Adreno 740 / SM8550).
         * Nudges e.g. 4096→4000, 8192→8000 while leaving non-power-of-2 values untouched.
         */
        internal fun safeTokenCount(tokens: Int): Int {
            if (tokens <= 0) return tokens
            // Check if tokens is an exact power of 2
            if (tokens and (tokens - 1) == 0) {
                val safe = (tokens * 125) / 128  // ~97.6% — e.g. 4096→4000, 8192→8000
                Log.w("LiteRtInferenceEngine",
                    "Adjusted maxTokens from $tokens to $safe (avoid GPU reshape alignment bug)")
                return safe
            }
            return tokens
        }
    }
}
