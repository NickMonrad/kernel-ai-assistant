package com.kernel.ai.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression tests for the notification Snooze button path.
 *
 * PR #1416 introduced a regression where [ClockAlertContract.ACTION_SNOOZE_ALERT]
 * called [ClockAlertService.performSnooze] but did not [ClockAlertService.dismissAlert]
 * the current alert after a successful repository operation.
 *
 * These tests validate the [snoozeAlertResult] contract used by both the
 * notification Snooze button and the voice Snooze command: dismiss only on success.
 */
class ClockAlertSnoozeRegressionTest {

    @Test
    fun `successful snooze dismisses the current alert`() {
        var dismissed = false
        snoozeAlertResult(snoozeSuccess = true) { dismissed = true }
        assertEquals(true, dismissed, "Successful snooze must dismiss the current alert")
    }

    @Test
    fun `failed snooze does not dismiss the current alert`() {
        var dismissed = false
        snoozeAlertResult(snoozeSuccess = false) { dismissed = true }
        assertEquals(false, dismissed, "Failed snooze must NOT dismiss the current alert")
    }

    @Test
    fun `dismiss callback is invoked exactly once per successful snooze`() {
        var dismissCount = 0
        snoozeAlertResult(snoozeSuccess = true) { dismissCount++ }
        snoozeAlertResult(snoozeSuccess = false) { dismissCount++ }
        assertEquals(1, dismissCount, "Only the successful snooze should dismiss")
    }

    @Test
    fun `dismiss is not invoked when snooze fails repeatedly`() {
        var dismissCount = 0
        repeat(3) {
            snoozeAlertResult(snoozeSuccess = false) { dismissCount++ }
        }
        assertEquals(0, dismissCount, "No dismiss should occur on repeated failures")
    }
}
