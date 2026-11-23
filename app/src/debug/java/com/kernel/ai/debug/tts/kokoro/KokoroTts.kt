package com.kernel.ai.debug.tts.kokoro

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kokoro helpers, ported from Software Mansion's react-native-executorch (MIT):
 * `src/extensions/speech/utils/kokoroUtils.ts` (vocabulary, pause table, voice decoding, duration scaling,
 * silence stripping) and `utils/textPartitioner.ts` (chunking). Debug spike only; see the evidence README for
 * the licence record. Offsets are UTF-16, as in the TypeScript source.
 */
internal object KokoroTts {
    const val SAMPLE_RATE_HZ = 24_000
    const val TICKS_PER_DURATION = 600
    const val VOICE_REF_SIZE = 256
    const val VOICE_REF_HALF_SIZE = 128
    const val DURATION_FEATURE_DIM = 640
    const val MIN_DURATION_TICKS = 16

    /** Bounds of the exported `.pte` programs (`export_nobool.py --max-tokens 128 --max-duration 296`). */
    const val MIN_TOKENS = 2
    const val MAX_TOKENS = 128
    const val MAX_DURATION_TICKS = 296

    private const val SILENCE_PADDING_MS = 50

    // IPA phoneme -> Kokoro vocabulary token id. Phonemes absent from the map are dropped at tokenization.
    private val VOCAB: Map<String, Int> = mapOf(
        ";" to 1, ":" to 2, "," to 3, "." to 4, "!" to 5, "?" to 6, "\u2014" to 9, "\u2026" to 10, "\"" to 11,
        "(" to 12, ")" to 13, "\u201C" to 14, "\u201D" to 15, " " to 16, "\u0303" to 17, "\u02A3" to 18,
        "\u02A5" to 19, "\u02A6" to 20, "\u02A8" to 21, "\u1D5D" to 22, "\uAB67" to 23, "A" to 24, "I" to 25,
        "O" to 31, "Q" to 33, "S" to 35, "T" to 36, "W" to 39, "Y" to 41, "\u1D4A" to 42, "a" to 43, "b" to 44,
        "c" to 45, "d" to 46, "e" to 47, "f" to 48, "h" to 50, "i" to 51, "j" to 52, "k" to 53, "l" to 54,
        "m" to 55, "n" to 56, "o" to 57, "p" to 58, "q" to 59, "r" to 60, "s" to 61, "t" to 62, "u" to 63,
        "v" to 64, "w" to 65, "x" to 66, "y" to 67, "z" to 68, "\u0251" to 69, "\u0250" to 70, "\u0252" to 71,
        "\u00E6" to 72, "\u03B2" to 75, "\u0254" to 76, "\u0255" to 77, "\u00E7" to 78, "\u0256" to 80,
        "\u00F0" to 81, "\u02A4" to 82, "\u0259" to 83, "\u025A" to 85, "\u025B" to 86, "\u025C" to 87,
        "\u025F" to 90, "\u0261" to 92, "\u0265" to 99, "\u0268" to 101, "\u026A" to 102, "\u029D" to 103,
        "\u026F" to 110, "\u0270" to 111, "\u014B" to 112, "\u0273" to 113, "\u0272" to 114, "\u0274" to 115,
        "\u00F8" to 116, "\u0278" to 118, "\u03B8" to 119, "\u0153" to 120, "\u0279" to 123, "\u027E" to 125,
        "\u027B" to 126, "\u0281" to 128, "\u027D" to 129, "\u0282" to 130, "\u0283" to 131, "\u0288" to 132,
        "\u02A7" to 133, "\u028A" to 135, "\u028B" to 136, "\u028C" to 138, "\u0263" to 139, "\u0264" to 140,
        "\u03C7" to 142, "\u028E" to 143, "\u0292" to 147, "\u0294" to 148, "\u02C8" to 156, "\u02CC" to 157,
        "\u02D0" to 158, "\u02B0" to 162, "\u02B2" to 164, "\u2193" to 169, "\u2192" to 171, "\u2197" to 172,
        "\u2198" to 173, "\u1D7B" to 177,
    )

    private val VOCAB_BY_CODE_POINT: Map<Int, Int> = VOCAB.entries.associate { it.key.codePointAt(0) to it.value }

