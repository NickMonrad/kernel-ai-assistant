package com.kernel.ai.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.memory.clock.ClockRepository
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AlarmBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var clockRepository: ClockRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ClockAlertContract.ACTION_SKIP_ALARM_OCCURRENCE) {
            handleSkipToday(context, intent)
            return
        }

        val label = intent.getStringExtra(ClockAlertContract.EXTRA_LABEL) ?: "Alarm"
        val ownerId = intent.getStringExtra(ClockAlertContract.EXTRA_OWNER_ID) ?: return
        val title = intent.getStringExtra(ClockAlertContract.EXTRA_TITLE) ?: "Alarm"
        val type = intent.getStringExtra(ClockAlertContract.EXTRA_EVENT_TYPE)
            ?.let(ClockEventType::valueOf)
            ?: ClockEventType.ALARM
        val occurrenceTriggerAtMillis = intent.getLongExtra(
            ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
            -1L,
        ).takeIf { it > 0L }

        when (type) {
            ClockEventType.TIMER -> {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(ClockAlertContract.timerNotificationId(ownerId))
                triggerAlert(context, ownerId, type, title, label, occurrenceTriggerAtMillis)
            }

            ClockEventType.ALARM -> {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(ClockAlertContract.preAlarmNotificationId(ownerId))
                triggerAlert(context, ownerId, type, title, label, occurrenceTriggerAtMillis)
            }

            ClockEventType.PRE_ALARM -> {
                showPreAlarmNotification(context, ownerId, label, occurrenceTriggerAtMillis)
            }
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clockRepository.handleScheduledEvent(ownerId, type, occurrenceTriggerAtMillis)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleSkipToday(context: Context, intent: Intent) {
        val ownerId = intent.getStringExtra(ClockAlertContract.EXTRA_OWNER_ID) ?: return
        val occurrenceTriggerAtMillis = intent.getLongExtra(
            ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
            -1L,
        ).takeIf { it > 0L } ?: return
        context.getSystemService(NotificationManager::class.java)
            .cancel(ClockAlertContract.preAlarmNotificationId(ownerId))

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clockRepository.skipAlarmOccurrence(ownerId, occurrenceTriggerAtMillis)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun triggerAlert(
        context: Context,
        ownerId: String,
        type: ClockEventType,
        title: String,
        label: String,
        occurrenceTriggerAtMillis: Long?,
    ) {
        ClockAlertService.trigger(
            context,
            TriggeredClockAlert(
                ownerId = ownerId,
                type = type,
                title = title,
                label = label,
                occurrenceTriggerAtMillis = occurrenceTriggerAtMillis,
            ),
        )
    }

    private fun showPreAlarmNotification(
        context: Context,
        ownerId: String,
        label: String,
        occurrenceTriggerAtMillis: Long?,
    ) {
        val triggerAtMillis = occurrenceTriggerAtMillis ?: return
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensurePreAlarmChannel(notificationManager)
        val formattedTime = DateTimeFormatter.ofPattern("h:mma")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(triggerAtMillis))
        val notification = NotificationCompat.Builder(context, ClockAlertContract.PRE_ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm in 30 minutes")
            .setContentText("${label.takeIf { it.isNotBlank() } ?: "Alarm"} rings at $formattedTime")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppPendingIntent(context))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Skip today",
                buildSkipTodayPendingIntent(context, ownerId, label, triggerAtMillis),
            )
            .build()
        notificationManager.notify(ClockAlertContract.preAlarmNotificationId(ownerId), notification)
    }

    private fun ensurePreAlarmChannel(notificationManager: NotificationManager) {
        if (notificationManager.getNotificationChannel(ClockAlertContract.PRE_ALARM_CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ClockAlertContract.PRE_ALARM_CHANNEL_ID,
                "Alarm reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Upcoming repeating alarm reminders"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun buildOpenAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildSkipTodayPendingIntent(
        context: Context,
        ownerId: String,
        label: String,
        occurrenceTriggerAtMillis: Long,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ownerId.hashCode(),
            Intent(context, AlarmBroadcastReceiver::class.java).apply {
                action = ClockAlertContract.ACTION_SKIP_ALARM_OCCURRENCE
                putExtra(ClockAlertContract.EXTRA_OWNER_ID, ownerId)
                putExtra(ClockAlertContract.EXTRA_LABEL, label)
                putExtra(ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS, occurrenceTriggerAtMillis)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}