package com.kernel.ai.debug.llamaprobe

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    val isGenerating: Boolean
        get() = !closed.get() && nativeIsGenerating(nativeHandle)

    suspend fun generate(
        prompt: String,
        systemPrompt: String,
        schemaJson: String,
        maxTokens: Int = 1024,
        reportProgress: Boolean = false,
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
                continuation.invokeOnCancellation { nativeCancel(nativeHandle, requestId) }
                try {
                    executor.execute {
                        try {
                            val result = JSONObject(
                                nativeGenerate(
                                    nativeHandle, requestId, prompt, systemPrompt, schemaJson, maxTokens, reportProgress,
                                ),
                            )
                            val generation = LlamaGeneration(
                                text = result.getString("text"),
                                promptTokens = result.getInt("prompt_tokens"),
                                generatedTokens = result.getInt("generated_tokens"),
                                timeToFirstTokenMs = result.getLong("ttft_ms"),
                                decodeMs = result.getLong("decode_ms"),
                                totalMs = result.getLong("total_ms"),
                                complete = result.getBoolean("complete"),
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
            executor.submit { Unit }.get()
        }
    }

    suspend fun close() {
        val shouldClose = synchronized(lifecycleLock) {
            if (closed.get()) {
                false
            } else {
                closed.set(true)
                activeRequestId.get().takeIf { it != 0L }?.let { nativeCancel(nativeHandle, it) }
                true
            }
        }
        if (!shouldClose) return
        withContext(Dispatchers.IO) {
            executor.submit { nativeClose(nativeHandle) }.get()
            executor.shutdown()
            check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "llama.cpp worker did not terminate" }
        }
    }

    private external fun nativeGenerate(
        handle: Long,
        requestId: Long,
        prompt: String,
        systemPrompt: String,
        schemaJson: String,
        maxTokens: Int,
        reportProgress: Boolean,
    ): String

    private external fun nativeCancel(handle: Long, requestId: Long)
    private external fun nativeIsGenerating(handle: Long): Boolean
    private external fun nativeClose(handle: Long)

    companion object {
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
