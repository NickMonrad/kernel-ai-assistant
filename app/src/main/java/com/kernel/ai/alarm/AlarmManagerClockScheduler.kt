package com.kernel.ai.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.memory.clock.ClockPlatformState
import com.kernel.ai.core.memory.clock.ClockScheduledEvent
import com.kernel.ai.core.memory.clock.ClockScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmManagerClockScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClockScheduler {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    override fun getPlatformState(): ClockPlatformState =
        ClockPlatformState(
            canScheduleExactAlarms = alarmManager.canScheduleExactAlarms(),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            canUseFullScreenIntent = notificationManager.canUseFullScreenIntent(),
        )

    override fun schedule(event: ClockScheduledEvent) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            event.eventId.hashCode(),
            buildBroadcastIntent(event),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            event.triggerAtMillis,
            pendingIntent,
        )
    }

    override fun cancel(event: ClockScheduledEvent) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            event.eventId.hashCode(),
            buildBroadcastIntent(event),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pendingIntent?.let(alarmManager::cancel)
    }

    private fun buildBroadcastIntent(event: ClockScheduledEvent): Intent =
        Intent().apply {
            component = ComponentName(context.packageName, "com.kernel.ai.alarm.AlarmBroadcastReceiver")
            putExtra(ClockAlertContract.EXTRA_LABEL, event.label ?: defaultLabel(event.type))
            putExtra(ClockAlertContract.EXTRA_OWNER_ID, event.ownerId)
            putExtra(ClockAlertContract.EXTRA_TITLE, defaultTitle(event.type))
            putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, event.type.name)
        }

    private fun defaultLabel(type: ClockEventType): String =
        when (type) {
            ClockEventType.ALARM -> "Alarm"
            ClockEventType.TIMER -> "Timer"
            ClockEventType.PRE_ALARM -> "Alarm reminder"
        }

    private fun defaultTitle(type: ClockEventType): String =
        when (type) {
            ClockEventType.TIMER -> "Timer"
            ClockEventType.PRE_ALARM -> "Alarm reminder"
            ClockEventType.ALARM -> "Alarm"
        }
}
