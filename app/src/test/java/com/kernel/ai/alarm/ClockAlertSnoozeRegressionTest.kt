package com.kernel.ai.alarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
 * that owns both the snooze call and the dismiss decision. The helper receives
 * the real suspend snooze operation, not a precomputed Boolean.
 */
class ClockAlertSnoozeRegressionTest {

    @Test
    fun `snooze operation executes exactly once`() = runTest {
        var snoozeCount = 0
        runSnoozeAction(
            snooze = { snoozeCount++; true },
            dismiss = {},
        )
        assertEquals(1, snoozeCount, "Snooze operation must execute exactly once")
    }

    @Test
    fun `successful snooze invokes dismissal exactly once`() = runTest {
        var dismissCount = 0
        runSnoozeAction(
            snooze = { true },
            dismiss = { dismissCount++ },
        )
        assertEquals(1, dismissCount, "Successful snooze must invoke dismissal exactly once")
    }

    @Test
    fun `failed snooze does not invoke dismissal`() = runTest {
        var dismissed = false
        runSnoozeAction(
            snooze = { false },
            dismiss = { dismissed = true },
        )
        assertEquals(false, dismissed, "Failed snooze must not invoke dismissal")
    }

    @Test
    fun `repeated failures never invoke dismissal`() = runTest {
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
    fun `successful execution does not invoke snooze more than once`() = runTest {
        var snoozeCount = 0
        runSnoozeAction(
            snooze = { snoozeCount++; true },
            dismiss = {},
        )
        assertEquals(1, snoozeCount, "Snooze operation must not be called more than once")
    }

    @Test
    fun `snooze completing after replacement preserves the new voice owner`() = runTest {
        val repositoryResult = CompletableDeferred<Boolean>()
        var currentOwner = "A"
        var stoppedOwner: String? = null
        var preserved = 0
        val action = async {
            runSnoozeAction(
                snooze = { repositoryResult.await() },
                dismiss = { stoppedOwner = currentOwner },
                shouldPreserveVoiceSession = { currentOwner != "A" },
                dismissPreservingVoiceSession = { preserved++ },
            )
        }

        currentOwner = "B"
        repositoryResult.complete(true)
        action.await()

        assertEquals(null, stoppedOwner)
        assertEquals(1, preserved)
    }

    @Test
    fun `auto snooze completing after replacement dismisses only the old alert`() = runTest {
        val repositoryResult = CompletableDeferred<Boolean>()
        var currentOwner = "A"
        var dismissedWithPreserve = false
        val action = async {
            runAutoSnoozeAction(
                snooze = { repositoryResult.await() },
                dismiss = { preserve -> dismissedWithPreserve = preserve },
                shouldPreserveVoiceSession = { currentOwner != "A" },
            )
        }

        currentOwner = "B"
        repositoryResult.complete(false)

        assertEquals(false, action.await())
        assertEquals(true, dismissedWithPreserve)
    }
}