    /** Silence in milliseconds appended after a chunk ending with the given phoneme. */
    private val PAUSE_MS: Map<String, Int> = mapOf(
        "." to 375, "?" to 500, "!" to 250, ";" to 400, "\u2026" to 600, "," to 130, ":" to 250, "-" to 200,
        "\u2014" to 250, "|" to 375, "\u0964" to 375, "\u0965" to 500, "\u00BF" to 50, "\u00A1" to 50,
        "\u00AB" to 50, "\u00BB" to 100,
    )

    fun pauseMs(lastPhoneme: String): Int = PAUSE_MS[lastPhoneme] ?: 0

    private val EOS = "?.!;\u2026|\u0964\u0965\u00BF\u00A1\u3002\uFF0E\uFF01\uFF1F\uFF1B".codePoints().toArray().toSet()
    private val PAUSE = ",:\u2014\u00AB\u00BB\u3001\uFF0C\uFF1A\u301C\uFF5E".codePoints().toArray().toSet()

    private const val TAG_EOS = 0
    private const val TAG_PAUSE = 1
    private const val TAG_WHITESPACE = 2

    /** Token ids for [phonemes] (code points), with pad token 0 at both ends. Mirrors `tokenize` in kokoroUtils.ts. */
    fun tokenize(phonemes: IntArray, totalLength: Int): LongArray {
        val tokens = LongArray(totalLength)
        val count = min(totalLength - 2, phonemes.size)
        var next = 1
        for (i in 0 until count) {
            val token = VOCAB_BY_CODE_POINT[phonemes[i]] ?: continue
            tokens[next++] = token.toLong()
        }
        return tokens
    }

    /** Scales per-token durations in place so they sum exactly to [target], distributing rounding by largest remainder. */
    fun scaleDurations(durations: IntArray, target: Int) {
        val total = durations.sum()
        if (total == 0) return
        val scale = target.toDouble() / total
        val shrinking = scale < 1
        val remainders = ArrayList<Pair<Double, Int>>(durations.size)
        var scaledSum = 0
        for (i in durations.indices) {
            val scaled = scale * durations[i]
            val rounded = if (shrinking) ceil(scaled).toInt() else floor(scaled).toInt()
            durations[i] = rounded
            scaledSum += rounded
            remainders.add(abs(rounded - scaled) to i)
        }
        remainders.sortByDescending { it.first }
        val diff = abs(target - scaledSum)
        for (k in 0 until min(diff, remainders.size)) {
            val index = remainders[k].second
            durations[index] += if (shrinking) -1 else 1
        }
    }

    /** Strips leading and trailing silence with a moving-average window, keeping [margin] samples at each edge. */
    fun stripAudio(audio: FloatArray, margin: Int, steps: Int = 10, threshold: Double = 0.005): FloatArray {
        if (audio.isEmpty()) return audio
        val start = max(0, findAudioBound(audio, reverse = false, steps, threshold) - margin)
        val end = min(audio.size - 1, findAudioBound(audio, reverse = true, steps, threshold) + margin)
        return if (end >= start) audio.copyOfRange(start, end + 1) else FloatArray(0)
    }

    private fun findAudioBound(audio: FloatArray, reverse: Boolean, steps: Int, threshold: Double): Int {
        val length = audio.size
        fun normalize(sample: Float): Double = max(0.0, abs(sample.toDouble()) - threshold)
        var windowSum = 0.0
        var index = if (reverse) length - 1 else 0
        for (processed in 1..length) {
            windowSum += normalize(audio[index])
            if (processed > steps) {
                windowSum -= normalize(audio[if (reverse) index + steps else index - steps])
            }
            if (processed >= steps && windowSum / steps >= threshold) return index
            index += if (reverse) -1 else 1
        }
        return if (reverse) 0 else length - 1
    }

