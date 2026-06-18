package com.kernel.ai.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [configuredSnoozeDurationMs] — the fallback resolver that
 * validates a configured snooze duration and falls back to the hardcoded
 * default when the value is invalid or zero.
 *
 * This function is used by [ClockAlertService.snoozeDurationFor] to resolve
 * explicit snooze duration (notification and voice) without racing the
 * async DataStore collector.
 */
class ClockAlertSnoozeDurationTest {

    @Test
    fun `valid positive configured duration is returned as-is`() {
        assertEquals(300_000L, configuredSnoozeDurationMs(300_000L))
        assertEquals(900_000L, configuredSnoozeDurationMs(900_000L))
        assertEquals(600_000L, configuredSnoozeDurationMs(600_000L))
        assertEquals(1_800_000L, configuredSnoozeDurationMs(1_800_000L))
    }

    @Test
    fun `zero configured duration falls back to default`() {
        assertEquals(600_000L, configuredSnoozeDurationMs(0L))
    }

    @Test
    fun `negative configured duration falls back to default`() {
        assertEquals(600_000L, configuredSnoozeDurationMs(-1L))
        assertEquals(600_000L, configuredSnoozeDurationMs(-1000L))
    }

    @Test
    fun `custom fallback is used when configured value is invalid`() {
        assertEquals(300_000L, configuredSnoozeDurationMs(0L, fallbackMs = 300_000L))
        assertEquals(120_000L, configuredSnoozeDurationMs(-1L, fallbackMs = 120_000L))
    }

    @Test
    fun `custom fallback is ignored when configured value is valid`() {
        assertEquals(900_000L, configuredSnoozeDurationMs(900_000L, fallbackMs = 300_000L))
    }
}
