package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceInputEngineTest {
    @Test
    fun `unset and unknown engines resolve to Auto`() {
        assertEquals(VoiceInputEngine.Auto, VoiceInputEngine.fromStorage(null))
        assertEquals(VoiceInputEngine.Auto, VoiceInputEngine.fromStorage("not-an-engine"))
    }

    @Test
    fun `former Vosk users migrate to Auto`() {
        assertEquals(VoiceInputEngine.Auto, VoiceInputEngine.fromStorage("Vosk"))
    }

    @Test
    fun `legacy SherpaOnnx value still maps to Zipformer`() {
        assertEquals(VoiceInputEngine.SherpaZipformer, VoiceInputEngine.fromStorage("SherpaOnnx"))
    }

    @Test
    fun `stored engines round-trip`() {
        VoiceInputEngine.entries.forEach { engine ->
            assertEquals(engine, VoiceInputEngine.fromStorage(engine.name))
        }
    }

    @Test
    fun `provisioner downloads Zipformer only for engines that use it`() {
        assertTrue(VoiceDefaultsProvisioner.needsZipformer(VoiceInputEngine.Auto))
        assertTrue(VoiceDefaultsProvisioner.needsZipformer(VoiceInputEngine.SherpaZipformer))
        assertFalse(VoiceDefaultsProvisioner.needsZipformer(VoiceInputEngine.AndroidNative))
        assertFalse(VoiceDefaultsProvisioner.needsZipformer(VoiceInputEngine.SherpaWhisper))
    }

    @Test
    fun `provisioner maps every Zipformer file to a catalogue model`() {
        assertEquals(
            SherpaSttModelSpec.ZIPFORMER.requiredFileNames.size,
            VoiceDefaultsProvisioner.zipformerModels().size,
        )
    }
}
