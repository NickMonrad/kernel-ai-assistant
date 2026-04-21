package com.kernel.ai.core.voice

import java.util.Locale

data class VoiceSpeakRequest(
    val text: String,
    val locale: Locale = Locale.getDefault(),
    val utteranceId: String? = null,
)

sealed interface VoiceOutputResult {
    object Spoken : VoiceOutputResult
    data class Unavailable(val message: String) : VoiceOutputResult
}

interface VoiceOutputController {
    suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult

    fun stop()
}
