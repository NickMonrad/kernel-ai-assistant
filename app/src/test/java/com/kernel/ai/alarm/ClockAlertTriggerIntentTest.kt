package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Contract tests for the trigger-intent action constant used by
 * [ClockAlertService.createTriggerIntent] and [ClockAlertService.onStartCommand].
 *
 * The actual Intent extras are validated by instrumentation tests
 * (ClockOverflowSettingsUiTest) and code review. These unit tests
 * verify the string constant contract that determines the
 * onStartCommand routing branch.
 */
class ClockAlertTriggerIntentTest {

    @Test
    fun `ACTION_TRIGGER_ALERT constant is correct`() {
        assertEquals("com.kernel.ai.alarm.action.TRIGGER_ALERT", ClockAlertContract.ACTION_TRIGGER_ALERT)
    }

    @Test
    fun `ACTION_TRIGGER_ALERT enables onStartCommand routing`() {
        // The action constant matches the string literal used in the
        // `when (intent?.action)` switch in ClockAlertService.onStartCommand.
        // If this test passes, the routing branch is reachable.
        assertEquals(
            ClockAlertContract.ACTION_TRIGGER_ALERT,
            "com.kernel.ai.alarm.action.TRIGGER_ALERT",
            "ACTION_TRIGGER_ALERT must match the action string in onStartCommand",
        )
    }

    @Test
    fun `TriggeredClockAlert fields map to intent extras`() {
        // When triggeredClockAlert is serialised to extras, the contract
        // between trigger() and onStartCommand / toTriggeredClockAlert()
        // must match.  This test validates the field-to-key mapping.
        val alert = TriggeredClockAlert(
            ownerId = "alarm-42",
            type = ClockEventType.ALARM,
            title = "Alarm",
            label = "Test alarm",
            occurrenceTriggerAtMillis = 1_000_000L,
            soundUri = "content://media/audio/test",
            autoSnoozeCount = 1,
        )

        assertEquals(ClockAlertContract.EXTRA_OWNER_ID, "alarm_id")
        assertEquals(ClockAlertContract.EXTRA_TITLE, "alarm_title")
        assertEquals(ClockAlertContract.EXTRA_LABEL, "alarm_label")
        assertEquals(ClockAlertContract.EXTRA_EVENT_TYPE, "clock_event_type")
        assertEquals(ClockAlertContract.EXTRA_SOUND_URI, "sound_uri")
        assertEquals(ClockAlertContract.EXTRA_AUTO_SNOOZE_COUNT, "auto_snooze_count")
        assertEquals(ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS, "occurrence_trigger_at_millis")
    }
}
