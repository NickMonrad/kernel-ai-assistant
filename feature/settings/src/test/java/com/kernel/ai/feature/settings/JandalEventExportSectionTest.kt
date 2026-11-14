package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.LifecycleOutcome
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files

class JandalEventExportSectionTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun installLog() {
            JandalEvents.install(Files.createTempFile("jandal-export-test", ".log").toFile())
        }
    }

    @Test
    fun `section header is present and events are listed`() {
        val section = renderJandalEventSection(listOf("ts|service|wake_word|outcome=STOPPED"), scrub = { it })

        assertTrue(section.startsWith("Recent Jandal events (in-app log, last 24 hours)"))
        assertTrue(section.contains("ts|service|wake_word|outcome=STOPPED"))
    }

    @Test
    fun `empty log says so`() {
        val section = renderJandalEventSection(emptyList(), scrub = { it })

        assertTrue(section.contains("No Jandal events recorded."))
    }

    @Test
    fun `each line goes through the scrubber`() {
        val section = renderJandalEventSection(listOf("raw line"), scrub = { "scrubbed" })

        assertTrue(section.contains("scrubbed"))
        assertFalse(section.contains("raw line"))
    }

    @Test
    fun `free text never reaches the section with chat opt-in on or off`() {
        JandalEvents.record("chat", "message_sent", "text" to "call Alice 0412345678 about milk")
        JandalEvents.record("permission", "mic_result", "outcome" to LifecycleOutcome.GRANTED)
        JandalEvents.awaitWritesForTest()
        val lines = JandalEvents.exportLines()

        val optIn = renderJandalEventSection(lines, scrub = { it })
        val optOut = renderJandalEventSection(lines, scrub = DiagnosticLogRedactor::redactText)

        for (section in listOf(optIn, optOut)) {
            assertFalse(section.contains("Alice"))
            assertFalse(section.contains("milk"))
            assertFalse(section.contains("0412345678"))
            assertTrue(section.contains("message_sent"))
            assertTrue(section.contains("GRANTED"))
        }
    }
}
