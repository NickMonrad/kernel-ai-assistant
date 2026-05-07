package com.kernel.ai.core.voice

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class VoiceSpeakRequest(
    val text: String,
    val locale: Locale = Locale.getDefault(),
    val utteranceId: String? = null,
)

interface VoiceOutputStreamingSession {
    suspend fun append(text: String, isFinal: Boolean = false): VoiceOutputResult
}

sealed interface VoiceOutputResult {
    object Spoken : VoiceOutputResult
    data class Unavailable(val message: String) : VoiceOutputResult
}

sealed interface VoiceOutputEvent {
    data class SpeakingStarted(val text: String) : VoiceOutputEvent
    object SpeakingStopped : VoiceOutputEvent
}

private class BufferedVoiceOutputStreamingSession(
    private val controller: VoiceOutputController,
    private val request: VoiceSpeakRequest,
) : VoiceOutputStreamingSession {
    private val buffer = StringBuilder()
    private var isClosed = false

    override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
        if (isClosed) return VoiceOutputResult.Spoken
        if (text.isNotBlank()) {
            buffer.append(text)
        }
        if (!isFinal) return VoiceOutputResult.Spoken

        isClosed = true
        val finalText = buffer.toString().trim()
        if (finalText.isBlank()) return VoiceOutputResult.Spoken
        return controller.speak(request.copy(text = finalText))
    }
}

interface VoiceOutputController {
    val events: Flow<VoiceOutputEvent> get() = emptyFlow()

    suspend fun warmUp(): VoiceOutputResult = VoiceOutputResult.Spoken

    suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult

    suspend fun openStreamingSession(
        request: VoiceSpeakRequest = VoiceSpeakRequest(text = ""),
    ): VoiceOutputStreamingSession = BufferedVoiceOutputStreamingSession(this, request)

    fun stop()

    /** Suspending variant of [stop]; default delegates to [stop]. */
    suspend fun stopSpeaking() { stop() }
}
