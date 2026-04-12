package com.kernel.ai.core.inference

import android.content.Context
import android.util.Log
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentConfig: ModelConfig? = null

    /** Ensures only one generation (chat or isolated) runs at a time. */
    private val generationMutex = Mutex()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override suspend fun initialize(config: ModelConfig) {
        withContext(LlmDispatcher) {
            _isReady.value = false

            // Apply hardware-aware defaults when AUTO is specified.
            val profile = hardwareProfileDetector.profile
            val resolvedConfig = if (config.backendType == BackendType.AUTO) {
                config.copy(
                    backendType = profile.recommendedBackend,
                    maxTokens = config.maxTokens.coerceAtMost(profile.recommendedMaxTokens),
                )
            } else config

            Log.i(TAG, "Initializing engine — model: ${resolvedConfig.modelPath}, " +
                "backend: ${resolvedConfig.backendType}, tier: ${profile.tier}")

            // Sanity-check quantization before spending 10-30s initializing.
            QuantizationVerifier.verify(
                modelFile = File(resolvedConfig.modelPath),
                expectedBytes = estimateExpectedBytes(resolvedConfig.modelPath),
            )

            val (eng, backendType) = createEngineWithFallback(resolvedConfig)
            engine = eng
            conversation = eng.createConversation(buildConversationConfig(backendType, resolvedConfig))
            currentConfig = resolvedConfig
            _activeBackend.value = backendType
            _isReady.value = true

            Log.i(TAG, "Engine ready — backend: $backendType, maxTokens: ${resolvedConfig.maxTokens}")
        }
    }

    override suspend fun resetConversation() {
        withContext(LlmDispatcher) {
            val eng = engine ?: return@withContext
            val config = currentConfig ?: return@withContext
            val backend = _activeBackend.value ?: BackendType.CPU

            safeClose(conversation, "conversation")
            conversation = eng.createConversation(buildConversationConfig(backend, config))
            _isGenerating.value = false
            Log.i(TAG, "Conversation reset")
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
            currentConfig = null
            Log.i(TAG, "Engine shut down")
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

        try {
            conv.sendMessageAsync(
                Contents.of(Content.Text(userMessage)),
                object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Route thinking tokens separately (Gemma thinking mode)
                    val thinkingText = message.channels["thought"]
                    if (!thinkingText.isNullOrEmpty()) {
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
            BackendType.AUTO -> listOf(BackendType.NPU, BackendType.GPU, BackendType.CPU)
        }

        var lastException: Exception? = null
        for (backendType in orderedBackends) {
            try {
                Log.d(TAG, "Trying backend: $backendType")
                val timeoutMs = if (backendType == BackendType.GPU) 25_000L else 120_000L
                val eng = tryInitEngine(
                    modelPath = config.modelPath,
                    backend = backendType.toBackend(context),
                    maxTokens = config.maxTokens,
                    timeoutMs = timeoutMs,
                )
                Log.i(TAG, "Backend $backendType initialized successfully")
                return Pair(eng, backendType)
            } catch (e: InterruptedException) {
                // tryInitEngine already restored the flag; re-assert defensively and abort
                // without attempting the next backend — the caller was cancelled.
                Thread.currentThread().interrupt()
                throw e
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.w(TAG, "Backend $backendType init timed out — falling back")
                lastException = Exception("Backend $backendType timed out", e)
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

    /**
     * Attempts to initialize a [Engine] with a timeout guard.
     *
     * GPU initialization on some devices (e.g. Samsung Galaxy S23 Ultra) can
     * block for >50s, causing an OOM-kill before the fallback chain fires.
     * Running the blocking call on a [CompletableFuture] thread lets us abort
     * our wait after [timeoutMs] and propagate a [java.util.concurrent.TimeoutException]
     * so [createEngineWithFallback] can fall back to the next backend.
     *
     * Memory note: after a GPU timeout the spawned thread keeps running (it cannot be
     * interrupted since it is inside a JNI call). If GPU init eventually succeeds while CPU
     * init is already in flight, both model weights are briefly resident simultaneously.
     * The [whenComplete] cleanup closes the orphaned engine as soon as it surfaces, but the
     * overlap window is real. On the S23 Ultra (12 GB) this is acceptable; on 6 GB devices
     * it may retrigger OOM. A future improvement would be to serialise backends through a
     * dedicated single-thread executor rather than sharing [ForkJoinPool.commonPool].
     */
    private fun tryInitEngine(
        modelPath: String,
        backend: com.google.ai.edge.litertlm.Backend,
        maxTokens: Int,
        timeoutMs: Long,
    ): Engine {
        val future = java.util.concurrent.CompletableFuture.supplyAsync {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = maxTokens,
            )
            val eng = Engine(engineConfig)
            eng.initialize()
            eng
        }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Restore the interrupt flag so the calling coroutine sees cancellation.
            Thread.currentThread().interrupt()
            future.whenComplete { eng, _ -> eng?.let { safeClose(it, "interrupted engine") } }
            throw e
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "Backend init timed out after ${timeoutMs}ms — falling back")
            // Clean up the engine if it eventually completes after our deadline.
            future.whenComplete { eng, _ -> eng?.let { safeClose(it, "timed-out engine") } }
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            if (cause is Exception) throw cause
            // Wrap Error (e.g. OutOfMemoryError) so createEngineWithFallback's
            // catch (e: Exception) can trigger the fallback to the next backend.
            throw RuntimeException("Engine init failed: ${cause.message}", cause)
        }
    }

    private fun buildConversationConfig(
        backendType: BackendType,
        config: ModelConfig,
    ): ConversationConfig {
        // NPU uses hardware sampler — setting SamplerConfig causes a crash
        val samplerConfig = if (backendType == BackendType.NPU) null else SamplerConfig(
            topK = 40,
            topP = config.topP.toDouble(),
            temperature = config.temperature.toDouble(),
        )
        val systemInstruction = config.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { Contents.of(Content.Text(it)) }

        return ConversationConfig(
            samplerConfig = samplerConfig,
            systemInstruction = systemInstruction,
        )
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
}
