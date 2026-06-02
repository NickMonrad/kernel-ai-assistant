package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** If a TTS utterance callback (onDone/onError/onStop) never fires, force SpeakingStopped after this delay. */
private const val PLAYBACK_TIMEOUT_MS = 30_000L

private const val TAG = "KernelAI"

@Singleton
class AndroidTextToSpeechController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceOutputController {

    private data class ActivePlayback(
        val token: Long,
        val utteranceIds: MutableSet<String> = mutableSetOf(),
        var firstChunkText: String? = null,
        var finalChunkQueued: Boolean = false,
    )

    private data class ActiveUtterance(
        val playbackToken: Long,
        val text: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceOutputEvent> = _events.asSharedFlow()
    private val playbackLock = Any()
    private val activeUtterances = mutableMapOf<String, ActiveUtterance>()
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var initDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var nextPlaybackToken: Long = 0L

    /**
     * Scheduled when the final chunk is queued; cancelled on natural completion ([SpeakingStopped] emitted)
     * or when [stop] is called. Prevents the "Speaking response…" stuck state if TTS callbacks drop.
     */
    @Volatile
    private var playbackTimeoutJob: Job? = null

    @Volatile
    private var activePlayback: ActivePlayback? = null

    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null

    override suspend fun warmUp(): VoiceOutputResult {
        return if (ensureReady() != null) {
            VoiceOutputResult.Spoken
        } else {
            VoiceOutputResult.Unavailable("Text-to-speech is unavailable on this device.")
        }
    }

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult {
        val text = request.text.trim()
        if (text.isBlank()) return VoiceOutputResult.Spoken
        val playbackToken = nextPlaybackToken()
        synchronized(playbackLock) {
            activePlayback = ActivePlayback(token = playbackToken)
        }
        val result = enqueuePlaybackChunk(
            playbackToken = playbackToken,
            text = text,
            locale = request.locale,
            utteranceId = request.utteranceId,
            queueMode = TextToSpeech.QUEUE_FLUSH,
            isFinal = true,
        )
        if (result is VoiceOutputResult.Spoken) {
            schedulePlaybackTimeout()
        }
        return result
    }

    override suspend fun openStreamingSession(
        request: VoiceSpeakRequest,
    ): VoiceOutputStreamingSession {
        val playbackToken = nextPlaybackToken()
        return object : VoiceOutputStreamingSession {
            private var hasQueuedChunk = false
            private var isClosed = false

            override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
                if (isClosed) return VoiceOutputResult.Spoken

                val normalized = text.trim()
                if (normalized.isBlank()) {
                    if (isFinal) {
                        isClosed = true
                        synchronized(playbackLock) {
                            activePlayback
                                ?.takeIf { it.token == playbackToken }
                                ?.finalChunkQueued = true
                        }
                        completePlaybackIfDrained(playbackToken)
                        schedulePlaybackTimeout()
                    }
                    return VoiceOutputResult.Spoken
                }

                synchronized(playbackLock) {
                    val playback = activePlayback
                    if (playback?.token != playbackToken) {
                        activePlayback = ActivePlayback(token = playbackToken)
                    }
                }
                val result = enqueuePlaybackChunk(
                    playbackToken = playbackToken,
                    text = normalized,
                    locale = request.locale,
                    utteranceId = null,
                    queueMode = if (hasQueuedChunk) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                    isFinal = isFinal,
                )
                if (result is VoiceOutputResult.Spoken) {
                    hasQueuedChunk = true
                    if (isFinal) {
                        isClosed = true
                        schedulePlaybackTimeout()
                    }
                }
                return result
            }
        }
    }

    override fun stop() {
        scope.launch {
            cancelPlaybackTimeout()
            val hadActivePlayback = synchronized(playbackLock) {
                val hadPlayback = activePlayback != null || activeUtterances.isNotEmpty()
                activePlayback = null
                activeUtterances.clear()
                hadPlayback
            }
            textToSpeech?.stop()
            releaseAudioFocus()
            if (hadActivePlayback) {
                _events.emit(VoiceOutputEvent.SpeakingStopped)
            }
        }
    }

    private suspend fun enqueuePlaybackChunk(
        playbackToken: Long,
        text: String,
        locale: Locale,
        utteranceId: String?,
        queueMode: Int,
        isFinal: Boolean,
    ): VoiceOutputResult {
        val engine = ensureReady() ?: return VoiceOutputResult.Unavailable(
            "Text-to-speech is unavailable on this device."
        )

        val availability = engine.isLanguageAvailable(locale)
        if (availability < TextToSpeech.LANG_AVAILABLE) {
            return VoiceOutputResult.Unavailable(
                "Text-to-speech voice for ${locale.toLanguageTag()} is not available on this device."
            )
        }

        engine.language = locale
        requestAudioFocus()
        val resolvedUtteranceId = utteranceId ?: "kernel-voice-${System.nanoTime()}"
        synchronized(playbackLock) {
            val playback = activePlayback?.takeIf { it.token == playbackToken }
                ?: ActivePlayback(token = playbackToken).also { activePlayback = it }
            playback.utteranceIds += resolvedUtteranceId
            if (playback.firstChunkText == null) {
                playback.firstChunkText = text
            }
            if (isFinal) {
                playback.finalChunkQueued = true
            }
            activeUtterances[resolvedUtteranceId] = ActiveUtterance(
                playbackToken = playbackToken,
                text = text,
            )
        }
        val result = engine.speak(
            text,
            queueMode,
            Bundle(),
            resolvedUtteranceId,
        )
        return if (result == TextToSpeech.ERROR) {
            synchronized(playbackLock) {
                activeUtterances.remove(resolvedUtteranceId)
                activePlayback
                    ?.takeIf { it.token == playbackToken }
                    ?.utteranceIds
                    ?.remove(resolvedUtteranceId)
            }
            completePlaybackIfDrained(playbackToken)
            VoiceOutputResult.Unavailable("Text-to-speech failed to start.")
        } else {
            VoiceOutputResult.Spoken
        }
    }

