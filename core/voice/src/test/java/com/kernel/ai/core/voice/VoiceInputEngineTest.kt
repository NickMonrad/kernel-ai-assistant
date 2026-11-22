package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
    fun `legacy Sherpa values migrate to Auto`() {
        assertEquals(VoiceInputEngine.Auto, VoiceInputEngine.fromStorage("SherpaOnnx"))
        assertEquals(VoiceInputEngine.Auto, VoiceInputEngine.fromStorage("SherpaZipformer"))
    }

    @Test
    fun `stored engines round-trip`() {
        VoiceInputEngine.entries.forEach { engine ->
            assertEquals(engine, VoiceInputEngine.fromStorage(engine.name))
        }
    }


    @Test
    fun `provisioner downloads asr4all only for engines that use it`() {
        assertTrue(VoiceDefaultsProvisioner.needsAsr4all(VoiceInputEngine.Auto))
        assertTrue(VoiceDefaultsProvisioner.needsAsr4all(VoiceInputEngine.Asr4all))
        assertFalse(VoiceDefaultsProvisioner.needsAsr4all(VoiceInputEngine.SherpaParaformer))
        assertFalse(VoiceDefaultsProvisioner.needsAsr4all(VoiceInputEngine.AndroidNative))
    }


    @Test
    fun `legacy Vosk cleanup only targets voice folders holding the Vosk model`(@TempDir tmp: File) {
        val withVosk = File(tmp, "a").apply { File(this, "voice/vosk-model").mkdirs() }
        val otherVoice = File(tmp, "b").apply { File(this, "voice/something-else").mkdirs() }
        val noVoice = File(tmp, "c").apply { File(this, "models").mkdirs() }

        assertEquals(
            listOf(File(withVosk, "voice")),
            VoiceDefaultsProvisioner.legacyVoskDirsToDelete(listOf(withVosk, otherVoice, noVoice)),
        )
    }
    @Test
    fun `release catalogue contains Automatic, Android native and asr4all input`() {
        assertEquals(
            listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative, VoiceInputEngine.Asr4all),
            VoiceInputEngine.entriesForBuild(isRelease = true),
        )
    }

    @Test
    fun `release resolves missing, unknown, legacy and debug-only choices to Automatic`() {
        listOf(null, "not-an-engine", "Vosk", "SherpaOnnx", "SherpaZipformer", "SherpaWhisper").forEach { stored ->
            assertEquals(VoiceInputEngine.Auto, VoiceInputEngine.resolveForBuild(stored, isRelease = true), "stored=$stored")
        }
    }

    @Test
    fun `release keeps stored Automatic, Android native and asr4all choices`() {
        listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative, VoiceInputEngine.Asr4all).forEach { engine ->
            assertEquals(engine, VoiceInputEngine.resolveForBuild(engine.name, isRelease = true))
        }
    }

    @Test
    fun `debug keeps every stored engine`() {
        VoiceInputEngine.entries.forEach { engine ->
            assertEquals(engine, VoiceInputEngine.resolveForBuild(engine.name, isRelease = false))
        }
    }

    @Test
    fun `debug catalogue keeps existing speech engines`() {
        assertEquals(VoiceInputEngine.entries, VoiceInputEngine.entriesForBuild(isRelease = false))
    }
}
