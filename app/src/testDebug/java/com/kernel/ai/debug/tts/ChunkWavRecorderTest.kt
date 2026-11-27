package com.kernel.ai.debug.tts

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChunkWavRecorderTest {
    @Test
    fun chunk_offsets_are_contiguous_per_reply_and_cover_every_sample() {
        val recorder = ChunkWavRecorder()
        val first = recorder.open(sampleRate = 24_000, pitch = 1.0f)
        first.write(floatArrayOf(0.1f, 0.2f, 0.3f), gain = 1.0f, isCurrent = { true })
        first.write(floatArrayOf(0.4f), gain = 1.0f, isCurrent = { true })
        first.drain(isCurrent = { true })
        first.release()
        val second = recorder.open(sampleRate = 24_000, pitch = 1.0f)
        second.write(floatArrayOf(-0.5f, -0.6f), gain = 1.0f, isCurrent = { true })
        second.release()

        val replies = recorder.replies
        assertEquals(2, replies.size)
        assertEquals(listOf(0 to 3, 3 to 4), replies[0].chunks.map { it.startFrame to it.endFrame })
        assertEquals(0, replies[0].startFrame)
        assertEquals(4, replies[0].endFrame)
        assertEquals(4, replies[1].startFrame)
        assertEquals(6, replies[1].endFrame)
        assertEquals(listOf(4 to 6), replies[1].chunks.map { it.startFrame to it.endFrame })
        assertEquals(6, recorder.frames)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, -0.5f, -0.6f), recorder.samples(), 0.0f)
    }

    @Test
    fun gain_is_applied_and_clamped_as_on_the_playback_path() {
        val recorder = ChunkWavRecorder()
        val track = recorder.open(sampleRate = 24_000, pitch = 1.0f)
        track.write(floatArrayOf(0.5f, -1.0f, 0.9f), gain = 1.5f, isCurrent = { true })
        track.release()

        assertArrayEquals(floatArrayOf(0.75f, -1.0f, 1.0f), recorder.samples(), 1e-6f)
    }
}
