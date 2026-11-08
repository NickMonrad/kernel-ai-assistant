package com.kernel.ai.assistant

import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeSessionJournalTest {
    @Test
    fun `transcript evidence is normalized and contains no plaintext`() {
        val hash = transcriptEvidenceSha256("  What   TIME Is It?  ")

        assertEquals(
            "482bbb9128050f734a2e44f15c0bb1066848c6b18444a90c771061c030ff2534",
            hash,
        )
        assertEquals(hash, transcriptEvidenceSha256("what time is it?"))
        assertFalse(hash.contains("time"))
    }

    @Test
    fun `session emits one correlated ordered lifecycle`() {
        val events = mutableListOf<RecordedEvent>()
        val journal = journal(events)

        journal.start()
        journal.record(
            AcousticEventType.STT_START_REQUESTED,
            metadata = { mapOf("attempt" to "1") },
        )
        journal.record(AcousticEventType.STT_READY)
        assertTrue(journal.complete())

        assertEquals(
            listOf(
                AcousticEventType.VOICE_SESSION_STARTED,
                AcousticEventType.STT_START_REQUESTED,
                AcousticEventType.STT_READY,
                AcousticEventType.SESSION_COMPLETED,
            ),
            events.map(RecordedEvent::type),
        )
        assertTrue(events.all { it.generationId == 41L && it.sessionId == 73L })
        assertEquals(mapOf("attempt" to "1"), events[1].metadata)
    }

    @Test
    fun `first terminal wins and post-terminal events are ignored`() {
        val events = mutableListOf<RecordedEvent>()
        var lateMetadataCalls = 0
        val journal = journal(events)

        journal.start()
        assertTrue(journal.cancel("stt_unavailable"))
        assertFalse(journal.complete())
        assertFalse(journal.cancel("second_terminal"))
        journal.record(AcousticEventType.STT_READY) {
            lateMetadataCalls += 1
            mapOf("unexpected" to "value")
        }

        assertEquals(
            listOf(
                AcousticEventType.VOICE_SESSION_STARTED,
                AcousticEventType.SESSION_CANCELLED,
            ),
            events.map(RecordedEvent::type),
        )
        assertEquals(mapOf("category" to "stt_unavailable"), events.last().metadata)
        assertEquals(0, lateMetadataCalls)
    }

    @Test
    fun `session must start once before recording or finishing`() {
        val events = mutableListOf<RecordedEvent>()
        val journal = journal(events)

        journal.record(AcousticEventType.STT_READY)
        assertFalse(journal.complete())
        assertFalse(journal.cancel("not_started"))
        journal.start()

        assertThrows(IllegalStateException::class.java) { journal.start() }
        assertEquals(listOf(AcousticEventType.VOICE_SESSION_STARTED), events.map(RecordedEvent::type))
    }

    @Test
    fun `only matching alert command capture belongs to active wake session`() {
        val captureSessionId = 73L
        val wakeEvents = listOf(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId),
            VoiceInputEvent.SpeechDetected(VoiceCaptureMode.AlertCommand, captureSessionId),
            VoiceInputEvent.PartialTranscript(VoiceCaptureMode.AlertCommand, "partial", captureSessionId),
            VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "final", captureSessionId),
            VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "error", captureSessionId),
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand, captureSessionId),
        )
        val unrelatedEvents = listOf(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId - 1),
            VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "wrong capture", captureSessionId + 1),
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, captureSessionId),
            VoiceInputEvent.SpeechDetected(VoiceCaptureMode.SlotReply, captureSessionId),
            VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "private", captureSessionId),
            VoiceInputEvent.Transcript(VoiceCaptureMode.SlotReply, "private", captureSessionId),
            VoiceInputEvent.Error(VoiceCaptureMode.Command, "private", captureSessionId),
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.SlotReply, captureSessionId),
        )

        assertTrue(wakeEvents.all { it.isWakeSessionEvent(captureSessionId) })
        assertFalse(unrelatedEvents.any { it.isWakeSessionEvent(captureSessionId) })
    }

    private fun journal(events: MutableList<RecordedEvent>) = WakeSessionJournal(
        generationId = 41L,
        sessionId = 73L,
        emit = { type, generationId, sessionId, metadata ->
            events += RecordedEvent(type, generationId, sessionId, metadata())
        },
    )

    private data class RecordedEvent(
        val type: String,
        val generationId: Long,
        val sessionId: Long,
        val metadata: Map<String, String>,
    )
}
