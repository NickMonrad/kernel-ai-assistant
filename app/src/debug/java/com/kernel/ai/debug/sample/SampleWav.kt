package com.kernel.ai.debug.sample

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Debug-only WAV helpers for [SampleClipReceiver]. The gain step matches the playback path
 * (`AudioTrackVoicePlayback`): multiply, then clamp to [-1, 1].
 */
internal object SampleWav {
    fun applyGain(samples: FloatArray, gain: Float): FloatArray =
        FloatArray(samples.size) { i -> (samples[i] * gain).coerceIn(-1.0f, 1.0f) }

    /** 16-bit mono PCM WAV bytes; the data chunk holds exactly `samples.size` frames. */
    fun wavBytes(samples: FloatArray, rate: Int): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        writeWav(DataOutputStream(bos), samples, rate)
        return bos.toByteArray()
    }

    fun writeFile(file: File, samples: FloatArray, rate: Int) {
        DataOutputStream(FileOutputStream(file).buffered()).use { writeWav(it, samples, rate) }
    }

    private fun writeWav(out: DataOutputStream, samples: FloatArray, rate: Int) {
        val dataBytes = samples.size * 2
        out.writeBytes("RIFF")
        out.writeIntLe(36 + dataBytes)
        out.writeBytes("WAVEfmt ")
        out.writeIntLe(16)
        out.writeShortLe(1)
        out.writeShortLe(1)
        out.writeIntLe(rate)
        out.writeIntLe(rate * 2)
        out.writeShortLe(2)
        out.writeShortLe(16)
        out.writeBytes("data")
        out.writeIntLe(dataBytes)
        for (s in samples) {
            out.writeShortLe((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt())
        }
        out.flush()
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xff)
        write((v shr 8) and 0xff)
        write((v shr 16) and 0xff)
        write((v shr 24) and 0xff)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xff)
        write((v shr 8) and 0xff)
    }

    private fun DataOutputStream.writeBytes(s: String) = write(s.toByteArray(Charsets.US_ASCII))
}
