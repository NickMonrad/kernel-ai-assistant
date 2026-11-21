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

    /**
     * Silence of [durationMs] between the text already appended and the next text (#1942), for example
     * after a reply's intro sentence. Default: no pause; engines that cannot insert silence ignore it.
     */
    suspend fun appendPause(durationMs: Long) {}

    /**
     * Closes the session after the producing coroutine was cancelled. Releases streaming
     * resources (closes chunk channels, marks the final chunk queued) without starting any
     * new playback. Default: an empty final [append], which streaming controllers treat as
     * "finish".
     */
    suspend fun cancel() {
        append("", isFinal = true)
    }

    /**
     * Starts playback of a session from [VoiceOutputController.openPreSynthesisedSession] (#1923). Returns
     * false when playback can no longer start (the held audio was cancelled or failed to synthesise), so the
     * caller must speak the reply another way. Default: false, for sessions that are not held.
     */
    suspend fun release(): Boolean = false
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

    override suspend fun cancel() {
        // Cancellation must not flush the buffer into fresh playback — just discard it.
        isClosed = true
    }
}

interface VoiceOutputController {
    val events: Flow<VoiceOutputEvent> get() = emptyFlow()

    suspend fun warmUp(): VoiceOutputResult = VoiceOutputResult.Spoken

    suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult

    suspend fun openStreamingSession(
        request: VoiceSpeakRequest = VoiceSpeakRequest(text = ""),
    ): VoiceOutputStreamingSession = BufferedVoiceOutputStreamingSession(this, request)

    /**
     * Opens a streaming session whose first chunk is synthesised before the reply is confirmed (#1923). The
     * first chunk is not played until [VoiceOutputStreamingSession.release]; [VoiceOutputStreamingSession.cancel]
     * before that discards it without emitting [VoiceOutputEvent.SpeakingStopped]. Null when this backend cannot
     * synthesise ahead, so the caller speaks on confirmation as before.
     */
    suspend fun openPreSynthesisedSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession? = null

    fun stop()

    /** Suspending variant of [stop]; default delegates to [stop]. */
    suspend fun stopSpeaking() { stop() }
}
