package com.kernel.ai.core.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AndroidTtsController"

@Singleton
class AndroidTextToSpeechController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceOutputController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var initDeferred: CompletableDeferred<Int>? = null

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
        val result = engine.speak(
            request.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            request.utteranceId ?: "kernel-voice-${System.nanoTime()}",
        )
        return if (result == TextToSpeech.ERROR) {
            VoiceOutputResult.Unavailable("Text-to-speech failed to start.")
        } else {
            VoiceOutputResult.Spoken
        }
    }

    override fun stop() {
        scope.launch {
            textToSpeech?.stop()
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
}