    private suspend fun ensureReady(): TextToSpeech? = withContext(Dispatchers.Main.immediate) {
        textToSpeech?.let { return@withContext it }

        val existingInit = initDeferred
        if (existingInit != null) {
            return@withContext waitForInit(existingInit)
        }

        val deferred = CompletableDeferred<Int>()
        initDeferred = deferred

        val engine = TextToSpeech(context) { status ->
            if (!deferred.isCompleted) deferred.complete(status)
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val speakingText = synchronized(playbackLock) {
                        val resolvedUtteranceId = utteranceId ?: return@synchronized null
                        val meta = activeUtterances[resolvedUtteranceId] ?: return@synchronized null
                        val playback = activePlayback?.takeIf { it.token == meta.playbackToken }
                            ?: return@synchronized null
                        playback.firstChunkText ?: meta.text
                    }
                    speakingText?.let { text ->
                        _events.tryEmit(VoiceOutputEvent.SpeakingStarted(text))
                    }
                }

                override fun onDone(utteranceId: String?) {
                    onUtteranceFinished(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onUtteranceFinished(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onUtteranceFinished(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    onUtteranceFinished(utteranceId)
                }
            }
        )
        textToSpeech = engine
        waitForInit(deferred)
    }

    private suspend fun waitForInit(deferred: CompletableDeferred<Int>): TextToSpeech? {
        val status = deferred.await()
        return if (status == TextToSpeech.SUCCESS) {
            textToSpeech
        } else {
            Log.w(TAG, "AndroidTextToSpeechController: init failed with status=$status")
            textToSpeech?.shutdown()
            textToSpeech = null
            initDeferred = null
            null
        }
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        val playbackToken = synchronized(playbackLock) {
            val resolvedUtteranceId = utteranceId ?: return@synchronized null
            val meta = activeUtterances.remove(resolvedUtteranceId) ?: return@synchronized null
            activePlayback
                ?.takeIf { it.token == meta.playbackToken }
                ?.utteranceIds
                ?.remove(resolvedUtteranceId)
            meta.playbackToken
        } ?: return
        completePlaybackIfDrained(playbackToken)
    }

    private fun completePlaybackIfDrained(playbackToken: Long) {
        val shouldEmitStopped = synchronized(playbackLock) {
            val playback = activePlayback?.takeIf { it.token == playbackToken } ?: return@synchronized false
            if (!playback.finalChunkQueued || playback.utteranceIds.isNotEmpty()) {
                return@synchronized false
            }
            activePlayback = null
            true
        }
        if (shouldEmitStopped) {
            cancelPlaybackTimeout()
            releaseAudioFocus()
            _events.tryEmit(VoiceOutputEvent.SpeakingStopped)
        }
    }

    private fun nextPlaybackToken(): Long = synchronized(playbackLock) {
        nextPlaybackToken += 1L
        nextPlaybackToken
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "AndroidTextToSpeechController: audio focus request not granted: $result")
        }
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    /**
     * Schedules a safety-net timeout that forces [VoiceOutputEvent.SpeakingStopped] if TTS
     * utterance callbacks never arrive after the final chunk was queued.
     *
     * Cancelled automatically when [stop] is called or [SpeakingStopped] is naturally emitted
     * via [completePlaybackIfDrained].
     */
    private fun schedulePlaybackTimeout() {
        cancelPlaybackTimeout()
        playbackTimeoutJob = scope.launch {
            delay(PLAYBACK_TIMEOUT_MS)
            val shouldEmit = synchronized(playbackLock) {
                val pb = activePlayback ?: return@synchronized false
                if (pb.finalChunkQueued && pb.utteranceIds.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "AndroidTextToSpeechController: playback timeout — $PLAYBACK_TIMEOUT_MS ms " +
                            "elapsed with ${pb.utteranceIds.size} pending utterance(s) — " +
                            "forcing SpeakingStopped",
                    )
                    activePlayback = null
                    activeUtterances.clear()
                    true
                } else {
                    false
                }
            }
            if (shouldEmit) {
                releaseAudioFocus()
                _events.tryEmit(VoiceOutputEvent.SpeakingStopped)
            }
        }
    }

    private fun cancelPlaybackTimeout() {
        playbackTimeoutJob?.cancel()
        playbackTimeoutJob = null
    }
}
