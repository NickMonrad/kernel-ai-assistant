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
import kotlinx.coroutines.CancellationException
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
internal const val PLAYBACK_TIMEOUT_MS = 30_000L

private const val TAG = "KernelAI"

/**
 * Test seam for [AndroidTextToSpeechController]: creates a [TextToSpeech] instance using
 * either the configured default engine (`enginePackage == null`) or an explicit engine
 * package (the recovery path).
 */
internal fun interface TextToSpeechFactory {
    fun create(listener: TextToSpeech.OnInitListener, enginePackage: String?): TextToSpeech
}

@Singleton
class AndroidTextToSpeechController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceOutputController {

    private var ttsFactory: TextToSpeechFactory = TextToSpeechFactory { listener, enginePackage ->
        if (enginePackage == null) {
            TextToSpeech(context, listener)
        } else {
            TextToSpeech(context, listener, enginePackage)
        }
    }

    /**
     * Package the implicit default-engine attempt targeted, captured from
     * [TextToSpeech.getDefaultEngine] so the recovery path never retries the engine that
     * just failed. The recovery path never reads or writes the device's global
     * `Settings.Secure.TTS_DEFAULT_SYNTH` value.
     */
    private var defaultEnginePackage: String? = null

    /**
     * Installed engines captured from the default-engine attempt via
     * [TextToSpeech.getEngines] (SDK 35+ exposes engine enumeration as an instance API;
     * it reads the package manager, independent of init success). Used only by the
     * recovery path in [selectFallbackEngine].
     */
    private var installedEngines: List<TextToSpeech.EngineInfo>? = null

    internal constructor(
        context: Context,
        ttsFactory: TextToSpeechFactory,
    ) : this(context) {
        this.ttsFactory = ttsFactory
    }

    private data class ActivePlayback(
        val token: Long,
        val utteranceIds: MutableSet<String> = mutableSetOf(),
        var firstChunkText: String? = null,
        var finalChunkQueued: Boolean = false,
        var startLogged: Boolean = false,
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

    private data class ReadyEngine(
        val instance: TextToSpeech,
        val enginePackage: String?,
    )

    private data class LocaleResolution(
        val locale: Locale?,
        val statuses: List<Int>,
        // Only meaningful when locale == null; getVoices() is null when the engine is unbound.
        val engineBound: Boolean = false,
    ) {
        // An unbound engine answers LANG_NOT_SUPPORTED for every locale, so that status is
        // evidence of a missing voice only when the engine is bound.
        val definitelyUnsupported: Boolean
            get() = locale == null && engineBound && statuses.isNotEmpty() &&
                statuses.all { it == TextToSpeech.LANG_NOT_SUPPORTED }
    }

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var activeEnginePackage: String? = null

    /** Shares the complete default-engine/fallback sequence, not just OnInit callback status. */
    @Volatile
    private var initializationDeferred: CompletableDeferred<ReadyEngine?>? = null

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
            schedulePlaybackTimeoutFor(playbackToken)
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
                        schedulePlaybackTimeoutFor(playbackToken)
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
                        schedulePlaybackTimeoutFor(playbackToken)
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
        allowRecovery: Boolean = true,
    ): VoiceOutputResult {
        fun unavailable(message: String): VoiceOutputResult {
            if (isFinal) {
                synchronized(playbackLock) {
                    activePlayback
                        ?.takeIf { it.token == playbackToken }
                        ?.finalChunkQueued = true
                }
                completePlaybackIfDrained(playbackToken)
            }
            return VoiceOutputResult.Unavailable(message)
        }

        var engine = ensureReady()
            ?: return unavailable("Text-to-speech is unavailable on this device.")
        var localeResolution = resolveAvailableLocale(engine, locale)
        logLocaleResolution(locale, localeResolution, "initial")
        if (localeResolution.locale == null && !localeResolution.definitelyUnsupported && allowRecovery) {
            val recovered = reinitializeAfterFailure(engine, "locale_unverified")
            if (recovered != null) {
                engine = recovered
                localeResolution = resolveAvailableLocale(engine, locale)
                logLocaleResolution(locale, localeResolution, "after_recovery")
            }
        }

        val resolvedLocale = localeResolution.locale
        if (resolvedLocale == null) {
            val message = if (localeResolution.definitelyUnsupported) {
                "Text-to-speech voice for ${locale.toLanguageTag()} is not supported by the installed engine."
            } else {
                "Text-to-speech could not verify ${locale.toLanguageTag()}; check the TTS engine connection and installed voice data."
            }
            return unavailable(message)
        }

        engine.language = resolvedLocale
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
        if (result != TextToSpeech.ERROR) return VoiceOutputResult.Spoken

        synchronized(playbackLock) {
            activeUtterances.remove(resolvedUtteranceId)
            activePlayback
                ?.takeIf { it.token == playbackToken }
                ?.utteranceIds
                ?.remove(resolvedUtteranceId)
        }
        Log.w(TAG, "AndroidTextToSpeechController: speak failed with ERROR; attempting bounded engine recovery.")
        releaseAudioFocus()
        if (allowRecovery) {
            val recovered = reinitializeAfterFailure(engine, "speak_error")
            if (recovered != null) {
                val retry = enqueuePlaybackChunk(
                    playbackToken = playbackToken,
                    text = text,
                    locale = locale,
                    utteranceId = utteranceId,
                    queueMode = queueMode,
                    isFinal = isFinal,
                    allowRecovery = false,
                )
                if (retry is VoiceOutputResult.Unavailable) completePlaybackIfDrained(playbackToken)
                return retry
            }
        }
        completePlaybackIfDrained(playbackToken)
        return VoiceOutputResult.Unavailable("Text-to-speech could not start speech. Check the TTS engine and try again.")
    }

