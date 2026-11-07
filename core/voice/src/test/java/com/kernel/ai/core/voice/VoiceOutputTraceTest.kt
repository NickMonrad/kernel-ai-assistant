package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class VoiceOutputTraceTest {
    @Test
    fun `trace is off unless the app enables it`() {
        assertFalse(VoiceOutputTrace.enabled)
    }

    @Test
    fun `line has engine, length and single-line text`() {
        assertEquals(
            "speaking_started engine=sherpa chars=20 text=It's 4:21 pm. Sweet",
            VoiceOutputTrace.line("sherpa", "It's 4:21 pm.\n Sweet"),
        )
    }
}
