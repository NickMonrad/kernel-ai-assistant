package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceCapabilitiesTest {

    private val full = VoiceCapabilities(
        platformRecognizerPresent = true,
        onDeviceRecognitionAvailable = true,
    )

    @Test
    fun `fully capable device with online off warns about stall risk`() {
        assertEquals(
            AndroidNativeWarning.OnlineOff,
            androidNativeWarning(full, localeReady = true, onlineAllowed = false),
        )
    }

    @Test
    fun `fully capable device with online on has no warning`() {
        assertNull(androidNativeWarning(full, localeReady = true, onlineAllowed = true))
    }

    @Test
    fun `missing recognition service outranks every other reason`() {
        val none = full.copy(platformRecognizerPresent = false, onDeviceRecognitionAvailable = false)
        assertEquals(
            AndroidNativeWarning.NoRecognitionService,
            androidNativeWarning(none, localeReady = false, onlineAllowed = false),
        )
    }

    @Test
    fun `no on-device recognizer outranks language pack and online`() {
        val noOnDevice = full.copy(onDeviceRecognitionAvailable = false)
        assertEquals(
            AndroidNativeWarning.NoOnDeviceRecognizer,
            androidNativeWarning(noOnDevice, localeReady = false, onlineAllowed = false),
        )
    }

    @Test
    fun `missing language pack outranks online off`() {
        assertEquals(
            AndroidNativeWarning.LanguagePackMissing,
            androidNativeWarning(full, localeReady = false, onlineAllowed = false),
        )
    }

    @Test
    fun `native hand-off is allowed only for not-ready and stalled categories before speech`() {
        assertTrue(isZipformerHandoffEligible(NATIVE_NOT_READY, hadSpeechProgress = false))
        assertTrue(isZipformerHandoffEligible(NATIVE_STALLED, hadSpeechProgress = false))
    }

    @Test
    fun `recognizer-reported errors are never handed off`() {
        assertFalse(isZipformerHandoffEligible(null, hadSpeechProgress = false))
        assertFalse(isZipformerHandoffEligible(NO_SPEECH_WINDOW_EXHAUSTED, hadSpeechProgress = false))
    }

    @Test
    fun `no hand-off once speech progress was seen`() {
        assertFalse(isZipformerHandoffEligible(NATIVE_STALLED, hadSpeechProgress = true))
    }
}
