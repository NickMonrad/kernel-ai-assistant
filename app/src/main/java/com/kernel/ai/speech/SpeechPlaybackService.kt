package com.kernel.ai.speech

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.os.IBinder
import android.util.Log
import com.kernel.ai.core.inference.ForegroundServiceLifecycle

/**
 * Start and stop gate for [SpeechPlaybackService]. A stop requested before the service has called
 * startForeground() is deferred until promotion, so the service never misses its foreground
 * deadline (ForegroundServiceDidNotStartInTimeException, #1636).
 */
internal class SpeechPlaybackForeground(
    private val lifecycle: ForegroundServiceLifecycle = ForegroundServiceLifecycle(),
) {
    @Volatile private var token: MediaSession.Token? = null

    /** Returns false when the OS denies a background start; the caller then posts the Stop card itself. */
    fun start(context: Context, token: MediaSession.Token): Boolean = try {
        this.token = token
        lifecycle.start {
            context.startForegroundService(Intent(context, SpeechPlaybackService::class.java))
        }
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(LOG_TAG, "Speech foreground service start denied", e)
        false
    }

    fun stop(context: Context) {
        lifecycle.stop { context.stopService(Intent(context, SpeechPlaybackService::class.java)) }
    }

    /** Called from the service once startForeground() has run; stops the service if a stop was deferred. */
    fun promoted(stopService: () -> Unit): Boolean = lifecycle.promoted(stopService)

    /** Newest token given to [start]; read here at promotion because a start during pending promotion is not re-sent. */
    fun promotionToken(): MediaSession.Token? = token

    private companion object {
        const val LOG_TAG = "SpeechStopSession"
    }
}

/**
 * Keeps local speech foreground-scheduled while the screen is off (#1959).
 *
 * This service owns the speech Stop card while it runs: [onStartCommand] promotes it with the
 * MediaStyle notification bound to the newest [MediaSession.Token] given to [SpeechPlaybackForeground.start]. The
 * manager posts the card itself only when the OS denies the foreground start.
 */
class SpeechPlaybackService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            SpeechStopNotification.NOTIFICATION_ID,
            SpeechStopNotification.build(this, foreground.promotionToken()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        foreground.promoted { stopSelfResult(startId) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val foreground = SpeechPlaybackForeground()

        /** Returns false when the OS denied the start. */
        fun start(context: Context, token: MediaSession.Token): Boolean = foreground.start(context, token)

        fun stop(context: Context) = foreground.stop(context)
    }
}
