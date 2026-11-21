package com.kernel.ai.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaSession
import com.kernel.ai.R

/**
 * The one speech Stop card (#1873). Both the foreground [SpeechPlaybackService] and the
 * denied-start fallback in [SpeechStopSessionManager] build it here, so notification ID 1873 is
 * never replaced by a plain notification.
 */
internal object SpeechStopNotification {
    const val CHANNEL_ID = "speech_playback"
    const val NOTIFICATION_ID = 1873

    fun build(context: Context, token: MediaSession.Token?): Notification {
        ensureChannel(context)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speech_stop)
            .setContentTitle(context.getString(R.string.speech_stop_title))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
        if (token != null) builder.setStyle(Notification.MediaStyle().setMediaSession(token))
        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.speech_stop_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
