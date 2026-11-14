package com.kernel.ai.assistant

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.voice.AcousticEventType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeSessionJournalJandalTest {

    @Test
    fun `partial transcripts never reach the Jandal log`() {
        recordWakeJandalEvent(AcousticEventType.STT_PARTIAL, generationId = 7L, sessionId = 9L)
        recordWakeJandalEvent(AcousticEventType.STT_FINAL, generationId = 7L, sessionId = 9L)
        JandalEvents.awaitWritesForTest()

        val lines = JandalEvents.exportLines()
        assertFalse(lines.any { it.contains(" wake stt_partial ") })
        assertTrue(lines.any { it.contains(" wake stt_final ") })
    }
}
