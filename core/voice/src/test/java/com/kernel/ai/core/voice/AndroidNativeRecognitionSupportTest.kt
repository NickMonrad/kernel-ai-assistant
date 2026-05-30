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
    fun `createRecognitionAvailability defaults locale status to unknown for capture startup`() {
        val availability = createRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-NZ",
            languageDisplayName = "English (New Zealand)",
        )

        assertEquals(AndroidNativeRecognitionLocaleStatus.Unknown, availability.localeStatus)
        assertNull(availability.blockingReason)
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

    @Test
    fun `capture startup does not force locale when support is still unknown`() {
        val availability = createRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-NZ",
            languageDisplayName = "English (New Zealand)",
        )

        assertEquals(false, shouldForceRecognizerLanguage(availability))
    }


    @Test
    fun `ready locale has no warning or blocking reason`() {
        val availability = AndroidNativeRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-AU",
            languageDisplayName = "English (Australia)",
            localeStatus = AndroidNativeRecognitionLocaleStatus.Ready,
        )

        assertNull(availability.blockingReason)
        assertNull(availability.warningMessage)
    }

    @Test
    fun `selectPlatformRecognitionService prefers configured external service`() {
        val configured = "com.google.android.tts/com.google.android.tts.GoogleTTSRecognitionService"
        val available = listOf(
            "com.kernel.ai.debug/com.kernel.ai.assistant.JandalRecognitionService",
            "com.google.android.as/com.google.android.as.AiAiSpeechRecognitionService",
            configured,
        )

        assertEquals(
            configured,
            selectPlatformRecognitionService(
                configuredService = configured,
                availableServices = available,
                selfPackageName = "com.kernel.ai.debug",
            ),
        )
    }

    @Test
    fun `selectPlatformRecognitionService skips self package when configured service is self`() {
        val selected = selectPlatformRecognitionService(
            configuredService = "com.kernel.ai.debug/com.kernel.ai.assistant.JandalRecognitionService",
            availableServices = listOf(
                "com.kernel.ai.debug/com.kernel.ai.assistant.JandalRecognitionService",
                "com.google.android.as/com.google.android.as.AiAiSpeechRecognitionService",
            ),
            selfPackageName = "com.kernel.ai.debug",
        )

        assertEquals(
            "com.google.android.as/com.google.android.as.AiAiSpeechRecognitionService",
            selected,
        )
    }

    @Test
    fun `selectPlatformRecognitionService returns null when only self service is available`() {
        val selected = selectPlatformRecognitionService(
            configuredService = "com.kernel.ai.debug/com.kernel.ai.assistant.JandalRecognitionService",
            availableServices = listOf("com.kernel.ai.debug/com.kernel.ai.assistant.JandalRecognitionService"),
            selfPackageName = "com.kernel.ai.debug",
        )

        assertEquals(null, selected)
    }

    @Test
    fun `selectPlatformRecognitionService prefers queryable external service over stale configured service`() {
        val selected = selectPlatformRecognitionService(
            configuredService = "com.stale.engine/com.stale.engine.OldRecognitionService",
            availableServices = listOf(
                "com.kernel.ai.debug/com.kernel.ai.assistant.JandalRecognitionService",
                "com.google.android.tts/com.google.android.tts.GoogleTTSRecognitionService",
            ),
            selfPackageName = "com.kernel.ai.debug",
        )

        assertEquals(
            "com.google.android.tts/com.google.android.tts.GoogleTTSRecognitionService",
            selected,
        )
    }

    @Test
    fun `selectPlatformRecognitionService returns null when configured external service is not queryable`() {
        val selected = selectPlatformRecognitionService(
            configuredService = "com.google.android.tts/com.google.android.tts.GoogleTTSRecognitionService",
            availableServices = emptyList(),
            selfPackageName = "com.kernel.ai.debug",
        )

        assertEquals(null, selected)
    }

    @Test
    fun `selectPlatformRecognitionService matches configured service across short and full forms`() {
        val selected = selectPlatformRecognitionService(
            configuredService = "com.google.android.tts/com.google.android.tts.GoogleTTSRecognitionService",
            availableServices = listOf(
                "com.other.engine/.OtherRecognitionService",
                "com.google.android.tts/.GoogleTTSRecognitionService",
            ),
            selfPackageName = "com.kernel.ai.debug",
        )

        assertEquals(
            "com.google.android.tts/com.google.android.tts.GoogleTTSRecognitionService",
            selected,
        )
    }
}
