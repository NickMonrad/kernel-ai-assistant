package com.kernel.ai.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kernel.ai.core.voice.SpeechStopCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "KernelAI"

/**
 * Exposes Stop controls only while Jandal is speaking (#1873).
 *
 * While [SpeechStopCoordinator.isSpeaking] is true this opens a [MediaSession] that routes
 * volume-key presses to the coordinator through a remote [VolumeProvider], and posts an ongoing
 * media-style notification with a Stop action. Both are torn down when speech ends, so volume
 * keys behave normally otherwise.
 */
@Singleton
class SpeechStopSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: SpeechStopCoordinator,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null

    fun start(scope: CoroutineScope): Job {
        coordinator.observe(scope)
        return scope.launch(Dispatchers.Main) {
            coordinator.isSpeaking.collect { speaking ->
                if (speaking) open() else close()
            }
        }
    }

    private fun open() {
        if (session != null) return
        val newSession = MediaSession(context, SESSION_TAG).apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(0L)
                    .build(),
            )
            setPlaybackToRemote(
                object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, VOLUME_MAX, VOLUME_START) {
                    override fun onAdjustVolume(direction: Int) {
                        // Any volume-key press while speaking stops speech. Hop to main so the
                        // stop runs on the same thread as the rest of the voice UI state.
                        mainHandler.post { coordinator.requestStop() }
                    }
                },
            )
            isActive = true
        }
        session = newSession
        postNotification(newSession)
    }

    private fun close() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun postNotification(mediaSession: MediaSession) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Speech playback", NotificationManager.IMPORTANCE_LOW),
        )
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Volume keys still work without the notification; log so the gap is visible.
            Log.i(TAG, "SpeechStop: POST_NOTIFICATIONS not granted; Stop action not shown")
            return
        }
        val stopIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SpeechStopReceiver::class.java).setAction(ACTION_STOP_SPEECH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Jandal is speaking")
            .setContentText("Press a volume key or tap Stop to interrupt")
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val SESSION_TAG = "JandalSpeech"
        const val CHANNEL_ID = "speech_playback"
        const val NOTIFICATION_ID = 1873
        const val VOLUME_MAX = 100
        const val VOLUME_START = 50
    }
}

/** Action used by the speaking notification's Stop button. Not exported. */
const val ACTION_STOP_SPEECH = "com.kernel.ai.STOP_SPEECH"
