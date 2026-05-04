package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeAndroidVoiceInputControllerTest {
    @Test
    fun `shouldRetryWithPlatformAfterStartupTimeout retries only on-device recognizer`() {
        assertEquals(true, shouldRetryWithPlatformAfterStartupTimeout(RecognizerBackend.OnDevice))
        assertEquals(false, shouldRetryWithPlatformAfterStartupTimeout(RecognizerBackend.Platform))
    }

    @Test
    fun `shouldRetryWithPlatformAfterRecognitionError retries startup silence on-device only`() {
        assertEquals(
            true,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = false,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = true,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.Platform,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = false,
                sawPartialTranscript = false,
            ),
        )
    }

    @Test
    fun `shouldForceRecognizerLanguage only forces verified locale`() {
        val unknownAvailability = createRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-AU",
            languageDisplayName = "English (Australia)",
        )
        val readyAvailability = createRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-AU",
            languageDisplayName = "English (Australia)",
            localeStatus = AndroidNativeRecognitionLocaleStatus.Ready,
        )

        assertEquals(false, shouldForceRecognizerLanguage(unknownAvailability))
        assertEquals(true, shouldForceRecognizerLanguage(readyAvailability))
    }
}
