package com.kernel.ai.core.voice

/**
 * Structured debug-only event for the target event journal.
 *
 * Each event carries a monotonically increasing sequence number, a monotonic
 * timestamp (elapsedRealtime), an optional wall-clock timestamp for operator
 * diagnostics, and correlation identifiers that let the runner group events
 * by detector generation and voice session.
 *
 * [metadata] contains only small structured, privacy-safe scalar values.
 * Never include transcript text, audio samples, file paths, account data,
 * device selectors, service endpoints, model paths or exception dumps.
 */
data class AcousticEvent(
    val sequence: Long,
    val monotonicMs: Long,
    val wallClockMs: Long = 0L,
    val type: String,
    val generationId: Long = 0L,
    val sessionId: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
)

/** Canonical event type strings emitted by the journal. */
object AcousticEventType {
    /** A new detector generation started. */
    const val DETECTOR_GENERATION_STARTED = "DETECTOR_GENERATION_STARTED"

    /** Silence gate became active (Stage 2/3 suppressed). */
    const val SILENCE_GATE_ENTERED = "SILENCE_GATE_ENTERED"

    /** First voiced frame detected after a period of silence-gating. */
    const val VOICED_FRAME_AFTER_SILENCE = "VOICED_FRAME_AFTER_SILENCE"

    /** Stage 2 embedding execution resumed after silence gating. */
    const val STAGE2_RESUMED = "STAGE2_RESUMED"

    /** Stage 3 classifier ready after embedding ring history filled. */
    const val STAGE3_READY = "STAGE3_READY"

    /**
     * An activation candidate was produced at or above the low threshold.
     * Metadata includes "confidence" and "mode" ("high" or "low").
     */
    const val ACTIVATION_CANDIDATE = "ACTIVATION_CANDIDATE"

    /**
     * Activation verified by STT (low-threshold path succeeded) or by
     * high-confidence fast path.  Metadata includes "mode".
     */
    const val VERIFIED_ACTIVATION = "VERIFIED_ACTIVATION"

    /** Wake callback invoked in WakeWordService. */
    const val WAKE_CALLBACK_INVOKED = "WAKE_CALLBACK_INVOKED"

    /** Voice/assistant session has started. */
    const val VOICE_SESSION_STARTED = "VOICE_SESSION_STARTED"

    /** STT start has been requested. */
    const val STT_START_REQUESTED = "STT_START_REQUESTED"

    /** STT recogniser reported readiness / ListeningStarted. */
    const val STT_READY = "STT_READY"

    /** Start-listening cue was requested. Metadata: "force_audible". */
    const val CUE_REQUESTED = "CUE_REQUESTED"

    /** STT detected speech.  No transcript content exposed. */
    const val STT_SPEECH_DETECTED = "STT_SPEECH_DETECTED"

    /** STT produced a partial result.  Metadata: "length" (character count). */
    const val STT_PARTIAL = "STT_PARTIAL"

    /** STT produced a final result. Metadata: "length" (character count). */
    const val STT_FINAL = "STT_FINAL"

    /** STT produced an error. Metadata: stable "category". */
    const val STT_ERROR = "STT_ERROR"

    /** Command handoff result. Metadata: "outcome" and optional stable "category". */
    const val COMMAND_ROUTING_RESULT = "COMMAND_ROUTING_RESULT"

    /** Voice session completed normally. */
    const val SESSION_COMPLETED = "SESSION_COMPLETED"

    /** Voice session was cancelled. Metadata: stable "category". */
    const val SESSION_CANCELLED = "SESSION_CANCELLED"

    /** Detector was re-armed with a new generation. */
    const val DETECTOR_REARMED = "DETECTOR_REARMED"

    /** Service loss. Metadata: stable "category". */
    const val SERVICE_ERROR = "SERVICE_ERROR"

    /** Detector-level error (ONNX session, AudioRecord, etc.). */
    const val DETECTOR_ERROR = "DETECTOR_ERROR"
}
