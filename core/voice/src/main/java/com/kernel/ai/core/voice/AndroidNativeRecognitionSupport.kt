package com.kernel.ai.core.voice

import android.content.Context
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AndroidNativeRecognitionAvailability(
    val isRecognitionAvailable: Boolean,
    val isOnDeviceRecognitionAvailable: Boolean,
) {
    val unavailableReason: String?
        get() = when {
            !isRecognitionAvailable -> "Android speech recognition is not available on this device."
            !isOnDeviceRecognitionAvailable -> "On-device Android speech recognition is unavailable for the current setup. Install the required language pack or keep using Vosk for guaranteed local voice input."
            else -> null
        }
}

@Singleton
class AndroidNativeRecognitionSupport @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun getAvailability(): AndroidNativeRecognitionAvailability =
        AndroidNativeRecognitionAvailability(
            isRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            isOnDeviceRecognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
        )

    fun createOnDeviceSpeechRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
}
