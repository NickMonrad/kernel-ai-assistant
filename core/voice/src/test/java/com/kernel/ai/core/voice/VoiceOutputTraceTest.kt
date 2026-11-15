package com.kernel.ai.core.voice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceOutputTraceTest {
    @AfterEach
    fun tearDown() {
        VoiceOutputTrace.enabled = false
        VoiceOutputTrace.sink = null
    }

    @Test
    fun `line has engine, cleaned length and single-line text`() {
        assertEquals(
            listOf("speaking_started engine=sherpa chars=19 part=1/1 text=It's 4:21 pm. Sweet"),
            VoiceOutputTrace.lines("sherpa", "It's 4:21 pm.\n Sweet"),
        )
    }

    @Test
    fun `long text is split into numbered parts that join back`() {
        val text = "a".repeat(VoiceOutputTrace.MAX_TEXT_PER_LINE + 10)
        val lines = VoiceOutputTrace.lines("inflect", text)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("speaking_started engine=inflect chars=${text.length} part=1/2 text="))
        assertTrue(lines[1].startsWith("speaking_started engine=inflect chars=${text.length} part=2/2 text="))
        assertEquals(text, lines.joinToString("") { it.substringAfter(" text=") })
    }

    @Test
    fun `nothing is traced unless the app enables it`() {
        val seen = mutableListOf<String>()
        VoiceOutputTrace.sink = { seen += it }
        VoiceOutputTrace.speakingStarted("sherpa", "hello")
        assertEquals(emptyList<String>(), seen)
        VoiceOutputTrace.enabled = true
        VoiceOutputTrace.speakingStarted("sherpa", "hello")
        assertEquals(listOf("speaking_started engine=sherpa chars=5 part=1/1 text=hello"), seen)
    }
}
