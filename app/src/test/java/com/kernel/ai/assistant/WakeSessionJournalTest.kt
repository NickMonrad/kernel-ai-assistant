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
    fun `only alert command events belong to the active wake session`() {
        val wakeEvents = listOf(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand),
            VoiceInputEvent.SpeechDetected(VoiceCaptureMode.AlertCommand),
            VoiceInputEvent.PartialTranscript(VoiceCaptureMode.AlertCommand, "partial"),
            VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "final"),
            VoiceInputEvent.Error(VoiceCaptureMode.AlertCommand, "error"),
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.AlertCommand),
        )
        val unrelatedEvents = listOf(
            VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command),
            VoiceInputEvent.SpeechDetected(VoiceCaptureMode.SlotReply),
            VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "private"),
            VoiceInputEvent.Transcript(VoiceCaptureMode.SlotReply, "private"),
            VoiceInputEvent.Error(VoiceCaptureMode.Command, "private"),
            VoiceInputEvent.ListeningStopped(VoiceCaptureMode.SlotReply),
        )

        assertTrue(wakeEvents.all(VoiceInputEvent::isWakeSessionEvent))
        assertFalse(unrelatedEvents.any(VoiceInputEvent::isWakeSessionEvent))
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
