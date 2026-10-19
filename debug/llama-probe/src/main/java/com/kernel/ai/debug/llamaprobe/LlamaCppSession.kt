package com.kernel.ai.debug.llamaprobe

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LlamaGeneration(
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val timeToFirstTokenMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val complete: Boolean,
    val streamedChunks: Int = 0,
    val streamTimeToFirstChunkMs: Long? = null,
) {
    val decodeTokensPerSecond: Double
        get() = if (decodeMs <= 0) 0.0 else generatedTokens * 1000.0 / decodeMs
}

class LlamaCppSession private constructor(
    private val nativeHandle: Long,
    val loadMs: Long,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama-probe").apply { isDaemon = true }
    }
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val nextRequestId = AtomicLong(1)
    private val activeRequestId = AtomicLong(0)
    private val callbackLock = Any()

    private inner class TokenObserver(
        private val requestId: Long,
        private val continuation: CancellableContinuation<LlamaGeneration>,
        private val startedAtNs: Long,
        private val emit: (String) -> Unit,
    ) {
        private val decoder = Utf8TokenDecoder()
        var chunkCount = 0
            private set
        var firstChunkMs: Long? = null
            private set

        fun onTokenBytes(bytes: ByteArray, length: Int) {
            synchronized(callbackLock) {
                if (!continuation.isActive || activeRequestId.get() != requestId || closed.get()) {
                    return@synchronized
                }
                val chunk = decoder.append(bytes, length)
                if (chunk.isNotEmpty()) {
                    if (firstChunkMs == null) {
                        firstChunkMs = (System.nanoTime() - startedAtNs) / 1_000_000
                    }
                    chunkCount++
                    emit(chunk)
                }
            }
        }

        fun finish() {
            check(decoder.finish().isEmpty()) { "UTF-8 decoder emitted text after the final token chunk" }
        }
    }


    val isGenerating: Boolean
        get() = activeRequestId.get() != 0L

    /**
     * Streams complete UTF-8 chunks synchronously on the native worker.
     * Success returns [LlamaGeneration]; validation, native and cancellation failures throw.
     * Keep [onText] non-blocking; cancellation serializes against the callback before returning.
     */
    suspend fun generate(
        prompt: String,
        systemPrompt: String,
        schemaJson: String,
        maxTokens: Int = 1024,
        reportProgress: Boolean = false,
        onText: ((String) -> Unit)? = null,
    ): LlamaGeneration {
        JsonSchemaSubset.validate(schemaJson)
        return suspendCancellableCoroutine { continuation ->
            synchronized(lifecycleLock) {
                if (closed.get()) {
                    continuation.resumeWithException(IllegalStateException("llama.cpp probe session is closed"))
                    return@synchronized
                }
                val requestId = nextRequestId.getAndIncrement()
                if (!activeRequestId.compareAndSet(0, requestId)) {
                    continuation.resumeWithException(IllegalStateException("A generation is already active"))
                    return@synchronized
                }
                continuation.invokeOnCancellation {
                    synchronized(callbackLock) {
                        if (!closed.get() && activeRequestId.get() == requestId) {
                            nativeCancel(nativeHandle, requestId)
                        }
                    }
                }
                try {
                    executor.execute {
                        val workerStartedAtNs = System.nanoTime()
                        val tokenObserver = onText?.let {
                            TokenObserver(requestId, continuation, workerStartedAtNs, it)
                        }
                        try {
                            val nativeResult = nativeGenerate(
                                nativeHandle, requestId, prompt, systemPrompt, schemaJson,
                                tokenObserver, maxTokens, reportProgress,
                            )
                            tokenObserver?.finish()
                            val result = JSONObject(
                                StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(nativeResult))
                                    .toString(),
                            )
                            val generation = LlamaGeneration(
                                text = result.getString("text"),
                                promptTokens = result.getInt("prompt_tokens"),
                                generatedTokens = result.getInt("generated_tokens"),
                                timeToFirstTokenMs = result.getLong("ttft_ms"),
                                decodeMs = result.getLong("decode_ms"),
                                totalMs = result.getLong("total_ms"),
                                complete = result.getBoolean("complete"),
                                streamedChunks = tokenObserver?.chunkCount ?: 0,
                                streamTimeToFirstChunkMs = tokenObserver?.firstChunkMs,
                            )
                            activeRequestId.compareAndSet(requestId, 0)
                            check(generation.complete) {
                                "llama.cpp generation reached maxTokens before end-of-generation"
                            }
                            if (continuation.isActive) continuation.resume(generation)
                        } catch (failure: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        } finally {
                            activeRequestId.compareAndSet(requestId, 0)
                        }
                    }
                } catch (failure: Throwable) {
                    activeRequestId.compareAndSet(requestId, 0)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }
    }

    suspend fun awaitNativeIdle() {
        withContext(Dispatchers.IO) {
            try {
                executor.submit { Unit }.get(NATIVE_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                throw IllegalStateException(
                    "llama.cpp worker did not become idle within ${NATIVE_DRAIN_TIMEOUT_SECONDS}s",
                    timeout,
                )
            }
        }
    }

    suspend fun close() {
        val requestId = synchronized(lifecycleLock) {
            if (closed.get()) return
            closed.set(true)
            activeRequestId.get()
        }
        if (requestId != 0L) {
            synchronized(callbackLock) {
                if (activeRequestId.get() == requestId) nativeCancel(nativeHandle, requestId)
            }
        }
        val closeTask = executor.submit { nativeClose(nativeHandle) }
        executor.shutdown()
        withContext(Dispatchers.IO) {
            try {
                closeTask.get(NATIVE_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                throw IllegalStateException(
                    "llama.cpp native close did not drain within ${NATIVE_DRAIN_TIMEOUT_SECONDS}s; " +
                        "native close remains queued behind the active request",
                    timeout,
                )
            }
            check(executor.awaitTermination(EXECUTOR_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "llama.cpp worker did not terminate after native close"
            }
        }
    }

    private external fun nativeGenerate(
        handle: Long,
        requestId: Long,
        prompt: String,
        systemPrompt: String,
        schemaJson: String,
        tokenObserver: Any?,
        maxTokens: Int,
        reportProgress: Boolean,
    ): ByteArray

    private external fun nativeCancel(handle: Long, requestId: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        private const val NATIVE_DRAIN_TIMEOUT_SECONDS = 30L
        private const val EXECUTOR_EXIT_TIMEOUT_SECONDS = 5L
        init {
            System.loadLibrary("jandal_llama_probe")
        }

        private external fun nativeOpen(modelPath: String, contextSize: Int): Long

        suspend fun load(model: File, contextSize: Int = 2048): LlamaCppSession = withContext(Dispatchers.IO) {
            require(model.isFile) { "GGUF model file is missing: ${model.absolutePath}" }
            require(contextSize in 512..8192) { "contextSize must be between 512 and 8192" }
            val start = System.nanoTime()
            val nativeHandle = nativeOpen(model.absolutePath, contextSize)
            val loadMs = (System.nanoTime() - start) / 1_000_000
            check(nativeHandle != 0L) { "llama.cpp failed to open the GGUF model" }
            LlamaCppSession(nativeHandle, loadMs)
        }
    }
}

internal class Utf8TokenDecoder {
    private val encoded = ByteBuffer.allocate(MAX_TOKEN_BYTES + MAX_INCOMPLETE_UTF8_BYTES)
    private val decoded = CharBuffer.allocate(MAX_TOKEN_BYTES + MAX_INCOMPLETE_UTF8_BYTES)
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private var finished = false

    fun append(bytes: ByteArray, length: Int): String {
        check(!finished) { "UTF-8 decoder is already finished" }
        require(length in 0..bytes.size && length <= MAX_TOKEN_BYTES) {
            "Native token chunk length is out of bounds"
        }
        check(encoded.remaining() >= length) { "Incomplete UTF-8 token chunk exceeded decoder capacity" }
        encoded.put(bytes, 0, length)
        encoded.flip()
        decoded.clear()
        val result = decoder.decode(encoded, decoded, false)
        if (result.isError) result.throwException()
        check(!result.isOverflow) { "UTF-8 decoder output buffer overflowed" }
        encoded.compact()
        decoded.flip()
        return decoded.toString()
    }

    fun finish(): String {
        check(!finished) { "UTF-8 decoder is already finished" }
        finished = true
        encoded.flip()
        decoded.clear()
        val result = decoder.decode(encoded, decoded, true)
        if (result.isError) result.throwException()
        check(!result.isOverflow && !encoded.hasRemaining()) {
            "Native generation ended with an incomplete UTF-8 sequence"
        }
        val flush = decoder.flush(decoded)
        if (flush.isError) flush.throwException()
        check(!flush.isOverflow) { "UTF-8 decoder output buffer overflowed while flushing" }
        decoded.flip()
        return decoded.toString()
    }

    private companion object {
        const val MAX_TOKEN_BYTES = 4096
        const val MAX_INCOMPLETE_UTF8_BYTES = 4
    }
}
