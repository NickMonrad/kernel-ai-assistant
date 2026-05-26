package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Wake word confidence threshold applied in [TfLiteWakeWordDetector.confidenceThreshold].
 * The threshold is read fresh on every [start] call so Settings changes take effect immediately.
 */

/** Audio parameters. 16kHz mono gives the best recognition accuracy. */
private const val SAMPLE_RATE = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

/**
 * Ring buffer size: 1 second of audio at 16kHz.
 * The TFLite model expects a fixed 16 000-sample normalised PCM window.
 */
private const val RING_BUFFER_SAMPLES = 16_000

/**
 * Inference rate: run the model every 320 samples (20ms) while listening.
 * Lower = more responsive; higher = fewer CPU cycles.
 */
private const val INFERENCE_STRIDE_SAMPLES = 320

/** TFLite model asset path relative to the app assets root. */
private const val MODEL_ASSET_PATH = "models/wakeword/hey_jandal_int8.tflite"

/**
 * Always-on wake word detector backed by a TFLite acoustic model.
 *
 * Audio pipeline:
 *   AudioRecord (16kHz mono) → 16 000-sample ring buffer → normalised FloatArray
 *   → TFLite interpreter → confidence[0] → threshold check → [onDetected]
 *
 * Model contract (for #984 training pipeline):
 * - Input:  FloatArray shape [1, 16000] — normalised PCM samples in [-1.0, 1.0]
 * - Output: FloatArray shape [1, 1]     — confidence in [0.0, 1.0]
 * - File:   app/src/main/assets/models/wakeword/hey_jandal_int8.tflite
 *
 * When the model file is absent [isAvailable] is false and [start] is a no-op,
 * so the app ships fine without the model. Dropping the file in assets activates the feature.
 */
@Singleton
class TfLiteWakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wakeWordPreferences: WakeWordPreferences,
) : WakeWordDetector {

    override val isAvailable: Boolean by lazy { modelFile?.exists() == true }

    @Volatile private var running = false
    @Volatile private var detectionThread: Thread? = null

    private val modelFile: File? by lazy {
        // Copy from assets to internal storage on first access so TFLite can mmap it.
        runCatching {
            val dest = File(context.filesDir, MODEL_ASSET_PATH)
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                context.assets.open(MODEL_ASSET_PATH).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "WakeWordDetector: model extracted to ${dest.absolutePath}")
            }
            dest
        }.getOrElse { e ->
            if (e is java.io.FileNotFoundException) {
                Log.d(TAG, "WakeWordDetector: model not yet available ($MODEL_ASSET_PATH) — feature disabled until #984 ships")
            } else {
                Log.w(TAG, "WakeWordDetector: unexpected error checking model file", e)
            }
            null
        }
    }

    override fun start(onDetected: () -> Unit) {
        if (!isAvailable) {
            Log.d(TAG, "WakeWordDetector: start() called but model absent — no-op")
            return
        }
        if (running) return
        running = true

        val threshold = runBlocking { wakeWordPreferences.confidenceThreshold.first() }
        Log.i(TAG, "WakeWordDetector: starting — threshold=$threshold")

        detectionThread = Thread({ runDetectionLoop(threshold, onDetected) }, "wake-word-detector").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        running = false
        detectionThread?.interrupt()
        detectionThread = null
        Log.d(TAG, "WakeWordDetector: stopped")
    }

    private fun runDetectionLoop(threshold: Float, onDetected: () -> Unit) {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(INFERENCE_STRIDE_SAMPLES * 2)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufSize,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "WakeWordDetector: AudioRecord failed to initialise")
            running = false
            return
        }

        val interpreter = runCatching {
            Interpreter(modelFile!!, Interpreter.Options().apply { setNumThreads(1) })
        }.getOrElse { e ->
            Log.e(TAG, "WakeWordDetector: failed to load TFLite model", e)
            audioRecord.release()
            running = false
            return
        }

        val ringBuffer = ShortArray(RING_BUFFER_SAMPLES)
        var writePos = 0
        val chunk = ShortArray(INFERENCE_STRIDE_SAMPLES)
        // Pre-allocate inference buffers — no allocation inside the hot loop.
        val inputBuffer = ByteBuffer.allocateDirect(RING_BUFFER_SAMPLES * 4)
            .order(ByteOrder.nativeOrder())
        val outputBuffer = Array(1) { FloatArray(1) }
        var samplesSinceLastInference = 0

        try {
            audioRecord.startRecording()
            Log.d(TAG, "WakeWordDetector: recording started")

            while (running && !Thread.currentThread().isInterrupted) {
                val read = audioRecord.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                // Write into ring buffer, wrapping around.
                for (i in 0 until read) {
                    ringBuffer[writePos] = chunk[i]
                    writePos = (writePos + 1) % RING_BUFFER_SAMPLES
                }
                samplesSinceLastInference += read

                if (samplesSinceLastInference < INFERENCE_STRIDE_SAMPLES) continue
                samplesSinceLastInference = 0

                // Flatten ring buffer into chronological order and normalise.
                inputBuffer.clear()
                for (i in 0 until RING_BUFFER_SAMPLES) {
                    val sample = ringBuffer[(writePos + i) % RING_BUFFER_SAMPLES]
                    inputBuffer.putFloat(sample / 32768f)
                }
                inputBuffer.rewind()

                outputBuffer[0][0] = 0f
                interpreter.run(inputBuffer, outputBuffer)

                val confidence = outputBuffer[0][0]
                if (confidence >= threshold) {
                    Log.i(TAG, "WakeWordDetector: detected — confidence=$confidence")
                    running = false  // prevent re-triggering while processing
                    onDetected()
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordDetector: error in detection loop", e)
        } finally {
            audioRecord.stop()
            audioRecord.release()
            interpreter.close()
            running = false
            Log.d(TAG, "WakeWordDetector: detection loop exited")
        }
    }
}
