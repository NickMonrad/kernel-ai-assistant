package com.kernel.ai.core.voice.asr4all

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.sound.sampled.AudioSystem

/**
 * Debug-only tier measurement harness. Runs the S/M x c16r4/c32r8 matrix over one fixture and
 * writes timings to ASR4ALL_MEASURE_OUT. It makes no selection: results are inputs to a later
 * owner decision, and nothing in production reads them.
 *
 * Inputs (skipped when absent): ASR4ALL_MEASURE=1, ASR4ALL_MEASURE_OUT (tsv path),
 * ASR4ALL_S_DIR, ASR4ALL_M_DIR (model dirs), ASR4ALL_FIXTURE_WAV (16 kHz mono s16 wav).
 * Timings are JVM host timings, not on-device numbers.
 */
class Asr4allTierMeasurementTest {

    private val enabled = System.getenv("ASR4ALL_MEASURE") == "1"
    private val out: File? = System.getenv("ASR4ALL_MEASURE_OUT")?.let(::File)
    private val fixture: File? = System.getenv("ASR4ALL_FIXTURE_WAV")?.let(::File)
    private val dirs = mapOf(
        "S" to System.getenv("ASR4ALL_S_DIR")?.let(::File),
        "M" to System.getenv("ASR4ALL_M_DIR")?.let(::File),
    )

    private fun clip(file: File): FloatArray {
        val stream = AudioSystem.getAudioInputStream(file)
        val bytes = stream.readBytes()
        return FloatArray(bytes.size / 2) { i ->
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    @Test
    fun measureTierMatrix() {
        assumeTrue(enabled && out != null && fixture?.isFile == true, "measurement inputs not set")
        val samples = clip(fixture!!)
        val seconds = samples.size / 16_000.0
        val rows = mutableListOf("model\ttier\topen_ms\tdecode_ms\taudio_s\trtf")
        for ((name, dir) in dirs) {
            if (dir == null || !dir.isDirectory) continue
            for (tier in listOf("stream_c16r4", "stream_c32r8")) {
                val t0 = System.nanoTime()
                Asr4allEngine.open(dir, tier).use { engine ->
                    val t1 = System.nanoTime()
                    engine.decodeOffline(samples, applyPcec = true)
                    val t2 = System.nanoTime()
                    val openMs = (t1 - t0) / 1e6
                    val decodeMs = (t2 - t1) / 1e6
                    rows += "$name\t$tier\t%.1f\t%.1f\t%.3f\t%.3f".format(openMs, decodeMs, seconds, decodeMs / 1000 / seconds)
                }
            }
        }
        out!!.writeText(rows.joinToString("\n", postfix = "\n"))
    }
}
