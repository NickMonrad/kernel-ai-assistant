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
    fun `Android's listener is offered only when it is ready`() {
        assertEquals(listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative), SetupSpeechOptions.inputOptions(androidNativeReady = true))
        assertEquals(listOf(VoiceInputEngine.Auto), SetupSpeechOptions.inputOptions(androidNativeReady = false))
    }

    @Test
    fun `the reason is shown only when Android's listener is not offered`() {
        assertNull(SetupSpeechOptions.inputReason(androidNativeReady = true))
        assertTrue(SetupSpeechOptions.inputReason(androidNativeReady = false)!!.contains("Automatic"))
    }

    @Test
    fun `Android's voice is offered only when a TTS engine is installed`() {
        assertEquals(
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts),
            SetupSpeechOptions.outputOptions(hasSystemTts = true),
        )
        assertEquals(listOf(VoiceOutputEngine.SherpaExperimental), SetupSpeechOptions.outputOptions(hasSystemTts = false))
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
