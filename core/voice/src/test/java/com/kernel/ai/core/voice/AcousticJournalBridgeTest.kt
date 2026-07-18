package com.kernel.ai.core.voice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AcousticJournalBridgeTest {
    @AfterEach
    fun resetRecorder() {
        AcousticJournalBridge.install(AcousticEventRecorder.NoOp)
    }

    @Test
    fun `disabled bridge does not allocate sequence or metadata`() {
        var sequenceCalls = 0
        var metadataCalls = 0
        AcousticJournalBridge.install(AcousticEventRecorder.NoOp)

        AcousticJournalBridge.record(
            type = AcousticEventType.DETECTOR_REARMED,
            sequenceProvider = SequenceProvider {
                sequenceCalls += 1
                sequenceCalls.toLong()
            },
            metadata = {
                metadataCalls += 1
                mapOf("category" to "must_not_be_built")
            },
        )

        assertEquals(0, sequenceCalls)
        assertEquals(0, metadataCalls)
    }

    @Test
    fun `enabled bridge evaluates sequence and metadata once`() {
        val events = mutableListOf<AcousticEvent>()
        var sequenceCalls = 0
        var metadataCalls = 0
        AcousticJournalBridge.install(events::add)

        AcousticJournalBridge.record(
            type = AcousticEventType.DETECTOR_REARMED,
            sequenceProvider = SequenceProvider {
                sequenceCalls += 1
                17L
            },
            metadata = {
                metadataCalls += 1
                mapOf("category" to "enabled")
            },
        )

        assertEquals(1, sequenceCalls)
        assertEquals(1, metadataCalls)
        assertEquals(17L, events.single().sequence)
        assertEquals(mapOf("category" to "enabled"), events.single().metadata)
    }
}
