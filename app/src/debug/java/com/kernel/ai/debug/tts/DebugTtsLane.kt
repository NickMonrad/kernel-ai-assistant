package com.kernel.ai.debug.tts

import java.io.Closeable

/**
 * One on-device TTS engine under measurement. Debug-only: lanes wrap production frontends and runners without
 * changing them, so the same harness can run Inflect now and streaming engines (Kokoro, Qwen3-TTS) later.
 */
interface DebugTtsLane : Closeable {
    /** Stable id written to evidence JSON, e.g. `inflect-micro-v2`. */
    val laneId: String

    /** Runtime and delegate description written to evidence JSON. */
    val backend: String

    /** Loads the model or session. Returns elapsed ms of this call (runner init, not device cold start). */
    fun initialize(): Long

    /**
     * Synthesises [text] chunk by chunk. [onChunkReady] fires after each chunk is synthesised, with the chunk
     * index and ms since the call started. Throws `CancellationException` if [cancel] is requested mid-run.
     */
    fun synthesize(text: String, onChunkReady: (index: Int, elapsedMs: Long) -> Unit): LaneUtterance

    /** Requests cancellation of the in-flight [synthesize]. Callers reset it with [resetCancellation]. */
    fun cancel()

    fun resetCancellation()
}

data class LaneChunk(
    val index: Int,
    val phonemeChars: Int,
    val synthMs: Long,
    val audioMs: Double,
    val peakAbs: Float,
    val finite: Boolean,
)

data class LaneUtterance(
    val frontendMs: Long,
    val chunks: List<LaneChunk>,
) {
    val synthMs: Long get() = chunks.sumOf { it.synthMs }
    val audioMs: Double get() = chunks.sumOf { it.audioMs }
    val finite: Boolean get() = chunks.all { it.finite }
}

/**
 * ONNX Runtime version read reflectively: `core/voice` consumes onnxruntime as `implementation`, so the app
 * module cannot compile against it, but the AAR is packaged in the APK.
 */
fun onnxRuntimeVersion(): String =
    Class.forName("ai.onnxruntime.OrtEnvironment").let { env ->
        env.getMethod("getVersion").invoke(env.getMethod("getEnvironment").invoke(null)) as String
    }
