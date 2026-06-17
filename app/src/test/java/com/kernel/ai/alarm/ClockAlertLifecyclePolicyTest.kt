package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClockAlertLifecyclePolicyTest {

    // ── resolveAlertLifecycleAction ──────────────────────────────────

    @Test
    fun `timer always resolves to auto-stop`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.TIMER, isSnoozeRetrigger = false),
        )
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.TIMER, isSnoozeRetrigger = true),
        )
    }

    @Test
    fun `alarm first ring resolves to auto-snooze`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_SNOOZE,
            resolveAlertLifecycleAction(ClockEventType.ALARM, isSnoozeRetrigger = false),
        )
    }

    @Test
    fun `alarm snooze re-trigger resolves to auto-stop`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, isSnoozeRetrigger = true),
        )
    }

    @Test
    fun `pre-alarm resolves to null (no action)`() {
        assertNull(
            resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, isSnoozeRetrigger = false),
        )
        assertNull(
            resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, isSnoozeRetrigger = true),
        )
    }

    // ── lifecycleTimeoutDurationMs ───────────────────────────────────

    @Test
    fun `timer timeout uses TIMER_AUTO_STOP_DURATION_MS`() {
        assertEquals(
            TIMER_AUTO_STOP_DURATION_MS,
            lifecycleTimeoutDurationMs(ClockEventType.TIMER, isSnoozeRetrigger = false),
        )
        assertEquals(
            TIMER_AUTO_STOP_DURATION_MS,
            lifecycleTimeoutDurationMs(ClockEventType.TIMER, isSnoozeRetrigger = true),
        )
    }

    @Test
    fun `alarm first ring timeout uses ALARM_AUTO_SNOOZE_DURATION_MS`() {
        assertEquals(
            ALARM_AUTO_SNOOZE_DURATION_MS,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, isSnoozeRetrigger = false),
        )
    }

    @Test
    fun `alarm snooze re-trigger timeout equals TIMER_AUTO_STOP_DURATION_MS`() {
        assertEquals(
            TIMER_AUTO_STOP_DURATION_MS,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, isSnoozeRetrigger = true),
        )
    }

    @Test
    fun `pre-alarm timeout is zero`() {
        assertEquals(0L, lifecycleTimeoutDurationMs(ClockEventType.PRE_ALARM, isSnoozeRetrigger = false))
        assertEquals(0L, lifecycleTimeoutDurationMs(ClockEventType.PRE_ALARM, isSnoozeRetrigger = true))
    }

    // ── Duration constants ──────────────────────────────────────────

    @Test
    fun `TIMER_AUTO_STOP_DURATION_MS is about one minute`() {
        assertEquals(60_000L, TIMER_AUTO_STOP_DURATION_MS)
    }


    // ── Durable snooze re-trigger flag (integration) ─────────────────
    // The isSnoozeRetrigger flag comes from the scheduled event model,
    // not from a companion set. These tests verify the lifecycle decision
    // driven by the durable flag in TriggeredClockAlert.

    @Test
    fun `alarm with isSnoozeRetrigger true resolves to auto-stop`() {
        val alert = TriggeredClockAlert(
            ownerId = "alarm-1",
            type = ClockEventType.ALARM,
            title = "Test alarm",
            label = "Test",
            isSnoozeRetrigger = true,
        )
        val action = resolveAlertLifecycleAction(alert.type, alert.isSnoozeRetrigger)
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP, action,
            "durably-flagged snooze re-trigger must auto-stop, not auto-snooze",
        )
    }

    @Test
    fun `alarm with isSnoozeRetrigger false resolves to auto-snooze`() {
        val alert = TriggeredClockAlert(
            ownerId = "alarm-2",
            type = ClockEventType.ALARM,
            title = "Test alarm",
            label = "Test",
            isSnoozeRetrigger = false,
        )
        val action = resolveAlertLifecycleAction(alert.type, alert.isSnoozeRetrigger)
        assertEquals(
            ClockAlertLifecycleAction.AUTO_SNOOZE, action,
            "first-ring alarm must auto-snooze",
        )
    }

    @Test
    fun `timer auto-stops regardless of isSnoozeRetrigger`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.TIMER, isSnoozeRetrigger = true),
        )
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.TIMER, isSnoozeRetrigger = false),
        )
    }

    @Test
    fun `pre-alarm has no lifecycle timeout regardless of flag`() {
        assertNull(resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, isSnoozeRetrigger = false))
        assertNull(resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, isSnoozeRetrigger = true))
    }
    @Test
    fun `ALARM_AUTO_SNOOZE_DURATION_MS is about one minute`() {
        assertEquals(60_000L, ALARM_AUTO_SNOOZE_DURATION_MS)
    }
}
