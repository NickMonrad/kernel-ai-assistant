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
        val tokens = decode(chunk)

        assertEquals(clip.ctcTokens, tokens.map { Triple(it.id, it.startFrame, it.endFrame) })
        assertEquals(clip.ctcText, tokens.joinToString("") { config.vocab[it.id] })
    }

    @ParameterizedTest
    @ValueSource(ints = [3, 16])
    fun `pause buckets follow the gap after the previous token`(chunk: Int) {
        val tokens = decode(chunk)

        assertEquals(config.pcec.pauseBlackout, tokens.first().pauseBucket)
        for (i in 1 until tokens.size) {
            val gap = tokens[i].startFrame - tokens[i - 1].endFrame - 1
            assertEquals(PcecFeatures.pauseBucket(config.pcec, gap), tokens[i].pauseBucket, "token $i gap $gap")
        }
    }

    private fun decode(chunk: Int): List<PcecToken> {
        val decoder = CtcStreamDecoder(config)
        val out = ArrayList<PcecToken>()
        val vocab = clip.vocabSize
        val dim = config.hiddenDim
        var frame = 0
        while (frame < clip.frames) {
            val n = minOf(chunk, clip.frames - frame)
            val logits = clip.logits.copyOfRange(frame * vocab, (frame + n) * vocab)
            decoder.accept(logits, FloatArray(n * dim), n) { out += it }
            frame += n
        }
        decoder.close { out += it }
        return out
    }
}
