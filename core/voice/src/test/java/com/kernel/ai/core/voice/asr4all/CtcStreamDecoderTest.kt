package com.kernel.ai.core.voice.asr4all

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Decodes real M-export logits recorded for a public LibriSpeech clip. */
class CtcStreamDecoderTest {
    private val config = Asr4AllTestData.configM()
    private val clip = Asr4AllTestData.Clip0000

    @ParameterizedTest
    @ValueSource(ints = [1, 7, 16, 160])
    fun `token sequence and timing do not depend on the chunking`(chunk: Int) {
        val tokens = decode(chunk).tokens

        assertEquals(clip.ctcTokens, tokens.map { Triple(it.id, it.startFrame, it.endFrame) })
        assertEquals(clip.ctcText, tokens.joinToString("") { config.vocab[it.id] })
    }

    @ParameterizedTest
    @ValueSource(ints = [3, 16])
    fun `pause buckets follow the blank frames before each token`(chunk: Int) {
        val tokens = decode(chunk).tokens

        assertEquals(PcecFeatures.pauseBucket(config.pcec, tokens.first().startFrame), tokens.first().pauseBucket)
        for (i in 1 until tokens.size) {
            val gap = tokens[i].startFrame - tokens[i - 1].endFrame - 1
            assertEquals(PcecFeatures.pauseBucket(config.pcec, gap), tokens[i].pauseBucket, "token $i gap $gap")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [5, 16])
    fun `hidden input spans each token's frames up to the next token's start`(chunk: Int) {
        val (tokens, handedBeforeFinish) = decode(chunk)

        // Only the last token waits for finish(): every other one is handed over when its successor starts.
        assertEquals(tokens.size - 1, handedBeforeFinish)
        val pooled = FloatArray(config.hiddenDim)
        tokens.forEachIndexed { i, t ->
            val end = if (i + 1 < tokens.size) tokens[i + 1].startFrame else clip.frames
            assertEquals(end - t.startFrame, t.hiddenFrames, "token $i span")
            t.pooledHidden(pooled, 0)
            // Column 0 of each hidden row holds its frame index, so the mean is the span's mean index.
            assertEquals((t.startFrame + end - 1) / 2.0, pooled[0].toDouble(), 1e-4, "token $i mean")
        }
    }

    private data class Decoded(val tokens: List<PcecToken>, val handedBeforeFinish: Int)

    private fun decode(chunk: Int): Decoded {
        val decoder = CtcStreamDecoder(config)
        val out = ArrayList<PcecToken>()
        val vocab = clip.vocabSize
        val dim = config.hiddenDim
        var frame = 0
        while (frame < clip.frames) {
            val n = minOf(chunk, clip.frames - frame)
            val logits = clip.logits.copyOfRange(frame * vocab, (frame + n) * vocab)
            val hidden = FloatArray(n * dim).also { h -> for (f in 0 until n) h[f * dim] = (frame + f).toFloat() }
            decoder.accept(logits, hidden, n) { out += it }
            frame += n
        }
        val handed = out.size
        decoder.finish { out += it }
        return Decoded(out, handed)
    }
}
