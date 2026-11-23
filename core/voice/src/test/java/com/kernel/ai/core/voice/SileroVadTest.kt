package com.kernel.ai.core.voice

import java.io.File
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SileroVadTest {
    private fun shippedModel(): SileroVad = SileroVad.fromBytes(File("src/vad/assets/silero_vad.onnx").readBytes())

    @Test
    fun `silence is not speech`() {
        val vad = shippedModel()
        val silence = ShortArray(16_000)
        assertFalse(vad.isSpeech(silence, silence.size))
    }

    @Test
    fun `steady hiss at fan level is not speech`() {
        val vad = shippedModel()
        val rng = Random(1)
        // Uniform hiss at about 5% of full scale for one second: the fan/TV-like floor from #2078.
        val hiss = ShortArray(16_000) { rng.nextInt(-1638, 1639).toShort() }
        assertFalse(vad.isSpeech(hiss, hiss.size))
    }
}
