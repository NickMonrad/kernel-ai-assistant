package com.kernel.ai.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.clock.ClockTimer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class AndroidClockTimerNotificationSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClockTimerNotificationTransport {
    private val syncer = ClockTimerNotificationSyncer(this)

    fun sync(timers: List<ClockTimer>) {
        syncer.sync(timers)
    }

    override fun show(timer: ClockTimer) {
        ensureChannel()
        NotificationManagerCompat.from(context).notify(
            ClockAlertContract.timerNotificationId(timer.id),
            buildTimerNotification(timer),
        )
    }

    override fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun buildTimerNotification(timer: ClockTimer) =
        NotificationCompat.Builder(context, ClockAlertContract.ACTIVE_TIMER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(timer.label ?: "Timer")
            .setContentText("${formatDuration(timer.durationMs)} remaining")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(timer.triggerAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(buildOpenAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                buildCancelTimerPendingIntent(timer.id),
            )
            .build()

    private fun buildOpenAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildCancelTimerPendingIntent(timerId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            timerId.hashCode(),
            Intent(context, ClockTimerActionReceiver::class.java).apply {
                action = ClockAlertContract.ACTION_CANCEL_TIMER
                putExtra(ClockAlertContract.EXTRA_TIMER_ID, timerId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(ClockAlertContract.ACTIVE_TIMER_CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ClockAlertContract.ACTIVE_TIMER_CHANNEL_ID,
                "Timers",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing countdown notifications for active timers"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = max(1L, durationMs / 1_000L)
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (hours > 0) add("$hours hour${if (hours == 1L) "" else "s"}")
            if (minutes > 0) add("$minutes minute${if (minutes == 1L) "" else "s"}")
            if (seconds > 0 && hours == 0L) add("$seconds second${if (seconds == 1L) "" else "s"}")
            if (isEmpty()) add("0 seconds")
        }.joinToString(" ")
    }
}
