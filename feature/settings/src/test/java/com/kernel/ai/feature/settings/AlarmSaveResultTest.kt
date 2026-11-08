package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.SchedulingResult
import com.kernel.ai.core.memory.clock.SchedulingWarning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure JVM tests for [AlarmSaveResult] conversion and warning message rendering.
 */
class AlarmSaveResultTest {

    @Test
    fun `toAlarmSaveResult maps Success without warnings to STORED with empty warnings`() {
        val sr = SchedulingResult.Success("data")
        val result = sr.toAlarmSaveResult()

        assertEquals(AlarmSaveResult.STORED(emptyList()), result)
    }

    @Test
    fun `toAlarmSaveResult maps Success with full screen warning`() {
        val sr = SchedulingResult.Success("data", warnings = listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE))
        val result = sr.toAlarmSaveResult()

        assertEquals(AlarmSaveResult.STORED(listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE)), result)
    }

    @Test
    fun `toAlarmSaveResult maps Success with boot restore warning`() {
        val sr = SchedulingResult.Success("data", warnings = listOf(SchedulingWarning.BOOT_RESTORE_LIMITED))
        val result = sr.toAlarmSaveResult()

        assertEquals(AlarmSaveResult.STORED(listOf(SchedulingWarning.BOOT_RESTORE_LIMITED)), result)
    }

    @Test
    fun `toAlarmSaveResult maps Success with both warnings`() {
        val sr = SchedulingResult.Success(
            "data",
            warnings = listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE, SchedulingWarning.BOOT_RESTORE_LIMITED),
        )
        val result = sr.toAlarmSaveResult()

        assertEquals(
            AlarmSaveResult.STORED(listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE, SchedulingWarning.BOOT_RESTORE_LIMITED)),
            result,
        )
    }

    @Test
    fun `toAlarmSaveResult maps ExactAlarmBlocked`() {
        val result = SchedulingResult.ExactAlarmBlocked.toAlarmSaveResult()

        assertEquals(AlarmSaveResult.EXACT_ALARM_BLOCKED, result)
    }

    @Test
    fun `toAlarmSaveResult maps NotificationBlocked`() {
        val result = SchedulingResult.NotificationBlocked.toAlarmSaveResult()

        assertEquals(AlarmSaveResult.NOTIFICATION_BLOCKED, result)
    }

    @Test
    fun `toAlarmSaveResult maps SchedulingFailed`() {
        val result = SchedulingResult.SchedulingFailed("db error").toAlarmSaveResult()

        assertEquals(AlarmSaveResult.FAILED("db error"), result)
    }

    // ── toWarningMessage ────────────────────────────────────────────────

    @Test
    fun `toWarningMessage returns null for empty warnings`() {
        assertNull(emptyList<SchedulingWarning>().toWarningMessage(isTimer = false))
    }

    @Test
    fun `toWarningMessage renders full screen warning for alarm`() {
        val msg = listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE).toWarningMessage(isTimer = false)
        assertEquals("Alarm saved. It may appear as a notification instead of opening full-screen.", msg)
    }

    @Test
    fun `toWarningMessage renders full screen warning for timer`() {
        val msg = listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE).toWarningMessage(isTimer = true)
        assertEquals("Timer set. It may appear as a notification instead of opening full-screen.", msg)
    }

    @Test
    fun `toWarningMessage renders boot restore warning for alarm`() {
        val msg = listOf(SchedulingWarning.BOOT_RESTORE_LIMITED).toWarningMessage(isTimer = false)
        assertEquals("Alarm saved. Scheduled events may need to be recreated after a device restart.", msg)
    }

    @Test
    fun `toWarningMessage renders boot restore warning for timer`() {
        val msg = listOf(SchedulingWarning.BOOT_RESTORE_LIMITED).toWarningMessage(isTimer = true)
        assertEquals("Timer set. Scheduled events may need to be recreated after a device restart.", msg)
    }

    @Test
    fun `toWarningMessage renders both warnings for alarm`() {
        val msg = listOf(
            SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE,
            SchedulingWarning.BOOT_RESTORE_LIMITED,
        ).toWarningMessage(isTimer = false)

        assertEquals(
            "Alarm saved. It may appear as a notification instead of opening full-screen. Scheduled events may need to be recreated after a device restart.",
            msg,
        )
    }

    @Test
    fun `toWarningMessage renders both warnings for timer`() {
        val msg = listOf(
            SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE,
            SchedulingWarning.BOOT_RESTORE_LIMITED,
        ).toWarningMessage(isTimer = true)

        assertEquals(
            "Timer set. It may appear as a notification instead of opening full-screen. Scheduled events may need to be recreated after a device restart.",
            msg,
        )
    }
}
