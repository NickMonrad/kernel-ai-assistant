package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.SchedulingResult
import com.kernel.ai.core.memory.clock.SchedulingWarning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ── settingsOffers and full-screen copy ─────────────────────────────

    @Test
    fun `settingsOffers offers only the full-screen page when full-screen alerts are off`() {
        assertEquals(
            listOf(AlarmSettingsOffer.FULL_SCREEN_ALERTS),
            listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE).settingsOffers(),
        )
    }

    @Test
    fun `settingsOffers offers both pages when exact alarms and full-screen alerts are off`() {
        assertEquals(
            listOf(AlarmSettingsOffer.EXACT_ALARMS, AlarmSettingsOffer.FULL_SCREEN_ALERTS),
            listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE, SchedulingWarning.EXACT_ALARM_UNAVAILABLE).settingsOffers(),
        )
    }

    @Test
    fun `settingsOffers is empty when only the boot restore limit applies`() {
        assertEquals(emptyList<AlarmSettingsOffer>(), listOf(SchedulingWarning.BOOT_RESTORE_LIMITED).settingsOffers())
    }

    @Test
    fun `toWarningMessage reports full-screen alerts are off rather than promising full-screen`() {
        val message = listOf(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE).toWarningMessage(isTimer = true)

        assertTrue(message.orEmpty().startsWith("Timer set."))
        assertTrue(message.orEmpty().contains("Full-screen alerts are off"))
    }

}
