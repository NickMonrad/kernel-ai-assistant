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
 * 3. hey_jandal.onnx      — `float32[1, 28, 96]` embedding window → `float32[1, 1]` confidence
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
     * threshold. Callers must stop any current capture before starting another.
     *
     * No-op if [isAvailable] is false.
     */
    fun start(onDetected: () -> Unit)

    /**
     * Stop detection and release AudioRecord resources.
     *
     * Safe to call when already stopped.
     */
    fun stop()
}
