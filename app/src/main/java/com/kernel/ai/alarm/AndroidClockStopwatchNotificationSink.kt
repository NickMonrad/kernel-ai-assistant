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
        val latestLap = stopwatch.laps.firstOrNull()
        val contentText = when (stopwatch.status) {
            StopwatchStatus.RUNNING -> latestLap?.let {
                "Lap ${it.lapNumber} · split ${formatStopwatchElapsed(it.splitMs)}"
            } ?: "Tap Lap to capture a split"
            StopwatchStatus.PAUSED -> buildString {
                append("Paused at ${formatStopwatchElapsed(elapsedMs)}")
                if (stopwatch.laps.isNotEmpty()) append(" · ${stopwatch.laps.size} laps")
            }
            StopwatchStatus.IDLE -> "Ready"
        }
        return NotificationCompat.Builder(context, ClockAlertContract.ACTIVE_STOPWATCH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Stopwatch")
            .setContentText(contentText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(stopwatch.status == StopwatchStatus.RUNNING)
            .setWhen(nowWallClockMs - elapsedMs)
            .setUsesChronometer(stopwatch.status == StopwatchStatus.RUNNING)
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
