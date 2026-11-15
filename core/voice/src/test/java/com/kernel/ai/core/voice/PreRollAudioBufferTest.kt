package com.kernel.ai.core.voice

import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreRollAudioBufferTest {

    private fun chunk(vararg values: Int): ShortArray = ShortArray(values.size) { values[it].toShort() }

    /** Fake recognizer: records the order of every waveform it is fed, as sample values. */
    private class FakeRecognizer {
        val accepted = mutableListOf<Short>()
        fun acceptWaveform(samples: ShortArray) {
            accepted += samples.toList()
        }
    }

    @Test
    fun `online consumer receives pre-roll captured before attach, then live audio, in order`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 1_000)
        // Captured while the recognizer was still loading.
        buffer.append(chunk(1, 2), 2)
        buffer.append(chunk(3, 4, 5), 3)

        val recognizer = FakeRecognizer()
        val preRoll = ArrayDeque(buffer.attach())
        // Live audio after the stream exists.
        buffer.append(chunk(6), 1)
        buffer.close()

        while (true) {
            val next = nextCaptureChunk(preRoll, buffer) ?: break
            recognizer.acceptWaveform(next)
        }

        assertEquals(listOf<Short>(1, 2, 3, 4, 5, 6), recognizer.accepted)
    }

    @Test
    fun `offline consumer prepends pre-roll to the utterance buffer in order`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 1_000)
        buffer.append(chunk(10, 11), 2)
        buffer.append(chunk(12), 1)

        // Offline loops collect every chunk, pre-roll first, then decode the concatenation once.
        val utterance = mutableListOf<Short>()
        val preRoll = ArrayDeque(buffer.attach())
        buffer.append(chunk(13, 14), 2)
        buffer.close()
        while (true) {
            val next = nextCaptureChunk(preRoll, buffer) ?: break
            utterance += next.toList()
        }

        assertEquals(listOf<Short>(10, 11, 12, 13, 14), utterance)
    }

    @Test
    fun `pre-roll keeps only the newest samples up to the window`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 4)
        buffer.append(chunk(1, 2), 2)
        buffer.append(chunk(3, 4), 2)
        buffer.append(chunk(5, 6), 2)

        val pre = buffer.attach().flatMap { it.toList() }
        assertEquals(listOf<Short>(3, 4, 5, 6), pre)
    }

    @Test
    fun `pre-roll always keeps the newest chunk even when it exceeds the window`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 2)
        buffer.append(chunk(1), 1)
        buffer.append(chunk(7, 8, 9), 3)

        val pre = buffer.attach().flatMap { it.toList() }
        assertEquals(listOf<Short>(7, 8, 9), pre)
    }

    @Test
    fun `append copies the first count samples so the reader can reuse its buffer`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 100)
        val reused = chunk(1, 2, 3)
        buffer.append(reused, 3)
        reused[0] = 99
        reused[1] = 99

        assertEquals(listOf<Short>(1, 2, 3), buffer.attach().single().toList())
    }

    @Test
    fun `take returns null after close once the queue is drained`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 100)
        buffer.attach()
        buffer.append(chunk(5), 1)
        buffer.close()

        assertEquals(listOf<Short>(5), buffer.take()!!.toList())
        assertNull(buffer.take())
        assertTrue(buffer.isClosed)
    }

    @Test
    fun `append after close is ignored`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 100)
        buffer.close()
        buffer.append(chunk(1), 1)

        assertTrue(buffer.attach().isEmpty())
    }

    @Test
    fun `take blocks until a live chunk arrives`() {
        val buffer = PreRollAudioBuffer(maxPreRollSamples = 100)
        buffer.attach()
        var received: ShortArray? = null
        val consumer = thread { received = buffer.take() }

        buffer.append(chunk(42), 1)
        consumer.join(5_000)

        assertEquals(listOf<Short>(42), received!!.toList())
    }
}
