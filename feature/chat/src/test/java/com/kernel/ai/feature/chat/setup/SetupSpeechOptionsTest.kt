package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupSpeechOptionsTest {

    private val withOnDevice = VoiceCapabilities(platformRecognizerPresent = true, onDeviceRecognitionAvailable = true)
    private val withoutOnDevice = VoiceCapabilities(platformRecognizerPresent = true, onDeviceRecognitionAvailable = false)

    @Test
    fun `android native is offered only when on-device recognition is available`() {
        assertEquals(listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative), SetupSpeechOptions.inputOptions(withOnDevice))
        assertEquals(listOf(VoiceInputEngine.Auto), SetupSpeechOptions.inputOptions(withoutOnDevice))
    }

    @Test
    fun `a service being registered alone does not offer android native`() {
        val registeredOnly = VoiceCapabilities(platformRecognizerPresent = true, onDeviceRecognitionAvailable = false)
        assertEquals(listOf(VoiceInputEngine.Auto), SetupSpeechOptions.inputOptions(registeredOnly))
    }

    @Test
    fun `input reason explains the missing option and is null when offered`() {
        assertNull(SetupSpeechOptions.inputReason(withOnDevice))
        assertTrue(SetupSpeechOptions.inputReason(withoutOnDevice) != null)
    }

    @Test
    fun `android tts is offered only when a system TTS engine exists`() {
        assertEquals(
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
            SetupSpeechOptions.outputOptions(hasSystemTts = true),
        )
        assertEquals(listOf(VoiceOutputEngine.SherpaExperimental), SetupSpeechOptions.outputOptions(hasSystemTts = false))
    }

    @Test
    fun `resolve keeps an offered candidate and falls back to the first option`() {
        assertEquals(VoiceInputEngine.AndroidNative, SetupSpeechOptions.resolve(VoiceInputEngine.AndroidNative, listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative)))
        assertEquals(VoiceInputEngine.Auto, SetupSpeechOptions.resolve(VoiceInputEngine.AndroidNative, listOf(VoiceInputEngine.Auto)))
    }

    @Test
    fun `initial output follows the stored Cori choice regardless of tier`() {
        val both = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts)
        assertEquals(VoiceOutputEngine.SherpaExperimental, SetupSpeechOptions.initialOutput(VoiceOutputEngine.SherpaExperimental, HardwareTier.LOW_POWER, both))
    }

    @Test
    fun `initial output for a stored Android TTS uses the tier default when Cori is offered`() {
        val both = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts)
        assertEquals(VoiceOutputEngine.SherpaExperimental, SetupSpeechOptions.initialOutput(VoiceOutputEngine.AndroidTts, HardwareTier.MID_RANGE, both))
        assertEquals(VoiceOutputEngine.AndroidTts, SetupSpeechOptions.initialOutput(VoiceOutputEngine.AndroidTts, HardwareTier.LOW_POWER, both))
    }

    @Test
    fun `initial output never leaves the offered options`() {
        val cori = listOf(VoiceOutputEngine.SherpaExperimental)
        assertEquals(VoiceOutputEngine.SherpaExperimental, SetupSpeechOptions.initialOutput(VoiceOutputEngine.AndroidTts, HardwareTier.LOW_POWER, cori))
    }
}
