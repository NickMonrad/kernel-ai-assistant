package com.kernel.ai.core.voice

/**
 * Detects a specific wake word phrase from a continuous audio stream.
 *
 * Implementations run an internal AudioRecord loop and notify via [onDetected] when
 * the wake phrase is recognised above [WakeWordPreferences.confidenceThreshold].
 *
 * Model contract (for #984 training pipeline):
 * - Sample rate: 16 kHz, mono, 16-bit PCM
 * - Window: 1-second ring buffer = 16 000 samples
 * - Model input: FloatArray of 16 000 normalised PCM samples in [-1, 1]
 * - Model output: FloatArray of size 1; output[0] is the wake word confidence in [0, 1]
 * - Model file: assets/models/wakeword/hey_jandal_int8.tflite
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
