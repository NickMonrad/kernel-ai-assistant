package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
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
}
