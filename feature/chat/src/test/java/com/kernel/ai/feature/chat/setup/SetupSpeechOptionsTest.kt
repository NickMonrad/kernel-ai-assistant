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
    fun `Automatic and Android native are offered only when Android is ready, in every build`() {
        assertEquals(
            listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative),
            SetupSpeechOptions.inputOptions(androidNativeReady = true),
        )
        assertEquals(
            listOf(VoiceInputEngine.Auto),
            SetupSpeechOptions.inputOptions(androidNativeReady = false),
        )
    }

    @Test
    fun `the reason is shown only when Android's listener is not offered`() {
        assertNull(SetupSpeechOptions.inputReason(androidNativeReady = true))
        assertTrue(SetupSpeechOptions.inputReason(androidNativeReady = false)!!.contains("Automatic"))
    }

    @Test
    fun `release reason names the offline asr4all model Automatic falls back to`() {
        assertNull(SetupSpeechOptions.inputReason(androidNativeReady = true, isRelease = true))
        assertTrue(SetupSpeechOptions.inputReason(androidNativeReady = false, isRelease = true)!!.contains("asr4all"))
    }

    @Test
    fun `release offers Piper and Android TTS, and Inflect Micro only on eligible devices`() {
        assertEquals(
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental, VoiceOutputEngine.AndroidTts),
            SetupSpeechOptions.outputOptions(hasSystemTts = true, isRelease = true, inflectEligible = true),
        )
        assertEquals(
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.InflectMicroExperimental),
            SetupSpeechOptions.outputOptions(hasSystemTts = false, isRelease = true, inflectEligible = true),
        )
        assertEquals(
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
            SetupSpeechOptions.outputOptions(hasSystemTts = true, isRelease = true, inflectEligible = false),
        )
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
