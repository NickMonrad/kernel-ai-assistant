package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupSpeechOptionsTest {

    @Test
    fun `Automatic and asr4all streaming are always offered, Android native only when Android is ready`() {
        assertEquals(
            listOf(VoiceInputEngine.Auto, VoiceInputEngine.Asr4all, VoiceInputEngine.AndroidNative),
            SetupSpeechOptions.inputOptions(androidNativeReady = true),
        )
        assertEquals(
            listOf(VoiceInputEngine.Auto, VoiceInputEngine.Asr4all),
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
        val reason = SetupSpeechOptions.inputReason(androidNativeReady = false, isRelease = true)!!
        assertTrue(reason.contains("asr4all"))
        assertTrue(reason.contains("once it has downloaded"))
    }

    @Test
    fun `release offers Piper, Kokoro and Android TTS, and Inflect Micro only on eligible devices`() {
        assertEquals(
            listOf(
                VoiceOutputEngine.SherpaExperimental,
                VoiceOutputEngine.InflectMicroExperimental,
                VoiceOutputEngine.KokoroExperimental,
                VoiceOutputEngine.AndroidTts,
            ),
            SetupSpeechOptions.outputOptions(hasSystemTts = true, isRelease = true, inflectEligible = true),
        )
        assertEquals(
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.KokoroExperimental),
            SetupSpeechOptions.outputOptions(hasSystemTts = false, isRelease = true, inflectEligible = false),
        )
    }

    @Test
    fun `debug builds offer Inflect Micro everywhere, as Settings does`() {
        assertEquals(
            listOf(
                VoiceOutputEngine.SherpaExperimental,
                VoiceOutputEngine.InflectMicroExperimental,
                VoiceOutputEngine.KokoroExperimental,
                VoiceOutputEngine.AndroidTts,
            ),
            SetupSpeechOptions.outputOptions(hasSystemTts = true, isRelease = false, inflectEligible = false),
        )
    }

    @Test
    fun `offered returns the candidate only when the options include it`() {
        val options = listOf(VoiceInputEngine.Auto)
        assertEquals(VoiceInputEngine.Auto, SetupSpeechOptions.offered(VoiceInputEngine.Auto, options))
        assertNull(SetupSpeechOptions.offered(VoiceInputEngine.SherpaParaformer, options))
    }

    @Test
    fun `an engine in use that setup lists gets no extra card, so the listed card is the one ticked`() {
        val options = SetupSpeechOptions.inputOptions(androidNativeReady = true)
        assertNull(SetupSpeechOptions.extraCurrent(options, VoiceInputEngine.Asr4all))
    }

    @Test
    fun `an engine in use that setup does not list is an extra ticked card`() {
        val options = SetupSpeechOptions.inputOptions(androidNativeReady = false)
        assertEquals(VoiceInputEngine.SherpaParaformer, SetupSpeechOptions.extraCurrent(options, VoiceInputEngine.SherpaParaformer))
    }

    @Test
    fun `Android native in use on a device where it is not ready is shown as the current card`() {
        val options = SetupSpeechOptions.inputOptions(androidNativeReady = false)
        assertEquals(
            VoiceInputEngine.AndroidNative,
            SetupSpeechOptions.extraCurrent(options, VoiceInputEngine.AndroidNative),
        )
    }

    @Test
    fun `no engine in use adds no extra card`() {
        val options = listOf(VoiceOutputEngine.SherpaExperimental)
        assertNull(SetupSpeechOptions.extraCurrent(options, null))
    }

    @Test
    fun `an output in use that setup does not list (Android TTS without system TTS) is ticked as a current card`() {
        val options = SetupSpeechOptions.outputOptions(hasSystemTts = false, isRelease = true, inflectEligible = false)
        assertEquals(
            VoiceOutputEngine.AndroidTts,
            SetupSpeechOptions.extraCurrent(options, VoiceOutputEngine.AndroidTts),
        )
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
