package com.kernel.ai.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidClockStopwatchNotificationSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClockStopwatchNotificationTransport {
    private val syncer = ClockStopwatchNotificationSyncer(this)

    fun sync(stopwatch: ClockStopwatch) {
        syncer.sync(stopwatch)
    }

    override fun show(stopwatch: ClockStopwatch) {
        ensureChannel()
        NotificationManagerCompat.from(context).notify(
            ClockAlertContract.STOPWATCH_NOTIFICATION_ID,
            buildStopwatchNotification(stopwatch),
        )
    }

    override fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun buildStopwatchNotification(stopwatch: ClockStopwatch): android.app.Notification {
        val nowWallClockMs = System.currentTimeMillis()
        val nowElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
        val elapsedMs = stopwatch.elapsedMs(nowElapsedRealtimeMs, nowWallClockMs)
        val contentText = stopwatchNotificationText(stopwatch, elapsedMs)
        return NotificationCompat.Builder(context, ClockAlertContract.ACTIVE_STOPWATCH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(stopwatchNotificationTitle(stopwatch))
            .apply { if (contentText != null) setContentText(contentText) }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(stopwatch.status == StopwatchStatus.RUNNING)
            .setWhen(nowWallClockMs - elapsedMs)
            .setUsesChronometer(stopwatch.status == StopwatchStatus.RUNNING)
            .setChronometerCountDown(false)
            .setContentIntent(buildOpenAppPendingIntent())
            .apply {
                when (stopwatch.status) {
                    StopwatchStatus.RUNNING -> {
                        addAction(
                            android.R.drawable.ic_menu_recent_history,
                            "Lap",
                            buildBroadcastPendingIntent(ClockAlertContract.ACTION_LAP_STOPWATCH),
                        )
                        addAction(
                            android.R.drawable.ic_media_pause,
                            "Pause",
                            buildBroadcastPendingIntent(ClockAlertContract.ACTION_PAUSE_STOPWATCH),
                        )
                        addAction(
                            android.R.drawable.ic_menu_revert,
                            "Reset",
                            buildBroadcastPendingIntent(ClockAlertContract.ACTION_RESET_STOPWATCH),
                        )
                    }

                    StopwatchStatus.PAUSED -> {
                        addAction(
                            android.R.drawable.ic_media_play,
                            "Resume",
                            buildBroadcastPendingIntent(ClockAlertContract.ACTION_RESUME_STOPWATCH),
                        )
                        addAction(
                            android.R.drawable.ic_menu_revert,
                            "Reset",
                            buildBroadcastPendingIntent(ClockAlertContract.ACTION_RESET_STOPWATCH),
                        )
                    }

                    StopwatchStatus.IDLE -> Unit
                }
            }
            .build()
    }

    private fun formatStopwatchElapsed(elapsedMs: Long): String {
        val safeElapsedMs = elapsedMs.coerceAtLeast(0L)
        val totalSeconds = safeElapsedMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun buildOpenAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildBroadcastPendingIntent(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, ClockStopwatchActionReceiver::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(ClockAlertContract.ACTIVE_STOPWATCH_CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ClockAlertContract.ACTIVE_STOPWATCH_CHANNEL_ID,
                "Stopwatch",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing stopwatch notifications and controls"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}

internal fun stopwatchNotificationTitle(stopwatch: ClockStopwatch): String = when (stopwatch.status) {
    StopwatchStatus.RUNNING -> stopwatch.laps.firstOrNull()?.let { "Stopwatch · Lap ${it.lapNumber}" } ?: "Stopwatch"
    StopwatchStatus.PAUSED -> "Stopwatch paused"
    StopwatchStatus.IDLE -> "Stopwatch"
}

internal fun stopwatchNotificationText(stopwatch: ClockStopwatch, elapsedMs: Long): String? = when (stopwatch.status) {
    StopwatchStatus.RUNNING -> null
    StopwatchStatus.PAUSED -> buildString {
        append("Paused at ${formatStopwatchElapsedForNotification(elapsedMs)}")
        if (stopwatch.laps.isNotEmpty()) append(" · ${stopwatch.laps.size} ${if (stopwatch.laps.size == 1) "lap" else "laps"}")
    }
    StopwatchStatus.IDLE -> "Ready"
}

private fun formatStopwatchElapsedForNotification(elapsedMs: Long): String {
    val safeElapsedMs = elapsedMs.coerceAtLeast(0L)
    val totalSeconds = safeElapsedMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}