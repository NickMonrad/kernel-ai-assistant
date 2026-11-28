package com.kernel.ai.core.voice.kokoro

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Result of Settings "Test voice speed" (#2117). Measured runs never play audio. */
sealed interface KokoroSpeedResult {
    /**
     * [realTimeFactor] is synthesis time ÷ audio length: 1.0 means audio is made exactly as fast as it plays.
     * [firstChunkSeconds] is time from the start of the run to the first chunk being ready.
     * [peakMemoryMb] is the process VmHWM after the run, or null when /proc is unreadable.
     * [memoryDetails] is the free-memory figures the check read before it ran; null when no check ran.
     */
    data class Measured(
        val audioSeconds: Double,
        val synthesisSeconds: Double,
        val firstChunkSeconds: Double,
        val peakMemoryMb: Long?,
        val runs: List<Run> = emptyList(),
        val medianRealTimeFactor: Double = synthesisSeconds / audioSeconds,
        val medianFirstChunkSeconds: Double = firstChunkSeconds,
        val stoppedEarly: Boolean = false,
        val memoryDetails: String? = null,
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
    /** [memoryShort]: no load was attempted because of free memory; [details] are the numbers the gate read. */
    data class Unavailable(val message: String, val details: String? = null, val memoryShort: Boolean = false) : KokoroSpeedResult
}

const val KOKORO_SELECT_MAX_MEDIAN_RTF = 0.70
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

/** Fixed passage, one sentence per entry: a reply is phonemised one sentence at a time, so the test is too. */
internal val KOKORO_SPEED_TEST_SENTENCES: List<String> = listOf(
    "Jandal reads replies aloud on this phone.",
    "Speech should keep pace with the words, so the first sentence starts quickly and the rest follows without a gap.",
    "Longer replies are split into sentences, and each sentence is made while the previous one plays.",
)

/**
 * Makes [KOKORO_SPEED_TEST_RUNS] passes over [sentences], phonemising and synthesising each sentence on its own as a
 * reply does; phonemising counts as making audio. [ensureActive] runs before every chunk, so leaving Settings stops the
 * test after the chunk in progress. A failure of any kind, including a native OutOfMemoryError, becomes Unavailable.
 * Clock and engine calls are injected so the timing logic is testable without ExecuTorch.
 */
internal fun measureKokoroSpeed(
    sentences: List<String>,
    splitText: (String) -> List<String>,
    synthesizeChunk: (String) -> FloatArray,
    sampleRate: Int,
    peakMemoryMb: () -> Long?,
    ensureActive: () -> Unit = {},
    runs: Int = KOKORO_SPEED_TEST_RUNS,
    selectMaxRtf: Double = KOKORO_SELECT_MAX_MEDIAN_RTF,
    nanoTime: () -> Long = System::nanoTime,
): KokoroSpeedResult {
    val measured = ArrayList<KokoroSpeedResult.Measured.Run>(runs)
    var stoppedEarly = false
    try {
        for (pass in 1..runs) {
            measured += timeOneSpeedRun(sentences, splitText, synthesizeChunk, sampleRate, ensureActive, nanoTime)
                ?: return KokoroSpeedResult.Unavailable("Kokoro produced no audio for the speed test.")
            // Two runs both over the limit put the median of three over it too, so a third pass cannot change the verdict.
            if (pass == 2 && runs > 2 && measured.all { it.realTimeFactor > selectMaxRtf }) {
                stoppedEarly = true
                break
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        return KokoroSpeedResult.Unavailable("Kokoro speed test failed: ${error.message}")
    }
    val result = aggregateKokoroSpeedRuns(measured, peakMemoryMb())
    return if (stoppedEarly && result is KokoroSpeedResult.Measured) result.copy(stoppedEarly = true) else result
}

/** One pass over [sentences]; null when the pass produced no samples. */
private fun timeOneSpeedRun(
    sentences: List<String>,
    splitText: (String) -> List<String>,
    synthesizeChunk: (String) -> FloatArray,
    sampleRate: Int,
    ensureActive: () -> Unit,
    nanoTime: () -> Long,
): KokoroSpeedResult.Measured.Run? {
    val start = nanoTime()
    var firstChunkNanos = -1L
    var samples = 0L
    sentences.forEach { sentence ->
        splitText(sentence).forEach { chunk ->
            ensureActive()
            samples += synthesizeChunk(chunk).size
            if (firstChunkNanos < 0L) firstChunkNanos = nanoTime() - start
        }
    }
    val elapsedNanos = nanoTime() - start
    if (samples == 0L) return null
    return KokoroSpeedResult.Measured.Run(
        audioSeconds = samples.toDouble() / sampleRate,
        synthesisSeconds = elapsedNanos / NANOS_PER_SECOND,
        firstChunkSeconds = firstChunkNanos / NANOS_PER_SECOND,
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
