package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AndroidNativeRecognitionSupportTest {

    @Test
    fun `resolveLocaleStatus treats base language fallback as ready`() {
        assertEquals(
            AndroidNativeRecognitionLocaleStatus.Ready,
            resolveLocaleStatus(
                languageTag = "en-NZ",
                installedLanguages = listOf("en-US"),
                supportedLanguages = emptyList(),
                pendingLanguages = emptyList(),
                onlineLanguages = emptyList(),
            ),
        )
    }

    @Test
    fun `resolveLocaleStatus treats same-language downloadable variant as unavailable`() {
        assertEquals(
            AndroidNativeRecognitionLocaleStatus.Unavailable,
            resolveLocaleStatus(
                languageTag = "en-NZ",
                installedLanguages = emptyList(),
                supportedLanguages = listOf("en-GB"),
                pendingLanguages = emptyList(),
                onlineLanguages = emptyList(),
            ),
        )
    }

    @Test
    fun `resolveLocaleStatus returns unknown when no matching language exists`() {
        assertEquals(
            AndroidNativeRecognitionLocaleStatus.Unknown,
            resolveLocaleStatus(
                languageTag = "en-NZ",
                installedLanguages = listOf("de-DE"),
                supportedLanguages = listOf("fr-FR"),
                pendingLanguages = emptyList(),
                onlineLanguages = emptyList(),
            ),
        )
    }

    @Test
    fun `unknown locale support warns without blocking start`() {
        val availability = AndroidNativeRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-AU",
            languageDisplayName = "English (Australia)",
            localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown,
        )

        assertNull(availability.blockingReason)
        assertEquals(
            "Android native speech recognition could not verify on-device support for English (Australia) on this device. It may fail unless that language is supported and installed locally.",
            availability.warningMessage,
        )
    }

    @Test
    fun `unsupported locale still blocks start`() {
        val availability = AndroidNativeRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-NZ",
            languageDisplayName = "English (New Zealand)",
            localeStatus = AndroidNativeRecognitionLocaleStatus.NotSupported,
        )

        assertEquals(
            "English (New Zealand) is not supported by Android native speech recognition on this device.",
            availability.blockingReason,
        )
        assertEquals(availability.blockingReason, availability.warningMessage)
    }
}
