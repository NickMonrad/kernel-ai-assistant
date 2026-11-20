package com.kernel.ai.speech

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.os.IBinder

/**
 * Keeps local speech foreground-scheduled while the screen is off (#1959).
 *
 * This service owns the speech Stop card while it runs: [onStartCommand] promotes it with the
 * MediaStyle notification bound to the [MediaSession.Token] passed in [EXTRA_SESSION_TOKEN]. The
 * manager posts the card itself only when the OS denies the foreground start.
 */
class SpeechPlaybackService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getParcelableExtra(EXTRA_SESSION_TOKEN, MediaSession.Token::class.java)
        startForeground(
            SpeechStopNotification.NOTIFICATION_ID,
            SpeechStopNotification.build(this, token),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SESSION_TOKEN = "com.kernel.ai.speech.SESSION_TOKEN"
    }
}