    /** Decodes a Kokoro voice `.bin`: raw little-endian float32 rows of [VOICE_REF_SIZE] values. */
    fun parseVoice(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /**
     * Splits [text] into chunks of at most [limit] UTF-16 units with a forward dynamic programme over candidate
     * breakpoints. Port of `partition` in textPartitioner.ts with `prioritizeInitialTtfa: true` (the lane default).
     */
    fun partition(text: String, limit: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        require(limit >= MIN_PARTITION_LIMIT) { "partition: limit $limit is below minimum $MIN_PARTITION_LIMIT" }

        val bpIdx = ArrayList<Int>()
        val bpTag = ArrayList<Int>()
        var charIdx = 0
        var lastBpIdx = -1
        while (charIdx < text.length) {
            val codePoint = text.codePointAt(charIdx)
            val charLen = Character.charCount(codePoint)
            val endIdx = charIdx + charLen - 1
            val tag = tagOf(codePoint)
            if (endIdx - lastBpIdx > limit) {
                bpIdx.add(charIdx - 1)
                bpTag.add(TAG_WHITESPACE)
                lastBpIdx = charIdx - 1
            }
            if (tag != null) {
                bpIdx.add(endIdx)
                bpTag.add(tag)
                lastBpIdx = endIdx
            }
            charIdx += charLen
        }
        if (bpIdx.isEmpty() || bpIdx.last() < text.length - 1) {
            bpIdx.add(text.length - 1)
            bpTag.add(TAG_EOS)
        }

        val n = bpIdx.size
        fun length(cur: Int, prev: Int): Int =
            if (prev < 0) bpIdx[cur] + 1 else bpIdx[cur] - bpIdx[prev]

        fun cost(cur: Int, prev: Int): Double {
            val len = length(cur, prev)
            if (len > limit) return Double.POSITIVE_INFINITY
            val startCharIdx = if (prev < 0) 0 else bpIdx[prev] + 1
            val target = targetLength(startCharIdx, limit)
            val diff = len - target
            val devScale = if (prev < 0 && diff < 0) DEVIATION_SCALING * INITIAL_SHORT_DEVIATION_SCALE else DEVIATION_SCALING
            return SEPARATOR_PENALTY[bpTag[cur]] + devScale * diff * diff
        }

        // Float storage mirrors the Float32Array used by the TypeScript original.
        val minCost = FloatArray(n) { Float.POSITIVE_INFINITY }
        val predecessor = IntArray(n) { -2 }
        var jMin = -1
        for (i in 0 until n) {
            while (jMin < i && length(i, jMin) > limit) ++jMin
            for (j in jMin until i) {
                val total = cost(i, j) + (if (j < 0) 0.0 else minCost[j].toDouble())
                if (total < minCost[i]) {
                    minCost[i] = total.toFloat()
                    predecessor[i] = j
                }
            }
        }
        require(minCost[n - 1] != Float.POSITIVE_INFINITY) { "partition: text cannot be divided into chunks of length <= $limit" }

        val cuts = ArrayList<Int>()
        var i = n - 1
        while (i >= 0) {
            cuts.add(bpIdx[i])
            i = predecessor[i]
        }
        cuts.reverse()

        val chunks = ArrayList<String>()
        var startIdx = 0
        for (cut in cuts) {
            chunks.add(text.substring(startIdx, cut + 1))
            startIdx = cut + 1
        }
        chunks.add(text.substring(startIdx))
        return chunks.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private const val MIN_PARTITION_LIMIT = 10
    private const val MAX_TARGET_PHRASE_LENGTH = 120
    private const val DEVIATION_SCALING = 0.01
    private const val TARGET_LENGTH_RATIO = 0.5
    private const val INITIAL_TARGET_LENGTH_RATIO = 0.15
    private const val RAMP_CHARACTER_COUNT = 150
    private const val INITIAL_SHORT_DEVIATION_SCALE = 0.1
    private val SEPARATOR_PENALTY = doubleArrayOf(5.0, 80.0, 1000.0)

    private fun targetLength(startCharIdx: Int, limit: Int): Double {
        val maxTarget = min(MAX_TARGET_PHRASE_LENGTH.toDouble(), limit * TARGET_LENGTH_RATIO)
        val minTarget = min(maxTarget, max(MIN_PARTITION_LIMIT * 2.5, limit * INITIAL_TARGET_LENGTH_RATIO))
        val progress = min(1.0, startCharIdx.toDouble() / RAMP_CHARACTER_COUNT)
        return minTarget + (maxTarget - minTarget) * progress
    }

    private fun tagOf(codePoint: Int): Int? = when {
        codePoint in EOS -> TAG_EOS
        codePoint in PAUSE -> TAG_PAUSE
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> TAG_WHITESPACE
        else -> null
    }

    /** Whether the phoneme is spoken (a letter), as opposed to punctuation. Mirrors `LETTER_PATTERN` in the TS task. */
    fun isLetter(phoneme: String): Boolean = phoneme.isNotEmpty() && Character.isLetter(phoneme.codePointAt(0))

    val silencePaddingSamples: Int get() = SILENCE_PADDING_MS * SAMPLE_RATE_HZ / 1000
}
