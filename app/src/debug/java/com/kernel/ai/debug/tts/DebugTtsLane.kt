package com.kernel.ai.debug.tts

import java.io.Closeable
import org.json.JSONObject

/** Lane ids. Each id is a `lane` broadcast extra, names the evidence output directory, and maps to one lane. */
object DebugTtsLanes {
    const val INFLECT_MICRO_V2 = "inflect-micro-v2"
    const val KOKORO_ET = "kokoro_et"
    const val KOKORO_ET_INT8 = "kokoro_et_int8"

    val ids: Set<String> = setOf(INFLECT_MICRO_V2, KOKORO_ET, KOKORO_ET_INT8)
}

/**
 * One on-device TTS engine under measurement. Debug-only: lanes wrap production frontends and runners without
 * changing them, so the same harness can run Inflect now and streaming engines (Kokoro, Qwen3-TTS) later.
 *
 * Implementer contract:
 * - [backend] is read after [initialize] returns. A lane may settle its backend there, for example a GPU→CPU
 *   fallback is only known once the engine has loaded.
 * - [cancel] must surface as `java.util.concurrent.CancellationException` from [synthesize]. A streaming engine
 *   that ends early on cancel must translate that into `CancellationException`, or the cancel probe records
 *   `cancel_observed=false`.
 */
interface DebugTtsLane : Closeable {
    /** Stable id written to evidence JSON, one of [DebugTtsLanes]. */
    val laneId: String

    /** Runtime and delegate description written to evidence JSON. Read after [initialize]. */
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

    /**
     * Lane-specific evidence: runtime, voice and model identities. Called after [initialize]; written to the
     * evidence JSON as `lane_metadata`.
     */
    fun metadata(): JSONObject
}

data class LaneChunk(
    val index: Int,
    /** Phoneme string length for lanes with a phoneme stage; null otherwise. */
    val phonemeChars: Int?,
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
