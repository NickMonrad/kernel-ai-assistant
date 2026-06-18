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
            resolveAlertLifecycleAction(ClockEventType.TIMER, autoSnoozeCount = 0, maxAutoSnoozes = 1),
        )
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.TIMER, autoSnoozeCount = 1, maxAutoSnoozes = 1),
        )
    }

    @Test
    fun `alarm first ring with max 1 resolves to auto-snooze`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_SNOOZE,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 1),
        )
    }

    @Test
    fun `alarm first ring with max 0 resolves to auto-stop`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 0),
        )
    }

    @Test
    fun `alarm snooze re-trigger with max 1 resolves to auto-stop`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 1, maxAutoSnoozes = 1),
        )
    }

    @Test
    fun `pre-alarm resolves to null (no action)`() {
        assertNull(
            resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 1),
        )
        assertNull(
            resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, autoSnoozeCount = 5, maxAutoSnoozes = 1),
        )
    }

    // ── Auto-snooze count behaviour ──────────────────────────────────

    @Test
    fun `autoSnoozesZero first unattended ring auto-stops`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 0),
        )
    }

    @Test
    fun `autoSnoozesOne first ring snoozes then second stops`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_SNOOZE,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 1),
        )
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 1, maxAutoSnoozes = 1),
        )
    }

    @Test
    fun `autoSnoozesTwo first two snooze then third stops`() {
        assertEquals(ClockAlertLifecycleAction.AUTO_SNOOZE,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 2))
        assertEquals(ClockAlertLifecycleAction.AUTO_SNOOZE,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 1, maxAutoSnoozes = 2))
        assertEquals(ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 2, maxAutoSnoozes = 2))
    }

    @Test
    fun `autoSnoozesThree first three snooze then fourth stops`() {
        for (count in 0..2) {
            assertEquals(ClockAlertLifecycleAction.AUTO_SNOOZE,
                resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = count, maxAutoSnoozes = 3))
        }
        assertEquals(ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.ALARM, autoSnoozeCount = 3, maxAutoSnoozes = 3))
    }

    // ── lifecycleTimeoutDurationMs ───────────────────────────────────

    @Test
    fun `timer timeout uses configured timer duration`() {
        assertEquals(
            30_000L,
            lifecycleTimeoutDurationMs(ClockEventType.TIMER, autoSnoozeCount = 0, maxAutoSnoozes = 1, timerDurationMs = 30_000L, alarmDurationMs = 60_000L),
        )
        assertEquals(
            120_000L,
            lifecycleTimeoutDurationMs(ClockEventType.TIMER, autoSnoozeCount = 1, maxAutoSnoozes = 1, timerDurationMs = 120_000L, alarmDurationMs = 60_000L),
        )
    }

    @Test
    fun `alarm first ring timeout uses configured alarm duration`() {
        assertEquals(
            30_000L,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 1, timerDurationMs = 60_000L, alarmDurationMs = 30_000L),
        )
        assertEquals(
            120_000L,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 1, timerDurationMs = 60_000L, alarmDurationMs = 120_000L),
        )
    }

    @Test
    fun `alarm auto-stop with max 0 uses alarm duration`() {
        assertEquals(
            45_000L,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 0, timerDurationMs = 15_000L, alarmDurationMs = 45_000L),
        )
    }

    @Test
    fun `alarm auto-stop after one snooze uses alarm duration`() {
        assertEquals(
            45_000L,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, autoSnoozeCount = 1, maxAutoSnoozes = 1, timerDurationMs = 15_000L, alarmDurationMs = 45_000L),
        )
    }

    @Test
    fun `alarm auto-stop after two snoozes uses alarm duration`() {
        assertEquals(
            45_000L,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, autoSnoozeCount = 2, maxAutoSnoozes = 2, timerDurationMs = 15_000L, alarmDurationMs = 45_000L),
        )
    }

    @Test
    fun `alarm auto-stop after three snoozes uses alarm duration`() {
        assertEquals(
            45_000L,
            lifecycleTimeoutDurationMs(ClockEventType.ALARM, autoSnoozeCount = 3, maxAutoSnoozes = 3, timerDurationMs = 15_000L, alarmDurationMs = 45_000L),
        )
    }

    @Test
    fun `pre-alarm timeout is zero`() {
        assertEquals(0L, lifecycleTimeoutDurationMs(ClockEventType.PRE_ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 1, timerDurationMs = 60_000L, alarmDurationMs = 60_000L))
        assertEquals(0L, lifecycleTimeoutDurationMs(ClockEventType.PRE_ALARM, autoSnoozeCount = 5, maxAutoSnoozes = 1, timerDurationMs = 60_000L, alarmDurationMs = 60_000L))
    }

    // ── Duration constants ──────────────────────────────────────────

    @Test
    fun `TIMER_AUTO_STOP_DURATION_MS is about one minute`() {
        assertEquals(60_000L, TIMER_AUTO_STOP_DURATION_MS)
    }

    @Test
    fun `ALARM_AUTO_SNOOZE_DURATION_MS is about one minute`() {
        assertEquals(60_000L, ALARM_AUTO_SNOOZE_DURATION_MS)
    }

    // ── TriggeredClockAlert integration ──────────────────────────────

    @Test
    fun `alarm with autoSnoozeCount 1 and max 1 resolves to auto-stop`() {
        val alert = TriggeredClockAlert(
            ownerId = "alarm-1",
            type = ClockEventType.ALARM,
            title = "Test alarm",
            label = "Test",
            autoSnoozeCount = 1,
        )
        val action = resolveAlertLifecycleAction(alert.type, alert.autoSnoozeCount, maxAutoSnoozes = 1)
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP, action,
            "durably-counted snooze re-trigger with autoSnoozeCount=1 must auto-stop",
        )
    }

    @Test
    fun `alarm with autoSnoozeCount 0 and max 1 resolves to auto-snooze`() {
        val alert = TriggeredClockAlert(
            ownerId = "alarm-2",
            type = ClockEventType.ALARM,
            title = "Test alarm",
            label = "Test",
            autoSnoozeCount = 0,
        )
        val action = resolveAlertLifecycleAction(alert.type, alert.autoSnoozeCount, maxAutoSnoozes = 1)
        assertEquals(
            ClockAlertLifecycleAction.AUTO_SNOOZE, action,
            "first-ring alarm must auto-snooze",
        )
    }

    @Test
    fun `timer auto-stops regardless of autoSnoozeCount`() {
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.TIMER, autoSnoozeCount = 0, maxAutoSnoozes = 1),
        )
        assertEquals(
            ClockAlertLifecycleAction.AUTO_STOP,
            resolveAlertLifecycleAction(ClockEventType.TIMER, autoSnoozeCount = 3, maxAutoSnoozes = 1),
        )
    }

    @Test
    fun `pre-alarm has no lifecycle timeout regardless of count`() {
        assertNull(resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, autoSnoozeCount = 0, maxAutoSnoozes = 1))
        assertNull(resolveAlertLifecycleAction(ClockEventType.PRE_ALARM, autoSnoozeCount = 3, maxAutoSnoozes = 1))
    }
}
