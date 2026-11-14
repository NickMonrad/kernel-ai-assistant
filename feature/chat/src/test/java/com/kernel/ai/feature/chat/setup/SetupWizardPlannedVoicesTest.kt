package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.SherpaPiperVoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SetupWizardPlannedVoicesTest {

    @Test
    fun `piper tiers plan the Cori voice`() {
        assertEquals(listOf(SherpaPiperVoice.CoriHigh), SetupWizardViewModel.plannedVoicesFor(HardwareTier.FLAGSHIP))
        assertEquals(listOf(SherpaPiperVoice.CoriHigh), SetupWizardViewModel.plannedVoicesFor(HardwareTier.MID_RANGE))
    }

    @Test
    fun `low power plans no voice pack because its default is Android TTS`() {
        assertEquals(emptyList<SherpaPiperVoice>(), SetupWizardViewModel.plannedVoicesFor(HardwareTier.LOW_POWER))
    }
}
