package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType

internal object ClockAlertContract {
    const val EXTRA_LABEL = "alarm_label"
    const val EXTRA_OWNER_ID = "alarm_id"
    const val EXTRA_TITLE = "alarm_title"
    const val EXTRA_EVENT_TYPE = "clock_event_type"
    const val EXTRA_TIMER_ID = "timer_id"

    const val ACTION_TRIGGER_ALERT = "com.kernel.ai.alarm.action.TRIGGER_ALERT"
    const val ACTION_STOP_ALERT = "com.kernel.ai.alarm.action.STOP_ALERT"
    const val ACTION_CANCEL_TIMER = "com.kernel.ai.alarm.action.CANCEL_TIMER"

    const val ALERT_CHANNEL_ID = "kernel_clock_alerts"
    const val ACTIVE_TIMER_CHANNEL_ID = "kernel_clock_timers"
    const val ALERT_NOTIFICATION_ID = 9_300

    fun timerNotificationId(timerId: String): Int = 20_000 + timerId.hashCode()
}

internal data class TriggeredClockAlert(
    val ownerId: String,
    val type: ClockEventType,
    val title: String,
    val label: String,
)
