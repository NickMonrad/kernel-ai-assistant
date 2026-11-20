package com.kernel.ai.speech

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kernel.ai.R
import com.kernel.ai.core.voice.SpeechStopCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Exposes system media Stop controls only while Jandal is speaking (#1873).
 *
 * While [SpeechStopCoordinator.isSpeaking] is true a [MediaSession] is active. Its PlaybackState
 * advertises ACTION_STOP plus a "Stop" custom action, and its callbacks route Stop, Pause and the
 * custom action to [SpeechStopCoordinator.requestStop]. A MediaStyle notification bound to the
 * session lets System UI draw the media card in the shade (verified on S23U). Lock-screen display is
 * not verified: the S21 lock-screen check failed (PR #1913). Its Stop routes through the same session callbacks, so no receiver is needed. The notification and session are
 * released when speech ends.
 */
@Singleton
class SpeechStopSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: SpeechStopCoordinator,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var cardPostedWithoutService = false
    private val lifecycle = SpeechStopSessionLifecycle(open = ::open, close = ::close)

    fun start(scope: CoroutineScope): Job {
        coordinator.observe(scope)
        return scope.launch(Dispatchers.Main) {
            coordinator.isSpeaking.collect { speaking -> lifecycle.onSpeakingChanged(speaking) }
        }
    }

    private fun open() {
        val mediaSession = MediaSession(context, SESSION_TAG).apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onStop() {
                        coordinator.requestStop()
                    }

                    override fun onPause() {
                        coordinator.requestStop()
                    }

                    override fun onCustomAction(action: String, extras: Bundle?) {
                        if (action == ACTION_STOP_CUSTOM) coordinator.requestStop()
                    }
                },
                mainHandler,
            )
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, context.getString(R.string.speech_stop_title))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, context.getString(R.string.app_name))
                    .build(),
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
                    .addCustomAction(
                        PlaybackState.CustomAction.Builder(
                            ACTION_STOP_CUSTOM,
                            context.getString(R.string.speech_stop_action),
                            R.drawable.ic_speech_stop,
                        ).build(),
                    )
                    .build(),
            )
            isActive = true
        }
        session = mediaSession
        val token = mediaSession.sessionToken
        cardPostedWithoutService = !startPlaybackService(token)
        if (cardPostedWithoutService) postCard(token)
    }

    private fun close() {
        session?.run {
            isActive = false
            release()
        }
        session = null
        context.stopService(Intent(context, SpeechPlaybackService::class.java))
        if (cardPostedWithoutService) {
            cardPostedWithoutService = false
            context.getSystemService(NotificationManager::class.java).cancel(SpeechStopNotification.NOTIFICATION_ID)
        }
    }

    /** Returns false when the OS denied the foreground start; the Stop card is then posted directly. */
    private fun startPlaybackService(token: MediaSession.Token): Boolean = try {
        context.startForegroundService(
            Intent(context, SpeechPlaybackService::class.java).apply {
                putExtra(SpeechPlaybackService.EXTRA_SESSION_TOKEN, token)
            },
        )
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(LOG_TAG, "Speech foreground service start denied", e)
        false
    }

    private fun postCard(token: MediaSession.Token) {
        context.getSystemService(NotificationManager::class.java)
            .notify(SpeechStopNotification.NOTIFICATION_ID, SpeechStopNotification.build(context, token))
    }

    private companion object {
        const val SESSION_TAG = "JandalSpeech"
        const val ACTION_STOP_CUSTOM = "com.kernel.ai.speech.STOP_SPEECH"
        const val LOG_TAG = "SpeechStopSession"
    }
}

/** Opens the session once when speech starts and closes it once when speech stops. */
internal class SpeechStopSessionLifecycle(
    private val open: () -> Unit,
    private val close: () -> Unit,
) {
    private var isOpen = false

    fun onSpeakingChanged(speaking: Boolean) {
        if (speaking == isOpen) return
        isOpen = speaking
        if (speaking) open() else close()
    }
}
