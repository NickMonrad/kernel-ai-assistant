package com.kernel.ai.core.voice

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class VoiceSpeakRequest(
    val text: String,
    val locale: Locale = Locale.getDefault(),
    val utteranceId: String? = null,
)

sealed interface VoiceOutputResult {
    object Spoken : VoiceOutputResult
    data class Unavailable(val message: String) : VoiceOutputResult
}

sealed interface VoiceOutputEvent {
    data class SpeakingStarted(val text: String) : VoiceOutputEvent
    object SpeakingStopped : VoiceOutputEvent
}

interface VoiceOutputController {
    val events: Flow<VoiceOutputEvent> get() = emptyFlow()

    suspend fun warmUp(): VoiceOutputResult = VoiceOutputResult.Spoken

    suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult

    fun stop()
}
