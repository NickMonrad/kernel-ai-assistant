package com.kernel.ai.assistant

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.voice.AcousticEventType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WakeSessionJournalJandalTest {

    private fun exportAfter(block: () -> Unit): List<String> {
        JandalEvents.install(File.createTempFile("jandal-wake", ".log"))
        val mark = System.nanoTime() % 999_999_999L
        JandalEvents.record("test", "mark", "id" to mark)
        block()
        JandalEvents.awaitWritesForTest()
        val exported = JandalEvents.exportLines()
        return exported.drop(exported.indexOfLast { " test mark " in it && "id=$mark" in it } + 1)
    }

    @Test
    fun `stt error category is exported as a typed enum`() {
        val lines = exportAfter {
            recordWakeJandalEvent(AcousticEventType.STT_ERROR, 1L, 2L) { mapOf("category" to "stt_unavailable") }
        }

        val line = lines.single { " wake stt_error " in it }
        assertTrue("category=STT_UNAVAILABLE" in line, line)
    }

    @Test
    fun `unlisted category maps to OTHER`() {
        val lines = exportAfter {
            recordWakeJandalEvent(AcousticEventType.CUE_PLAYBACK_ERROR, 1L, 2L) {
                mapOf("category" to "device_specific_failure")
            }
        }

        val line = lines.single { " wake cue_playback_error " in it }
        assertTrue("category=OTHER" in line, line)
        assertFalse("device_specific_failure" in line, line)
    }

    @Test
    fun `command routing failure exports outcome and category enums`() {
        val lines = exportAfter {
            recordWakeJandalEvent(AcousticEventType.COMMAND_ROUTING_RESULT, 1L, 2L) {
                mapOf("outcome" to "failed", "category" to "route_activity_failed")
            }
        }

        val line = lines.single { " wake command_routing_result " in it }
        assertTrue("outcome=FAILED" in line, line)
        assertTrue("category=ROUTE_ACTIVITY_FAILED" in line, line)
    }

    @Test
    fun `final transcript hash and length are not exported`() {
        val lines = exportAfter {
            recordWakeJandalEvent(AcousticEventType.STT_FINAL, 1L, 2L) {
                mapOf("length" to "12", "normalized_transcript_sha256" to "deadbeef-transcript-hash")
            }
        }

        val line = lines.single { " wake stt_final " in it }
        assertFalse("deadbeef-transcript-hash" in line, line)
        assertFalse("length=" in line, line)
    }
}
