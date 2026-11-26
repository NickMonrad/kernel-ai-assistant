package com.kernel.ai.assistant

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WakeUserPauseExportTest {

    @Test
    fun `the support export tells a user pause from a resume`() {
        JandalEvents.install(File.createTempFile("jandal-user-pause", ".log"))
        JandalEvents.awaitWritesForTest()
        val mark = System.nanoTime() % 999_999_999L
        JandalEvents.record("test", "mark", "id" to mark)

        recordWakeUserPauseEvent(paused = true)
        recordWakeUserPauseEvent(paused = false)
        JandalEvents.awaitWritesForTest()
        val exported = JandalEvents.exportLines()
        val lines = exported.drop(exported.indexOfLast { " test mark " in it && "id=$mark" in it } + 1)

        assertTrue(
            lines.any { Regex(" wake_user_pause paused=true(\\s|$)").containsMatchIn(it) },
            "a user pause must export as paused=true",
        )
        assertTrue(
            lines.any { Regex(" wake_user_pause paused=false(\\s|$)").containsMatchIn(it) },
            "a user resume must export as paused=false",
        )
    }
}
