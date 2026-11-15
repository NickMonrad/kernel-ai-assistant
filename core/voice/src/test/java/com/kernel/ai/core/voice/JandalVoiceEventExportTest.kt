package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.TtsFailure
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** The voice route and TTS failure constants must reach the exported lines, not `[redacted]` (#1870). */
class JandalVoiceEventExportTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun installLog() {
            JandalEvents.install(Files.createTempFile("jandal-voice-export", ".log").toFile())
        }
    }

    @Test
    fun `voice route and tts failure constants are exported`() {
        JandalEvents.record("voice", "stt_route", "route" to AutoSttRoute.Zipformer, "online_fallback" to false)
        JandalEvents.record("tts", "speak_failed", "category" to TtsFailure.ENGINE_START)
        JandalEvents.awaitWritesForTest()
        val lines = JandalEvents.exportLines()

        assertTrue(lines.any { it.contains(" voice stt_route ") && it.contains("route=ZIPFORMER") }, lines.toString())
        assertTrue(lines.any { it.contains(" tts speak_failed ") && it.contains("category=ENGINE_START") }, lines.toString())
    }
}
