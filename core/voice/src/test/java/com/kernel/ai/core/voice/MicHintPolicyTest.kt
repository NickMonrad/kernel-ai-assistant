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

    @Test
    fun `five taps show the hint on the fifth and the sixth tap hides it`() {
        var stored = 0
        val shownPerTap = (1..5).map { _ ->
            stored = MicHintPolicy.nextUseCount(stored)
            MicHintPolicy.shouldShowHint(stored)
        }
        assertEquals(listOf(true, true, true, true, true), shownPerTap)

        stored = MicHintPolicy.nextUseCount(stored)
        assertEquals(6, stored)
        assertEquals(false, MicHintPolicy.shouldShowHint(stored))
    }

    @Test
    fun `count stops rising once the hint is retired`() {
        var stored = 0
        repeat(10) { stored = MicHintPolicy.nextUseCount(stored) }
        assertEquals(6, stored)
    }
}
