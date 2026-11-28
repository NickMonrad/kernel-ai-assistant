package com.kernel.ai.alarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kernel.ai.core.memory.clock.ClockEventType

internal object ClockAlertContract {
    const val EXTRA_LABEL = "alarm_label"
    const val EXTRA_OWNER_ID = "alarm_id"
    const val EXTRA_TITLE = "alarm_title"
    const val EXTRA_EVENT_TYPE = "clock_event_type"
    const val EXTRA_TIMER_ID = "timer_id"
    const val EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS = "occurrence_trigger_at_millis"
    const val EXTRA_SOUND_URI = "sound_uri"
    const val EXTRA_AUTO_SNOOZE_COUNT = "auto_snooze_count"
    const val ACTION_TRIGGER_ALERT = "com.kernel.ai.alarm.action.TRIGGER_ALERT"
    const val ACTION_STOP_ALERT = "com.kernel.ai.alarm.action.STOP_ALERT"
    const val ACTION_STOP_TIMER_ALERTS = "com.kernel.ai.alarm.action.STOP_TIMER_ALERTS"
    const val ACTION_SNOOZE_ALERT = "com.kernel.ai.alarm.action.SNOOZE_ALERT"
    const val ACTION_ADD_MINUTE_ALERT = "com.kernel.ai.alarm.action.ADD_MINUTE_ALERT"
    const val ACTION_START_VOICE_CONTROL = "com.kernel.ai.alarm.action.START_VOICE_CONTROL"
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
    val soundUri: String? = null,
    val autoSnoozeCount: Int = 0,
)

/** Reads the alert back from an intent written by [putTriggeredClockAlertExtras]. */
internal fun Intent.toTriggeredClockAlert(): TriggeredClockAlert? {
    val ownerId = getStringExtra(ClockAlertContract.EXTRA_OWNER_ID) ?: return null
    val label = getStringExtra(ClockAlertContract.EXTRA_LABEL) ?: return null
    val title = getStringExtra(ClockAlertContract.EXTRA_TITLE) ?: return null
    val type = getStringExtra(ClockAlertContract.EXTRA_EVENT_TYPE)
        ?.let(ClockEventType::valueOf)
        ?: ClockEventType.ALARM
    val occurrenceTriggerAtMillis = getLongExtra(
        ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
        -1L,
    ).takeIf { it > 0L }
    val soundUri = getStringExtra(ClockAlertContract.EXTRA_SOUND_URI)
    val autoSnoozeCount = getIntExtra(
        ClockAlertContract.EXTRA_AUTO_SNOOZE_COUNT,
        0,
    )
    return TriggeredClockAlert(
        ownerId = ownerId,
        label = label,
        title = title,
        type = type,
        occurrenceTriggerAtMillis = occurrenceTriggerAtMillis,
        soundUri = soundUri,
        autoSnoozeCount = autoSnoozeCount,
    )
}

internal fun Intent.putTriggeredClockAlertExtras(alert: TriggeredClockAlert): Intent = apply {
    putExtra(ClockAlertContract.EXTRA_OWNER_ID, alert.ownerId)
    putExtra(ClockAlertContract.EXTRA_LABEL, alert.label)
    putExtra(ClockAlertContract.EXTRA_TITLE, alert.title)
    putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, alert.type.name)
    putExtra(
        ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
        alert.occurrenceTriggerAtMillis ?: -1L,
    )
    putExtra(ClockAlertContract.EXTRA_SOUND_URI, alert.soundUri)
}

/** Intent for a button action on the ringing alert, handled by [ClockAlertService]. */
internal fun clockAlertServiceIntent(context: Context, action: String, alert: TriggeredClockAlert): Intent =
    Intent(context, ClockAlertService::class.java).apply {
        this.action = action
        data = Uri.parse(alertPendingIntentIdentity(action, alert))
        putTriggeredClockAlertExtras(alert)
    }
