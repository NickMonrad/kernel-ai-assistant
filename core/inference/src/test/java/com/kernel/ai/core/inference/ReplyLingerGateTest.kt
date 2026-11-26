package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplyLingerGateTest {
    private class FakeDelays {
        class Pending(val delayMs: Long, val action: () -> Unit, var cancelled: Boolean = false)

        val pending = mutableListOf<Pending>()

        fun schedule(delayMs: Long, action: () -> Unit): CancelDelayed {
            val item = Pending(delayMs, action)
            pending += item
            return CancelDelayed { item.cancelled = true }
        }

        /** Fires the most recent live action, as the main handler would after its delay. */
        fun fireLive() {
            val live = pending.last { !it.cancelled }
            live.cancelled = true
            live.action()
        }
    }

    private var screenOff = true
    private var stops = 0
    private val delays = FakeDelays()
    private val gate = ReplyLingerGate(
        lingerMs = LINGER_MS,
        isScreenOffOrLocked = { screenOff },
        schedule = delays::schedule,
        stopService = { stops++ },
    )

    @Test
    fun `spoken reply ending screen-off lingers for the cap then stops`() {
        gate.onGenerationStart()
        gate.onGenerationEnd(lingerForReply = true)

        assertTrue(gate.isLingering)
        assertEquals(LINGER_MS, delays.pending.single().delayMs)
        assertEquals(0, stops)

        delays.fireLive()

        assertFalse(gate.isLingering)
        assertEquals(1, stops)
    }

    @Test
    fun `linger expiry does not stop the service when a new generation has started`() {
        gate.onGenerationStart()
        gate.onGenerationEnd(lingerForReply = true)
        // A generation starts while the linger is pending; the start cancels it, so nothing fires.
        gate.onGenerationStart()

        assertFalse(gate.isLingering)
        assertTrue(delays.pending.single().cancelled)
        assertEquals(0, stops)

        // Even if the cancelled action were run, it must not stop a running generation.
        delays.pending.single().action()
        assertEquals(0, stops)
    }

    @Test
    fun `new start cancels a pending linger and the later end lingers again`() {
        gate.onGenerationStart()
        gate.onGenerationEnd(lingerForReply = true)
        gate.onGenerationStart()
        gate.onGenerationEnd(lingerForReply = true)

        assertEquals(2, delays.pending.size)
        assertTrue(delays.pending[0].cancelled)
        assertFalse(delays.pending[1].cancelled)

        delays.fireLive()
        assertEquals(1, stops)
    }

    @Test
    fun `cancel path stops at once and cancels any pending linger`() {
        gate.onGenerationStart()
        gate.onGenerationEnd(lingerForReply = true)
        gate.onGenerationStart()

        gate.onGenerationEnd(lingerForReply = false)

        assertEquals(1, stops)
        assertFalse(gate.isLingering)
    }

    @Test
    fun `reply ending with the screen on stops at once`() {
        screenOff = false
        gate.onGenerationStart()
        gate.onGenerationEnd(lingerForReply = true)

        assertEquals(1, stops)
        assertTrue(delays.pending.isEmpty())
    }

    @Test
    fun `generation that is not a spoken reply stops at once with the screen off`() {
        gate.onGenerationStart()
        gate.onGenerationEnd(lingerForReply = false)

        assertEquals(1, stops)
        assertTrue(delays.pending.isEmpty())
    }

    private companion object {
        const val LINGER_MS = 30_000L
    }
}
