package com.kernel.ai.core.voice

/**
 * Detects a specific wake word phrase from a continuous audio stream.
 *
 * Implementations run an internal AudioRecord loop and notify via [onDetected] when
 * the wake phrase is recognised above [WakeWordPreferences.confidenceThreshold].
 *
 * ## openWakeWord 3-stage model contract (for #984 / #985)
 *
 * Audio format — mandatory, non-negotiable:
 * - Sample rate: 16 kHz, mono, 16-bit PCM
 * - Frame size:  80 ms = 1 280 samples
 *
 * Pipeline:
 * 1. melspectrogram.onnx  — `float32[1, 1280]` PCM → mel-spectrogram patch
 * 2. embedding_model.onnx — mel patch → `float32[1, 96]` embedding (per 80ms frame)
 * 3. hey_jandal.onnx      — `float32[1, 16, 96]` embedding window → `float32[1, 1]` confidence
 *
 * Model files (all in assets/models/wakeword/):
 * - melspectrogram.onnx  — shared preprocessing; download from openWakeWord releases
 * - embedding_model.onnx — Google Speech Embedding backbone; download from openWakeWord releases
 * - hey_jandal.onnx      — trained by #984; produced by torch.onnx.export on the custom classifier
 */
interface WakeWordDetector {

    /** True when the model file is present on device and the detector can run. */
    val isAvailable: Boolean

    /**
     * Start continuous detection.
     *
     * [onDetected] is invoked on an unspecified background thread when confidence exceeds the
     * primary threshold (or the secondary threshold after [verifyWindow] confirms the phrase).
     *
     * @param generationId   service-allocated ID shared by every event from this detector run
     * @param onDetected      called when the wake word is confirmed; runs on the detector thread
     * @param verifyWindow    optional secondary verification: receives the last ~3 s of raw 16kHz
     *                        PCM (as [ShortArray]) captured just before the LOW_THRESHOLD crossing.
     *                        Return true to confirm; false to suppress.  Called synchronously on
     *                        the detector thread when confidence is in [LOW_THRESHOLD, HIGH_THRESHOLD).
     *                        Null disables the dual-threshold path — all detections go through the
     *                        high threshold only.
     *
     * No-op if [isAvailable] is false.
     */
    fun start(
        generationId: Long,
        onDetected: () -> Unit,
        verifyWindow: ((ShortArray) -> Boolean)? = null,
    )

    /**
     * Stop detection and release AudioRecord resources.
     *
     * Safe to call when already stopped.
     */
    fun stop()
}
