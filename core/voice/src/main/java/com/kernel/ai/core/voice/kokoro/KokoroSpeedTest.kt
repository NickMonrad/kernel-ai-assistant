package com.kernel.ai.core.voice.kokoro

import java.io.File
import java.io.IOException

/** Result of Settings "Test voice speed" (#2117). Measured runs never play audio. */
sealed interface KokoroSpeedResult {
    /**
     * [realTimeFactor] is synthesis time ÷ audio length: 1.0 means audio is made exactly as fast as it plays.
     * [firstChunkSeconds] is time from the start of the run to the first chunk being ready.
     * [peakMemoryMb] is the process VmHWM after the run, or null when /proc is unreadable.
     */
    data class Measured(
        val audioSeconds: Double,
        val synthesisSeconds: Double,
        val firstChunkSeconds: Double,
        val peakMemoryMb: Long?,
    ) : KokoroSpeedResult {
        val realTimeFactor: Double get() = synthesisSeconds / audioSeconds

        /** At or above real time the phone cannot keep up with playback, so speech would stutter. */
        val stutters: Boolean get() = realTimeFactor >= STUTTER_REAL_TIME_FACTOR
    }

    data class Unavailable(val message: String) : KokoroSpeedResult
}

internal const val STUTTER_REAL_TIME_FACTOR = 1.0

/** Fixed passage with two sentences so the run covers the sentence chunking a normal reply uses. */
internal const val KOKORO_SPEED_TEST_TEXT =
    "Jandal reads replies aloud on this phone. Speech should keep pace with the words, so the first sentence " +
        "starts quickly and the rest follows without a gap. Longer replies are split into sentences, and each " +
        "sentence is made while the previous one plays."

/**
 * Times the text frontend and every synthesis chunk for [text]. Phonemising is part of making audio, so it counts.
 * Clock and engine calls are injected so the timing logic is testable without ExecuTorch.
 */
internal fun measureKokoroSpeed(
    text: String,
    splitText: (String) -> List<String>,
    synthesizeChunk: (String) -> FloatArray,
    sampleRate: Int,
    peakMemoryMb: () -> Long?,
    nanoTime: () -> Long = System::nanoTime,
): KokoroSpeedResult {
    val start = nanoTime()
    var firstChunkNanos = -1L
    var samples = 0L
    splitText(text).forEach { chunk ->
        samples += synthesizeChunk(chunk).size
        if (firstChunkNanos < 0L) firstChunkNanos = nanoTime() - start
    }
    val elapsedNanos = nanoTime() - start
    if (samples == 0L) return KokoroSpeedResult.Unavailable("Kokoro produced no audio for the speed test.")
    return KokoroSpeedResult.Measured(
        audioSeconds = samples.toDouble() / sampleRate,
        synthesisSeconds = elapsedNanos / NANOS_PER_SECOND,
        firstChunkSeconds = firstChunkNanos / NANOS_PER_SECOND,
        peakMemoryMb = peakMemoryMb(),
    )
}

/** Reads VmHWM (peak resident set, kB) from a /proc/self/status body. */
internal fun parseVmHwmMb(status: String): Long? {
    val kilobytes = status.lineSequence()
        .firstOrNull { it.startsWith("VmHWM:") }
        ?.substringAfter(':')
        ?.trim()
        ?.substringBefore(' ')
        ?.toLongOrNull()
        ?: return null
    return kilobytes / KB_PER_MB
}

internal fun readPeakMemoryMb(): Long? = try {
    parseVmHwmMb(File("/proc/self/status").readText())
} catch (_: IOException) {
    null
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val KB_PER_MB = 1024L
