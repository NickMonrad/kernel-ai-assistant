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
        val runs: List<Run> = emptyList(),
        val medianRealTimeFactor: Double = synthesisSeconds / audioSeconds,
        val medianFirstChunkSeconds: Double = firstChunkSeconds,
    ) : KokoroSpeedResult {
        data class Run(
            val audioSeconds: Double,
            val synthesisSeconds: Double,
            val firstChunkSeconds: Double,
        ) {
            val realTimeFactor: Double get() = synthesisSeconds / audioSeconds
        }

        val realTimeFactor: Double get() = medianRealTimeFactor
        val allowsKokoro: Boolean get() = medianRealTimeFactor <= KOKORO_SELECT_MAX_MEDIAN_RTF
    }
    data class Unavailable(val message: String) : KokoroSpeedResult
}

const val KOKORO_SELECT_MAX_MEDIAN_RTF = 0.90
internal const val KOKORO_SPEED_TEST_RUNS = 3

internal fun aggregateKokoroSpeedRuns(
    runs: List<KokoroSpeedResult.Measured.Run>,
    peakMemoryMb: Long?,
): KokoroSpeedResult {
    if (runs.isEmpty()) return KokoroSpeedResult.Unavailable("Kokoro produced no audio for the speed test.")
    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
    val medianRtf = median(runs.map { it.realTimeFactor })
    val medianFirstChunk = median(runs.map { it.firstChunkSeconds })
    return KokoroSpeedResult.Measured(
        audioSeconds = runs.map { it.audioSeconds }.average(),
        synthesisSeconds = runs.map { it.synthesisSeconds }.average(),
        firstChunkSeconds = medianFirstChunk,
        peakMemoryMb = peakMemoryMb,
        runs = runs,
        medianRealTimeFactor = medianRtf,
        medianFirstChunkSeconds = medianFirstChunk,
    )
}

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
    val audioSeconds = samples.toDouble() / sampleRate
    val synthesisSeconds = elapsedNanos / NANOS_PER_SECOND
    val firstChunkSeconds = firstChunkNanos / NANOS_PER_SECOND
    return KokoroSpeedResult.Measured(
        audioSeconds = audioSeconds,
        synthesisSeconds = synthesisSeconds,
        firstChunkSeconds = firstChunkSeconds,
        peakMemoryMb = peakMemoryMb(),
        runs = listOf(KokoroSpeedResult.Measured.Run(audioSeconds, synthesisSeconds, firstChunkSeconds)),
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
