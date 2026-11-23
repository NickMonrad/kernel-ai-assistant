package com.kernel.ai.core.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.nio.FloatBuffer

/**
 * Per-chunk speech decision for the asr4all reader. Stateful: [reset] runs when a capture opens, so one
 * utterance's recurrent state never carries into the next.
 */
internal fun interface SpeechDetector {
    /** True when the first [n] samples of [pcm] (16 kHz mono) contain speech. */
    fun isSpeech(pcm: ShortArray, n: Int): Boolean

    fun reset() {}
}

/**
 * Silero VAD v4, 16 kHz branch (k2-fsa export; upstream MIT, see `docs/VOICE_MODEL_ATTRIBUTION.md`), run on
 * onnxruntime. The model takes 512-sample frames (32 ms) with recurrent state `h` and `c`, each [2, 1, 64].
 * A chunk is speech when any frame that completes inside it reaches [THRESHOLD].
 */
internal class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : SpeechDetector {
    private val frame = FloatArray(FRAME)
    private var filled = 0
    private val h = FloatArray(STATE_SIZE)
    private val c = FloatArray(STATE_SIZE)

    override fun reset() {
        filled = 0
        h.fill(0f)
        c.fill(0f)
    }

    override fun isSpeech(pcm: ShortArray, n: Int): Boolean {
        var speech = false
        for (i in 0 until n) {
            frame[filled++] = pcm[i] / 32768f
            if (filled == FRAME) {
                filled = 0
                if (probability() >= THRESHOLD) speech = true
            }
        }
        return speech
    }

    private fun probability(): Float {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, FRAME.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(h), STATE_SHAPE).use { ht ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(c), STATE_SHAPE).use { ct ->
                    session.run(mapOf("x" to x, "h" to ht, "c" to ct)).use { out ->
                        val prob = (out.get("prob").get() as OnnxTensor).floatBuffer.get(0)
                        (out.get("new_h").get() as OnnxTensor).floatBuffer.get(h)
                        (out.get("new_c").get() as OnnxTensor).floatBuffer.get(c)
                        return prob
                    }
                }
            }
        }
    }

    companion object {
        const val FRAME = 512
        const val THRESHOLD = 0.5f
        private const val STATE_SIZE = 2 * 64
        private val STATE_SHAPE = longArrayOf(2, 1, 64)
        private const val MODEL_ASSET = "silero_vad.onnx"

        fun load(assets: AssetManager): SileroVad = fromBytes(assets.open(MODEL_ASSET).use { it.readBytes() })

        fun fromBytes(model: ByteArray): SileroVad {
            val env = OrtEnvironment.getEnvironment()
            return SileroVad(env, env.createSession(model))
        }
    }
}
