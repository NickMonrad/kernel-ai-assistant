package com.kernel.ai.assistant

import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.StartListeningCueResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeWordCueTest {

    private val journal = WakeSessionJournal(
        generationId = 1L,
        sessionId = 1L,
        emit = { _, _, _, _ -> },
    )

    @Test
    fun `successful cue records CUE_REQUESTED then CUE_PLAYBACK_STARTED`() {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val testJournal = WakeSessionJournal(
            generationId = 1L,
            sessionId = 1L,
            emit = { type, _, _, metadata -> events.add(type to metadata()) },
        )
        testJournal.start()

        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true,
            context = StartListeningCueContext.WAKE_WORD,
            policyVersion = "2026-07-cue-v1",
            selectedStream = 4,
            currentVolume = 10,
            maxVolume = 25,
            routeClassification = "built_in_speaker",
        )

        playWakeCue(testJournal, cuePlayer)

        val types = events.map { it.first }
        assertTrue(types.contains(AcousticEventType.CUE_REQUESTED), "CUE_REQUESTED must be recorded")
        assertTrue(types.contains(AcousticEventType.CUE_PLAYBACK_STARTED), "CUE_PLAYBACK_STARTED must be recorded on success")
    }

    @Test
    fun `failed cue records CUE_REQUESTED then CUE_PLAYBACK_ERROR`() {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val testJournal = WakeSessionJournal(
            generationId = 1L,
            sessionId = 1L,
            emit = { type, _, _, metadata -> events.add(type to metadata()) },
        )
        testJournal.start()

        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = false,
            context = StartListeningCueContext.WAKE_WORD,
            failureCategory = "playback_start_failed",
        )

        playWakeCue(testJournal, cuePlayer)

        val types = events.map { it.first }
        assertTrue(types.contains(AcousticEventType.CUE_REQUESTED))
        assertTrue(types.contains(AcousticEventType.CUE_PLAYBACK_ERROR))
    }

    @Test
    fun `successful cue metadata includes all required fields`() {
        val result = StartListeningCueResult(
            started = true,
            context = StartListeningCueContext.WAKE_WORD,
            policyVersion = "2026-07-cue-v1",
            selectedStream = 4,
            currentVolume = 10,
            maxVolume = 25,
            routeClassification = "built_in_speaker",
        )
        val meta = cueMetadata(result)

        assertEquals("wake_word", meta["context"])
        assertEquals("2026-07-cue-v1", meta["policy_version"])
        assertEquals("4", meta["stream"])
        assertEquals("10", meta["current_volume"])
        assertEquals("25", meta["max_volume"])
        assertEquals("built_in_speaker", meta["route"])
    }

    @Test
    fun `failed cue metadata includes category`() {
        val result = StartListeningCueResult(
            started = false,
            context = StartListeningCueContext.WAKE_WORD,
            policyVersion = "2026-07-cue-v1",
            failureCategory = "playback_start_failed",
        )
        val meta = cueMetadata(result, isError = true)

        assertEquals("playback_start_failed", meta["category"])
        assertEquals("unknown", meta["stream"])
        assertEquals("unknown", meta["route"])
    }

    @Test
    fun `playWakeCue invokes cue player with WAKE_WORD context`() {
        val cuePlayer = mockk<StartListeningCuePlayer>()
        every { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) } returns StartListeningCueResult(
            started = true, context = StartListeningCueContext.WAKE_WORD,
        )

        playWakeCue(journal, cuePlayer)

        verify(exactly = 1) { cuePlayer.playCue(StartListeningCueContext.WAKE_WORD) }
    }

    @Test
    fun `WakeSessionJournal records exactly one terminal event on success`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.complete()
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals, "exactly one terminal event expected")
    }

    @Test
    fun `WakeSessionJournal records exactly one terminal event on cancel`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.cancel("test_failure")
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals, "exactly one terminal event expected")
    }

    @Test
    fun `WakeSessionJournal does not record duplicate terminal events`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.complete()
        j.cancel("ignored") // second call should be no-op
        val terminals = events.count { it in setOf(AcousticEventType.SESSION_COMPLETED, AcousticEventType.SESSION_CANCELLED) }
        assertEquals(1, terminals)
    }

    @Test
    fun `WakeSessionJournal does not record events after terminal`() {
        val events = mutableListOf<String>()
        val j = WakeSessionJournal(1L, 1L, emit = { type, _, _, _ -> events.add(type) })
        j.start()
        j.complete()
        j.record(AcousticEventType.STT_READY) // should be ignored
        assertTrue(events.none { it == AcousticEventType.STT_READY }, "post-terminal events must be ignored")
    }
}
