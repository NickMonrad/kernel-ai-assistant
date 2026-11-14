package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SetupWizardPlannedVoicesTest {

    @Test
    fun `sherpa engine plans the Cori voice when it is not on disk`() {
        assertEquals(
            listOf(SherpaPiperVoice.CoriHigh),
            SetupWizardViewModel.plannedVoicesFor(VoiceOutputEngine.SherpaExperimental) { false },
        )
    }

    @Test
    fun `an already downloaded voice is not planned again`() {
        assertEquals(
            emptyList<SherpaPiperVoice>(),
            SetupWizardViewModel.plannedVoicesFor(VoiceOutputEngine.SherpaExperimental) { true },
        )
    }

    @Test
    fun `android tts engine plans no voice pack`() {
        assertEquals(
            emptyList<SherpaPiperVoice>(),
            SetupWizardViewModel.plannedVoicesFor(VoiceOutputEngine.AndroidTts) { false },
        )
    }
}
