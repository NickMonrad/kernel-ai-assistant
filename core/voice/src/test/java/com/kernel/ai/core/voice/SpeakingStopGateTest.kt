package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Both engines pair SpeakingStopped with SpeakingStarted through this gate (#1873 SF1). The engines
 * themselves need Android services and native runners, so these tests pin the pairing rule.
 */
class SpeakingStopGateTest {

    @Test
    fun `stop is claimed once for the reply that started`() {
        val gate = SpeakingStopGate()
        gate.markStarted(1L)

        assertTrue(gate.claimStopped(1L))
        assertFalse(gate.claimStopped(1L))
    }

    @Test
    fun `stop without a started reply is never claimed`() {
        val gate = SpeakingStopGate()

        assertFalse(gate.claimStopped(1L))
    }

    @Test
    fun `stale unwind after the next reply started claims nothing`() {
        val gate = SpeakingStopGate()
        gate.markStarted(1L)
        gate.markStarted(2L) // reply 2 emitted SpeakingStarted before reply 1 unwound

        assertFalse(gate.claimStopped(1L))
        assertTrue(gate.claimStopped(2L))
    }

    @Test
    fun `stale unwind cannot end the newer reply's speech`() {
        val gate = SpeakingStopGate()
        gate.markStarted(1L)
        gate.markStarted(2L)
        gate.claimStopped(1L)

        // Reply 2 is still speaking: its own stop is still owed.
        assertTrue(gate.claimStopped(2L))
    }

    @Test
    fun `a claimed reply cannot claim again after a new reply starts`() {
        val gate = SpeakingStopGate()
        gate.markStarted(1L)
        assertTrue(gate.claimStopped(1L))

        gate.markStarted(2L)
        assertFalse(gate.claimStopped(1L))
        assertTrue(gate.claimStopped(2L))
    }
}
