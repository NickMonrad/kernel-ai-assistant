package com.kernel.ai.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression tests for the notification Snooze button orchestration.
 *
 * PR #1416 introduced a regression where [ClockAlertContract.ACTION_SNOOZE_ALERT]
 * called [ClockAlertService.performSnooze] but did not [ClockAlertService.dismissAlert]
 * the current alert after a successful repository operation.
 *
 * These tests validate [runSnoozeAction] — the smallest testable orchestration
 * that owns both the snooze call and the dismiss decision.
 */
class ClockAlertSnoozeRegressionTest {

    @Test
    fun `snooze is invoked exactly once`() {
        var snoozeCount = 0
        runSnoozeAction(
            snooze = { snoozeCount++; true },
            dismiss = {},
        )
        assertEquals(1, snoozeCount, "Snooze operation must be called exactly once")
    }

    @Test
    fun `successful snooze invokes dismissal exactly once`() {
        var dismissCount = 0
        runSnoozeAction(
            snooze = { true },
            dismiss = { dismissCount++ },
        )
        assertEquals(1, dismissCount, "Successful snooze must invoke dismissal exactly once")
    }

    @Test
    fun `failed snooze does not invoke dismissal`() {
        var dismissed = false
        runSnoozeAction(
            snooze = { false },
            dismiss = { dismissed = true },
        )
        assertEquals(false, dismissed, "Failed snooze must not invoke dismissal")
    }

    @Test
    fun `repeated failures do not invoke dismissal`() {
        var dismissCount = 0
        repeat(3) {
            runSnoozeAction(
                snooze = { false },
                dismiss = { dismissCount++ },
            )
        }
        assertEquals(0, dismissCount, "No dismissal should occur after repeated failures")
    }

    @Test
    fun `helper does not invoke snooze more than once`() {
        var snoozeCount = 0
        runSnoozeAction(
            snooze = { snoozeCount++; true },
            dismiss = {},
        )
        assertEquals(1, snoozeCount, "Snooze operation must not be called more than once")
    }
}
