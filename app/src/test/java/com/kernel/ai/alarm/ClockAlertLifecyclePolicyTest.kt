package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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


    // ── Snoozed re-trigger flag preservation regression ──────────────
    // This test simulates the full flow that was broken by double-
    // consumption of the snoozed owner ID marker. The flag from
    // consumeSnoozedOwnerId() at TRIGGER_ALERT time must be preserved
    // and passed to resolveAlertLifecycleAction() at timeout time.
    // See PR #1277, bug fix commit after review.

    @Test
    fun `snoozed alarm re-trigger flag consumed once and used for timeout`() {
        // Simulate auto-snooze: performAutoSnooze adds the ownerId
        ClockAlertService.addSnoozedOwnerId("alarm-retrigger-test")

        // Simulate TRIGGER_ALERT: onStartCommand consumes the marker once
        val isSnoozeRetrigger = ClockAlertService.consumeSnoozedOwnerId("alarm-retrigger-test")
        assertTrue(isSnoozeRetrigger, "snoozed marker must be consumed on re-trigger")

        // Verify marker is consumed: second call returns false
        assertFalse(
            ClockAlertService.consumeSnoozedOwnerId("alarm-retrigger-test"),
            "marker should only be consumable once",
        )

        // Simulate lifecycle timeout: the preserved flag drives the action
        val action = resolveAlertLifecycleAction(ClockEventType.ALARM, isSnoozeRetrigger)
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP, action,
            "snoozed re-trigger must auto-stop, not auto-snooze again",
        )
    }

    @Test
    fun `full snoozed alarm lifecycle first snoozes then re-trigger auto-stops`() {
        // Phase 1: First alarm ring — no snoozed marker
        assertFalse(
            ClockAlertService.consumeSnoozedOwnerId("alarm-1"),
            "first ring has no snoozed marker",
        )

        // First timeout: isSnoozeRetrigger = false → auto-snooze
        assertEquals(
            ClockAlertLifecycleAction.AUTO_SNOOZE,
            resolveAlertLifecycleAction(ClockEventType.ALARM, isSnoozeRetrigger = false),
        )

        // Simulate auto-snooze: performAutoSnooze saves the ownerId
        ClockAlertService.addSnoozedOwnerId("alarm-1")

        // Clean up the companion state for test isolation
        // Phase 2: Snoozed alarm re-triggers — marker exists
        val isSnoozeRetrigger = ClockAlertService.consumeSnoozedOwnerId("alarm-1")
        assertTrue(isSnoozeRetrigger, "re-trigger has snoozed marker")

        // Second timeout: preserved flag is true → auto-stop
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, isSnoozeRetrigger),
        )
    }

    @Test
    fun `timer auto-stop unaffected by snoozed marker`() {
        // Even with a stale snoozed marker, timer always auto-stops
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
    fun `ALARM_AUTO_SNOOZE_DURATION_MS is about one minute`() {
        assertEquals(60_000L, ALARM_AUTO_SNOOZE_DURATION_MS)
    }
}
