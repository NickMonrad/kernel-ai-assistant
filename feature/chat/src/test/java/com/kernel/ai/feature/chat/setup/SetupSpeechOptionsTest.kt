package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupSpeechOptionsTest {

    @Test
    fun `debug offers Automatic and Android native only when Android is ready`() {
        assertEquals(
            listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative),
            SetupSpeechOptions.inputOptions(androidNativeReady = true, isRelease = false),
        )
        assertEquals(
            listOf(VoiceInputEngine.Auto),
            SetupSpeechOptions.inputOptions(androidNativeReady = false, isRelease = false),
        )
    }

    @Test
    fun `release offers only Android native input`() {
        assertEquals(
            listOf(VoiceInputEngine.AndroidNative),
            SetupSpeechOptions.inputOptions(androidNativeReady = true, isRelease = true),
        )
        assertEquals(
            listOf(VoiceInputEngine.AndroidNative),
            SetupSpeechOptions.inputOptions(androidNativeReady = false, isRelease = true),
        )
    }

    @Test
    fun `the reason is shown only when Android's listener is not offered`() {
        assertNull(SetupSpeechOptions.inputReason(androidNativeReady = true))
        assertTrue(SetupSpeechOptions.inputReason(androidNativeReady = false)!!.contains("Automatic"))
    }

    @Test
    fun `release always offers only Android TTS so setup remains non-empty`() {
        listOf(true, false).forEach { hasSystemTts ->
            assertEquals(
                listOf(VoiceOutputEngine.AndroidTts),
                SetupSpeechOptions.outputOptions(
                    hasSystemTts = hasSystemTts,
                    isRelease = true,
                    inflectEligible = true,
                ),
            )
        }
    }

    @Test
    fun `debug builds offer Inflect Micro everywhere, as Settings does`() {
        assertEquals(
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental, VoiceOutputEngine.AndroidTts),
            SetupSpeechOptions.outputOptions(hasSystemTts = true, isRelease = false, inflectEligible = false),
        )
    }

    @Test
    fun `Kokoro is never offered in setup, even in debug builds`() {
        listOf(true, false).forEach { isRelease ->
            assertFalse(
                VoiceOutputEngine.KokoroExperimental in
                    SetupSpeechOptions.outputOptions(hasSystemTts = true, isRelease = isRelease, inflectEligible = true),
            )
        }
    }

    @Test
    fun `offered returns the candidate only when the options include it`() {
        val options = listOf(VoiceInputEngine.Auto)
        assertEquals(VoiceInputEngine.Auto, SetupSpeechOptions.offered(VoiceInputEngine.Auto, options))
        assertNull(SetupSpeechOptions.offered(VoiceInputEngine.SherpaParaformer, options))
    }

    @Test
    fun `the default output follows the tier when offered`() {
        val options = listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts)
        assertEquals(
            VoiceOutputEngine.defaultForTier(HardwareTier.LOW_POWER),
            SetupSpeechOptions.defaultOutput(HardwareTier.LOW_POWER, options),
        )
    }

    @Test
    fun `the default output falls back to Cori when the tier default is not offered`() {
        assertEquals(
            VoiceOutputEngine.SherpaExperimental,
            SetupSpeechOptions.defaultOutput(HardwareTier.LOW_POWER, listOf(VoiceOutputEngine.SherpaExperimental)),
        )
    }
}