    private fun resolveAvailableLocale(
        engine: TextToSpeech,
        requested: Locale,
    ): LocaleResolution {
        val languageFallback = requested.language
            .takeIf { it.isNotBlank() }
            ?.let(Locale::forLanguageTag)
        val candidates = sequenceOf(
            requested,
            languageFallback,
            if (requested.language == Locale.ENGLISH.language) Locale.US else null,
            Locale.getDefault(),
        ).filterNotNull().distinct()
        val statuses = mutableListOf<Int>()
        candidates.forEach { candidate ->
            val status = runCatching { engine.isLanguageAvailable(candidate) }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "AndroidTextToSpeechController: locale check failed for ${candidate.toLanguageTag()} " +
                            "with ${error.javaClass.simpleName}.",
                    )
                }
                .getOrElse { Int.MIN_VALUE }
            statuses += status
            if (status >= TextToSpeech.LANG_AVAILABLE) {
                return LocaleResolution(locale = candidate, statuses = statuses)
            }
        }
        return LocaleResolution(locale = null, statuses = statuses, engineBound = isEngineBound(engine))
    }

    // getVoices() returns null when the TextToSpeech service is not bound. A query failure counts as unbound.
    private fun isEngineBound(engine: TextToSpeech): Boolean =
        runCatching { engine.voices }.getOrNull() != null

    private fun logLocaleResolution(
        requested: Locale,
        resolution: LocaleResolution,
        phase: String,
    ) {
        if (resolution.locale != null) return
        val enginePackage = activeEnginePackage ?: defaultEnginePackage ?: "default"
        Log.w(
            TAG,
            "AndroidTextToSpeechController: locale unresolved phase=$phase engine=$enginePackage " +
                "requested=${requested.toLanguageTag()} statuses=${resolution.statuses.joinToString(",")} engineBound=${resolution.engineBound}.",
        )
    }

    private suspend fun ensureReady(
        reinitialize: Boolean = false,
        failedEngine: TextToSpeech? = null,
    ): TextToSpeech? = withContext(Dispatchers.Main.immediate) {
        initializationDeferred?.let { return@withContext it.await()?.instance }
        if (!reinitialize) {
            textToSpeech?.let { return@withContext it }
        } else if (failedEngine != null && textToSpeech !== failedEngine) {
            // Another caller already replaced the failed engine; use the current ready instance.
            return@withContext textToSpeech
        }

        val currentEngine = textToSpeech
        val preferredPackage = activeEnginePackage
        if (reinitialize && synchronized(playbackLock) { activeUtterances.isNotEmpty() }) {
            Log.w(TAG, "AndroidTextToSpeechController: deferring recovery while another utterance is active.")
            return@withContext currentEngine
        }
        if (reinitialize) {
            textToSpeech = null
            activeEnginePackage = null
            currentEngine?.shutdown()
            Log.w(
                TAG,
                "AndroidTextToSpeechController: reinitializing TTS engine after a bounded runtime failure.",
            )
        }

        val deferred = CompletableDeferred<ReadyEngine?>()
        initializationDeferred = deferred
        try {
            val ready = if (reinitialize && preferredPackage != null) {
                initializeEngine(preferredPackage)
            } else {
                initializeDefaultWithFallback()
            }
            if (ready != null) {
                textToSpeech = ready.instance
                activeEnginePackage = ready.enginePackage
            }
            deferred.complete(ready)
            ready?.instance
        } catch (cancelled: CancellationException) {
            // This deferred is shared with concurrent callers; complete it without propagating
            // the initiating caller's cancellation to those waiters.
            deferred.complete(null)
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "AndroidTextToSpeechController: engine initialization failed (${error.javaClass.simpleName}).")
            deferred.complete(null)
            null
        } finally {
            if (initializationDeferred === deferred) initializationDeferred = null
        }
    }

    private suspend fun initializeDefaultWithFallback(): ReadyEngine? {
        // Normal path (#1463): use Android's configured default engine, exactly as before.
        initializeEngine(enginePackage = null)?.let { return it }

        // Recovery path (#1463): the configured default engine is stale or missing. Pick an
        // actually installed engine (never the one that just failed) and retry once with the
        // explicit-engine constructor. The device's global TTS default is never modified.
        val fallbackPackage = selectFallbackEngine()
        if (fallbackPackage == null) {
            Log.w(
                TAG,
                "AndroidTextToSpeechController: default TTS engine unavailable and no installed " +
                    "engine found for recovery; reporting Unavailable.",
            )
            return null
        }
        Log.i(
            TAG,
            "AndroidTextToSpeechController: default TTS engine unavailable; retrying with " +
                "installed engine package $fallbackPackage.",
        )
        return initializeEngine(enginePackage = fallbackPackage)
    }

    /**
     * Uses the platform's installed-engine order and skips the default package that failed
     * initialization. The global TTS default is never read or changed.
     */
    private fun selectFallbackEngine(): String? =
        (installedEngines ?: emptyList())
            .asSequence()
            .map { it.name }
            .filter { enginePackage -> enginePackage != null && enginePackage != defaultEnginePackage }
            .firstOrNull()

    private suspend fun initializeEngine(enginePackage: String?): ReadyEngine? {
        val deferred = CompletableDeferred<Int>()
        val engine = ttsFactory.create(
            listener = { status ->
                if (!deferred.isCompleted) deferred.complete(status)
            },
            enginePackage = enginePackage,
        )
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val started = synchronized(playbackLock) {
                        val resolvedUtteranceId = utteranceId ?: return@synchronized null
                        val meta = activeUtterances[resolvedUtteranceId] ?: return@synchronized null
                        val playback = activePlayback?.takeIf { it.token == meta.playbackToken }
                            ?: return@synchronized null
                        val firstStart = !playback.startLogged
                        playback.startLogged = true
                        Triple(playback.firstChunkText ?: meta.text, meta.text, firstStart)
                    }
                    started?.let { (eventText, chunkText, firstStart) ->
                        // The trace wants the chunk that is starting now, not the first one.
                        VoiceOutputTrace.speakingStarted("android_tts", chunkText)
                        if (firstStart) VoiceOutputTrace.logSpeakingStarted("android-tts", chunkText.length)
                        _events.tryEmit(VoiceOutputEvent.SpeakingStarted(eventText))
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
        val status = try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            engine.shutdown()
            throw cancelled
        }
        if (status == TextToSpeech.SUCCESS) {
            return ReadyEngine(instance = engine, enginePackage = enginePackage)
        }

        Log.w(
            TAG,
            "AndroidTextToSpeechController: TTS initialization callback failed with status=$status.",
        )
        if (enginePackage == null) {
            // Preserve #1463: capture the failing default and installed engines for fallback.
            defaultEnginePackage = engine.defaultEngine
            installedEngines = engine.engines
        }
        engine.shutdown()
        return null
    }

    private suspend fun reinitializeAfterFailure(
        failedEngine: TextToSpeech,
        reason: String,
    ): TextToSpeech? {
        Log.w(TAG, "AndroidTextToSpeechController: bounded recovery requested reason=$reason.")
        return ensureReady(reinitialize = true, failedEngine = failedEngine)
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

    /**
     * Arms the safety-net timeout only while [playbackToken] still owns [activePlayback]. A stale
     * session (superseded by a newer speak/stream) must not cancel the newer playback's timeout.
     */
    private fun schedulePlaybackTimeoutFor(playbackToken: Long) {
        synchronized(playbackLock) {
            if (activePlayback?.token == playbackToken) schedulePlaybackTimeout()
        }
    }

    private fun cancelPlaybackTimeout() {
        playbackTimeoutJob?.cancel()
        playbackTimeoutJob = null
    }
}
