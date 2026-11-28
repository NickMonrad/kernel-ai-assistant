package com.kernel.ai.feature.settings

import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeakingOptionsTest {

    private fun state(
        engines: List<VoiceOutputEngine>,
        piperVoices: List<SherpaPiperVoice> = listOf(SherpaPiperVoice.CoriHigh),
    ) = VoiceUiState(
        availableOutputEngines = engines,
        sherpaVoices = piperVoices.map { SherpaVoiceRowUiState(it) },
    )

    @Test
    fun inflectCardAppearsOnceWhenEngineIsAvailable() {
        val options = speakingOptions(state(engines = VoiceOutputEngine.entries))

        assertEquals(1, options.count { it is SpeakingOption.InflectMicro })
    }

    @Test
    fun inflectCardAbsentWhenEngineIsNotAvailable() {
        val options = speakingOptions(
            state(engines = listOf(VoiceOutputEngine.AndroidTts, VoiceOutputEngine.SherpaExperimental)),
        )

        assertEquals(0, options.count { it is SpeakingOption.InflectMicro })
    }

    @Test
    fun releaseBuildShowsOnlyCoriInflectAndPhoneVoiceWithoutKokoro() {
        // Release: sherpaVoices is already filtered to Cori, and Kokoro is not in availableOutputEngines.
        val options = speakingOptions(
            state(
                engines = listOf(
                    VoiceOutputEngine.SherpaExperimental,
                    VoiceOutputEngine.InflectMicroExperimental,
                    VoiceOutputEngine.AndroidTts,
                ),
            ),
        )

        assertEquals(
            listOf("speaking_piper_CoriHigh", "speaking_inflect_micro", "speaking_phone_voice"),
            options.map(::speakingCardTestTag),
        )
    }

    @Test
    fun debugBuildOrdersPiperVoicesThenInflectThenKokoroThenPhone() {
        val piper = SherpaPiperVoice.entries
        val options = speakingOptions(state(engines = VoiceOutputEngine.entries, piperVoices = piper))

        val expected = piper.map { "speaking_piper_${it.name}" } +
            "speaking_inflect_micro" +
            "speaking_kokoro" +
            "speaking_phone_voice"
        assertEquals(expected, options.map(::speakingCardTestTag))
    }
}
