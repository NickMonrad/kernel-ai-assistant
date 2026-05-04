package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeAndroidVoiceInputControllerTest {
    @Test
    fun `shouldUseCachedCaptureAvailability only for alert commands`() {
        assertEquals(false, shouldUseCachedCaptureAvailability(VoiceCaptureMode.Command))
        assertEquals(false, shouldUseCachedCaptureAvailability(VoiceCaptureMode.SlotReply))
        assertEquals(true, shouldUseCachedCaptureAvailability(VoiceCaptureMode.AlertCommand))
    }


    @Test
    fun `shouldRetryWithPlatformAfterWatchdogTimeout retries only on-device sessions without partials`() {
        assertEquals(true, shouldRetryWithPlatformAfterWatchdogTimeout(RecognizerBackend.OnDevice, false))
        assertEquals(false, shouldRetryWithPlatformAfterWatchdogTimeout(RecognizerBackend.OnDevice, true))
        assertEquals(false, shouldRetryWithPlatformAfterWatchdogTimeout(RecognizerBackend.Platform, false))
    }


    @Test
    fun `shouldRetryWithPlatformAfterStartupTimeout retries only on-device recognizer`() {
        assertEquals(true, shouldRetryWithPlatformAfterStartupTimeout(RecognizerBackend.OnDevice))
        assertEquals(false, shouldRetryWithPlatformAfterStartupTimeout(RecognizerBackend.Platform))
    }

    @Test
    fun `shouldRetryWithPlatformAfterRecognitionError keeps normal command fallback conservative`() {
        assertEquals(
            true,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.Command,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = false,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.Command,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = true,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.Platform,
                mode = VoiceCaptureMode.Command,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = false,
                sawPartialTranscript = false,
            ),
        )
    }

    @Test
    fun `shouldRetryWithPlatformAfterRecognitionError retries alert no-match after speech without partials`() {
        assertEquals(
            true,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.AlertCommand,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = true,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.AlertCommand,
                error = android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = true,
                sawPartialTranscript = true,
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
