package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType

internal object ClockAlertContract {
    const val EXTRA_LABEL = "alarm_label"
    const val EXTRA_OWNER_ID = "alarm_id"
    const val EXTRA_TITLE = "alarm_title"
    const val EXTRA_EVENT_TYPE = "clock_event_type"
    const val EXTRA_TIMER_ID = "timer_id"
    const val EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS = "occurrence_trigger_at_millis"

    const val ACTION_TRIGGER_ALERT = "com.kernel.ai.alarm.action.TRIGGER_ALERT"
    const val ACTION_STOP_ALERT = "com.kernel.ai.alarm.action.STOP_ALERT"
    const val ACTION_CANCEL_TIMER = "com.kernel.ai.alarm.action.CANCEL_TIMER"
    const val ACTION_SKIP_ALARM_OCCURRENCE = "com.kernel.ai.alarm.action.SKIP_ALARM_OCCURRENCE"
    const val ACTION_PAUSE_STOPWATCH = "com.kernel.ai.alarm.action.PAUSE_STOPWATCH"
    const val ACTION_RESUME_STOPWATCH = "com.kernel.ai.alarm.action.RESUME_STOPWATCH"
    const val ACTION_LAP_STOPWATCH = "com.kernel.ai.alarm.action.LAP_STOPWATCH"
    const val ACTION_RESET_STOPWATCH = "com.kernel.ai.alarm.action.RESET_STOPWATCH"
    const val ALERT_CHANNEL_ID = "kernel_clock_alerts"
    const val ACTIVE_TIMER_CHANNEL_ID = "kernel_clock_timers"
    const val ACTIVE_STOPWATCH_CHANNEL_ID = "kernel_clock_stopwatch"
    const val PRE_ALARM_CHANNEL_ID = "kernel_clock_pre_alarms"
    const val ALERT_NOTIFICATION_ID = 9_300
    const val STOPWATCH_NOTIFICATION_ID = 40_000

    fun timerNotificationId(timerId: String): Int = 20_000 + timerId.hashCode()
    fun preAlarmNotificationId(ownerId: String): Int = 30_000 + ownerId.hashCode()
}

internal data class TriggeredClockAlert(
    val ownerId: String,
    val type: ClockEventType,
    val title: String,
    val label: String,
    val occurrenceTriggerAtMillis: Long? = null,
 )
