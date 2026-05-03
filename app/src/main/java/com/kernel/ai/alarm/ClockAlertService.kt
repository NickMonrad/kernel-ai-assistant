package com.kernel.ai.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.clock.ClockEventType

class ClockAlertService : Service() {
    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private val vibratorManager: VibratorManager
        get() = getSystemService(VibratorManager::class.java)

    private val activeAlerts = linkedSetOf<TriggeredClockAlert>()
    private var ringtone: Ringtone? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ClockAlertContract.ACTION_TRIGGER_ALERT -> {
                val alert = intent.toTriggeredClockAlert() ?: return START_NOT_STICKY
                activeAlerts.removeAll { it.ownerId == alert.ownerId }
                activeAlerts += alert
                ensureChannel()
                startForeground(
                    ClockAlertContract.ALERT_NOTIFICATION_ID,
                    buildNotification(alert),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                startAlertPlayback()
            }

            ClockAlertContract.ACTION_STOP_ALERT -> stopAlertSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun buildNotification(alert: TriggeredClockAlert) =
        NotificationCompat.Builder(this, ClockAlertContract.ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(
                if (activeAlerts.size > 1) "${activeAlerts.size} active alerts"
                else alert.title,
            )
            .setContentText(
                if (activeAlerts.size > 1) "${alert.label} (+${activeAlerts.size - 1} more)"
                else alert.label,
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(buildOpenAppPendingIntent())
            .setDeleteIntent(buildStopPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (alert.type == ClockEventType.TIMER) "Dismiss" else "Stop",
                buildStopPendingIntent(),
            )
            .apply {
                if (notificationManager.canUseFullScreenIntent()) {
                    setFullScreenIntent(buildOpenAppPendingIntent(), true)
                }
            }
            .build()

    private fun startAlertPlayback() {
        stopPlayback()
        startVibration()
        ringtone = RingtoneManager.getRingtone(
            this,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            isLooping = true
            play()
        }
    }

    private fun startVibration() {
        defaultVibrator()?.cancel()
        defaultVibrator()?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500), 0),
        )
    }

    private fun stopAlertSession() {
        activeAlerts.clear()
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPlayback() {
        ringtone?.stop()
        ringtone = null
        defaultVibrator()?.cancel()
    }

    private fun defaultVibrator(): Vibrator? = vibratorManager.defaultVibrator

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(ClockAlertContract.ALERT_CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ClockAlertContract.ALERT_CHANNEL_ID,
                "Clock alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alarm and timer completion alerts"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun buildOpenAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildStopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            Intent(this, ClockAlertService::class.java).apply {
                action = ClockAlertContract.ACTION_STOP_ALERT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun Intent.toTriggeredClockAlert(): TriggeredClockAlert? {
        val ownerId = getStringExtra(ClockAlertContract.EXTRA_OWNER_ID) ?: return null
        val label = getStringExtra(ClockAlertContract.EXTRA_LABEL) ?: return null
        val title = getStringExtra(ClockAlertContract.EXTRA_TITLE) ?: return null
        val type = getStringExtra(ClockAlertContract.EXTRA_EVENT_TYPE)
            ?.let(ClockEventType::valueOf)
            ?: ClockEventType.ALARM
        return TriggeredClockAlert(ownerId = ownerId, type = type, title = title, label = label)
    }

    companion object {
        internal fun trigger(context: Context, alert: TriggeredClockAlert) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClockAlertService::class.java).apply {
                    action = ClockAlertContract.ACTION_TRIGGER_ALERT
                    putExtra(ClockAlertContract.EXTRA_OWNER_ID, alert.ownerId)
                    putExtra(ClockAlertContract.EXTRA_LABEL, alert.label)
                    putExtra(ClockAlertContract.EXTRA_TITLE, alert.title)
                    putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, alert.type.name)
                },
            )
        }
    }
}
