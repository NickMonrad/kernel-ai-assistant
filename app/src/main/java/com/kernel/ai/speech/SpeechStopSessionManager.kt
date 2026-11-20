package com.kernel.ai.speech

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * custom action to [SpeechStopCoordinator.requestStop]. The Stop card is a MediaStyle notification
 * bound to that session: System UI draws it in the shade (verified on S23U). Lock-screen display is
 * not verified: the S21 lock-screen check failed (PR #1913).
 *
 * Foreground scheduling (#1959): [open] promotes [SpeechPlaybackService], which posts the card as ID
 * 1873 while it runs. If the OS denies the background start, the manager posts the same card itself
 * so the shade control still exists. Exactly one party posts ID 1873 in either case ([SpeechStopCard]).
 * [close] stops the service, deferred until promotion if the start is still pending. The session and
 * card are released when speech ends.
 */
@Singleton
class SpeechStopSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: SpeechStopCoordinator,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private val card = speechStopCard(context)
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
        card.show(mediaSession.sessionToken)
    }

    private fun close() {
        session?.run {
            isActive = false
            release()
        }
        session = null
        card.hide()
    }

    private companion object {
        const val SESSION_TAG = "JandalSpeech"
        const val ACTION_STOP_CUSTOM = "com.kernel.ai.speech.STOP_SPEECH"
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
