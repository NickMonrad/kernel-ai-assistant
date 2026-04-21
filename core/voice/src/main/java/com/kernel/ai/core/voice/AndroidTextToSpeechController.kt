package com.kernel.ai.core.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AndroidTtsController"

@Singleton
class AndroidTextToSpeechController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceOutputController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceOutputEvent> = _events.asSharedFlow()

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var initDeferred: CompletableDeferred<Int>? = null

    @Volatile
    private var activeUtteranceId: String? = null

    @Volatile
    private var activeUtteranceText: String? = null

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult {
        val engine = ensureReady() ?: return VoiceOutputResult.Unavailable(
            "Text-to-speech is unavailable on this device."
        )

        val locale = request.locale
        val availability = engine.isLanguageAvailable(locale)
        if (availability < TextToSpeech.LANG_AVAILABLE) {
            return VoiceOutputResult.Unavailable(
                "Text-to-speech voice for ${locale.toLanguageTag()} is not available on this device."
            )
        }

        engine.language = locale
        val utteranceId = request.utteranceId ?: "kernel-voice-${System.nanoTime()}"
        activeUtteranceId = utteranceId
        activeUtteranceText = request.text
        val result = engine.speak(
            request.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            utteranceId,
        )
        return if (result == TextToSpeech.ERROR) {
            activeUtteranceId = null
            activeUtteranceText = null
            VoiceOutputResult.Unavailable("Text-to-speech failed to start.")
        } else {
            VoiceOutputResult.Spoken
        }
    }

    override fun stop() {
        scope.launch {
            textToSpeech?.stop()
            activeUtteranceId = null
            activeUtteranceText = null
            _events.emit(VoiceOutputEvent.SpeakingStopped)
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
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == activeUtteranceId) {
                        activeUtteranceText?.let { text ->
                            _events.tryEmit(VoiceOutputEvent.SpeakingStarted(text))
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    clearActiveUtterance(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    clearActiveUtterance(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    clearActiveUtterance(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    clearActiveUtterance(utteranceId)
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
            Log.w(TAG, "TextToSpeech init failed with status=$status")
            textToSpeech?.shutdown()
            textToSpeech = null
            initDeferred = null
            null
        }
    }

    private fun clearActiveUtterance(utteranceId: String?) {
        if (utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        activeUtteranceText = null
        _events.tryEmit(VoiceOutputEvent.SpeakingStopped)
    }
}
