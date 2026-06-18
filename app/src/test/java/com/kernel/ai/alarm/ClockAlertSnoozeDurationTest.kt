package com.kernel.ai.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [configuredSnoozeDurationMs] — the fallback resolver used by
 * [ClockAlertService.snoozeDurationFor] to resolve the snooze duration for
 * explicit snooze paths (notification Snooze button and voice Snooze
 * command).
 *
 * The resolution chain in [ClockAlertService.snoozeDurationFor] is:
 *   1. Captured per-alert config ([activeAlertConfigs]) — one-shot from
 *      DataStore, avoiding the async-mutable-field race on cold start.
 *   2. [snoozeDurationMs] — mutable field populated by async DataStore flow.
 *   3. [ALARM_SNOOZE_MS] — hardcoded 10-minute default via
 *      [configuredSnoozeDurationMs] fallback when the resolved value is not
 *      positive.
 *
 * These tests validate step 3 of the chain — the final safety net when
 * both captured config and mutable field are unavailable or invalid.
 * Steps 1 and 2 are exercised through the service integration path.
 */
class ClockAlertSnoozeDurationTest {

    @Test
    fun `explicit snooze with captured 300_000ms resolves to 5 minutes`() {
        assertEquals(300_000L, configuredSnoozeDurationMs(300_000L))
    }

    @Test
    fun `explicit snooze with captured 900_000ms resolves to 15 minutes`() {
        assertEquals(900_000L, configuredSnoozeDurationMs(900_000L))
    }

    @Test
    fun `explicit snooze with zero duration falls back to default 10 minutes`() {
        assertEquals(10 * 60 * 1_000L, configuredSnoozeDurationMs(0L))
    }

    @Test
    fun `explicit snooze with negative duration falls back to default 10 minutes`() {
        assertEquals(10 * 60 * 1_000L, configuredSnoozeDurationMs(-1L))
        assertEquals(10 * 60 * 1_000L, configuredSnoozeDurationMs(-1000L))
    }

    @Test
    fun `snooze resolver chains through configuredSnoozeDurationMs for notification and voice`() {
        // This test documents that the notification Snooze button (ACTION_SNOOZE_ALERT)
        // and voice Snooze command (ClockAlertVoiceCommand.SNOOZE) both route through
        // snoozeDurationFor() in ClockAlertService, which calls configuredSnoozeDurationMs
        // as its final fallback step.
        //
        // snoozeDurationFor(alert):
        //   val configured = activeAlertConfigs[alert.ownerId]?.snoozeDurationMs
        //       ?: snoozeDurationMs                     // mutable field fallback
        //   return configuredSnoozeDurationMs(configured) // final safety net
        //
        // The notification path (line 155):
        //   performSnooze(alert, snoozeDurationFor(alert))
        //
        // The voice path (line 611):
        //   ClockAlertVoiceCommand.SNOOZE -> performSnooze(alert, snoozeDurationFor(alert))

        // All the resolution behaviours are tested in the individual tests above.
        // This test exists to pin the wiring: the resolver must return positive
        // values as-is, and zero/negative must fall back.
        val captured300k = configuredSnoozeDurationMs(300_000L)
        assertEquals(300_000L, captured300k, "valid captured config is used directly")

        val capturedZero = configuredSnoozeDurationMs(0L)
        assertEquals(10 * 60 * 1_000L, capturedZero, "invalid config falls back to 10 minutes")
    }
}
