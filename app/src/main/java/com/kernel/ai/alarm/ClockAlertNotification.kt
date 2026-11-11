package com.kernel.ai.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.net.Uri
import com.kernel.ai.core.memory.clock.ClockEventType

internal object ClockAlertNotification {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ClockAlertContract.ALERT_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
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

    fun build(
        context: Context,
        alert: TriggeredClockAlert,
        mediaSessionToken: MediaSession.Token,
        title: String = alert.title,
        contentText: String = alert.label,
    ): Notification {
        val notification = Notification.Builder(context, ClockAlertContract.ALERT_CHANNEL_ID)
            .setSmallIcon(com.kernel.ai.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(contentText)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Android 14+ permits swiping ordinary ongoing FGS notifications. The active media
            // session and MediaStyle identify this actual alarm playback as a media notification.
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            // #1796 seam: this PendingIntent remains body-tap only; do not set a full-screen intent
            // or add USE_FULL_SCREEN_INTENT in this release.
            .setContentIntent(ringingActivityPendingIntent(context, alert))

            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(serviceActionPendingIntent(context, alert, ClockAlertContract.ACTION_STOP_ALERT))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    serviceActionPendingIntent(context, alert, ClockAlertContract.ACTION_STOP_ALERT),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_lock_idle_alarm),
                    "Snooze",
                    serviceActionPendingIntent(context, alert, ClockAlertContract.ACTION_SNOOZE_ALERT),
                ).build(),
            )
            .build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        return notification
    }
}

internal fun ringingActivityPendingIntent(context: Context, alert: TriggeredClockAlert): PendingIntent =
    PendingIntent.getActivity(
        context,
        alert.ownerId.hashCode(),
        ringingActivityIntent(context, alert),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )



internal fun ringingActivityIntent(context: Context, alert: TriggeredClockAlert): Intent =
    Intent(context, RingingActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putAlertExtras(alert)
    }

internal fun ringingActionIntent(
    context: Context,
    action: String,
    alert: TriggeredClockAlert,
): Intent = Intent(context, ClockAlertService::class.java).apply {
    this.action = action
    data = Uri.parse(alertPendingIntentIdentity(action, alert))
    putAlertExtras(alert)
}

internal fun launchRingingActivityIfForeground(
    context: Context,
    alert: TriggeredClockAlert,
    isMainActivityVisible: Boolean,
): Boolean {
    if (!shouldShowRingingActivity(isMainActivityVisible)) return false
    context.startActivity(ringingActivityIntent(context, alert))
    return true
}
private fun serviceActionPendingIntent(
    context: Context,
    alert: TriggeredClockAlert,
    action: String,
): PendingIntent = PendingIntent.getService(
    context,
    alert.ownerId.hashCode(),
    ringingActionIntent(context, action, alert),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

internal fun Intent.putAlertExtras(alert: TriggeredClockAlert) {
    putExtra(ClockAlertContract.EXTRA_OWNER_ID, alert.ownerId)
    putExtra(ClockAlertContract.EXTRA_LABEL, alert.label)
    putExtra(ClockAlertContract.EXTRA_TITLE, alert.title)
    putExtra(ClockAlertContract.EXTRA_EVENT_TYPE, alert.type.name)
    putExtra(ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS, alert.occurrenceTriggerAtMillis ?: -1L)
    putExtra(ClockAlertContract.EXTRA_SOUND_URI, alert.soundUri)
    putExtra(ClockAlertContract.EXTRA_AUTO_SNOOZE_COUNT, alert.autoSnoozeCount)
}

internal fun Intent.toTriggeredClockAlert(): TriggeredClockAlert? {
    val ownerId = getStringExtra(ClockAlertContract.EXTRA_OWNER_ID) ?: return null
    val title = getStringExtra(ClockAlertContract.EXTRA_TITLE) ?: return null
    val label = getStringExtra(ClockAlertContract.EXTRA_LABEL) ?: return null
    val eventType = getStringExtra(ClockAlertContract.EXTRA_EVENT_TYPE)
    val type = if (eventType == null) {
        ClockEventType.ALARM
    } else {
        runCatching { ClockEventType.valueOf(eventType) }.getOrNull() ?: return null
    }
    return TriggeredClockAlert(
        ownerId = ownerId,
        type = type,
        title = title,
        label = label,
        occurrenceTriggerAtMillis = getLongExtra(
            ClockAlertContract.EXTRA_OCCURRENCE_TRIGGER_AT_MILLIS,
            -1L,
        ).takeIf { it > 0L },
        soundUri = getStringExtra(ClockAlertContract.EXTRA_SOUND_URI),
        autoSnoozeCount = getIntExtra(ClockAlertContract.EXTRA_AUTO_SNOOZE_COUNT, 0),
    )
}

internal fun shouldShowRingingActivity(isMainActivityVisible: Boolean): Boolean = isMainActivityVisible
