package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicHintPolicyTest {

    @Test
    fun `hint shows for exactly the first five mic uses then stops`() {
        val shown = (1..8).count { MicHintPolicy.shouldShowHint(it) }

        assertEquals(5, shown)
        assertEquals(true, MicHintPolicy.shouldShowHint(5))
        assertEquals(false, MicHintPolicy.shouldShowHint(6))
    }

    @Test
    fun `no hint before the first use is recorded`() {
        assertEquals(false, MicHintPolicy.shouldShowHint(0))
    }
}
