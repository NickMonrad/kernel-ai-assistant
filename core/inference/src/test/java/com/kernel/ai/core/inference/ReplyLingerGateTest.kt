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
    fun `completed reply ending screen-off lingers for the cap then stops`() {
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)

        assertTrue(gate.isLingering)
        assertEquals(LINGER_MS, delays.pending.single().delayMs)
        assertEquals(0, stops)

        delays.fireLive()

        assertFalse(gate.isLingering)
        assertEquals(1, stops)
    }

    @Test
    fun `linger expiry does not stop the service when a new reply has started`() {
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)
        // A reply starts while the linger is pending; the start cancels it, so nothing fires.
        gate.onReplyStart()

        assertFalse(gate.isLingering)
        assertTrue(delays.pending.single().cancelled)
        assertEquals(0, stops)

        // Even if the cancelled action were run, it must not stop a running reply.
        delays.pending.single().action()
        assertEquals(0, stops)
    }

    @Test
    fun `new reply start cancels a pending linger and the later end lingers again`() {
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)

        assertEquals(2, delays.pending.size)
        assertTrue(delays.pending[0].cancelled)
        assertFalse(delays.pending[1].cancelled)

        delays.fireLive()
        assertEquals(1, stops)
    }

    @Test
    fun `cancel path stops at once and cancels any pending linger`() {
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)
        gate.onReplyStart()

        gate.onReplyEnd(lingerAfterCompletion = false)

        assertEquals(1, stops)
        assertFalse(gate.isLingering)
    }

    @Test
    fun `reply ending with the screen on stops at once`() {
        screenOff = false
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)

        assertEquals(1, stops)
        assertTrue(delays.pending.isEmpty())
    }

    @Test
    fun `reply that did not complete normally stops at once with the screen off`() {
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = false)

        assertEquals(1, stops)
        assertTrue(delays.pending.isEmpty())
    }

    @Test
    fun `internal generation ending during a reply linger leaves the linger to its deadline`() {
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)

        // Chat title generation runs during the linger: it neither cancels nor ends the linger.
        gate.onInternalStart()
        assertTrue(gate.isLingering)
        gate.onInternalEnd()

        assertEquals(0, stops)
        assertTrue(gate.isLingering)
        assertFalse(delays.pending.single().cancelled)

        delays.fireLive()
        assertEquals(1, stops)
    }

    @Test
    fun `internal generation still running at the linger deadline does not stop the service early`() {
        gate.onReplyStart()
        gate.onReplyEnd(lingerAfterCompletion = true)
        gate.onInternalStart()

        delays.fireLive()
        assertEquals(0, stops)

        // The service stops when the internal generation ends, since no linger is left.
        gate.onInternalEnd()
        assertEquals(1, stops)
    }

    @Test
    fun `internal generation with no reply linger stops at once`() {
        gate.onInternalStart()
        gate.onInternalEnd()

        assertEquals(1, stops)
        assertTrue(delays.pending.isEmpty())
    }

    private companion object {
        const val LINGER_MS = 30_000L
    }
}
