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
    fun `shouldForceRecognizerLanguage keeps requested locale for native capture`() {
        val availability = createRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-AU",
            languageDisplayName = "English (Australia)",
        )

        assertEquals(true, shouldForceRecognizerLanguage(availability))
    }
}
