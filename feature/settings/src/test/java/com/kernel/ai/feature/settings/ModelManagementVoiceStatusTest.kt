package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.model.availability.ModelRoleCopy
import com.kernel.ai.core.voice.Asr4AllInstall
import com.kernel.ai.core.voice.SherpaSttModelSpec
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import com.kernel.ai.core.voice.kernelModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelManagementVoiceStatusTest {

    @Test
    fun `android tts is reported as built in whatever the sherpa pack state`() {
        assertEquals(VoiceSectionStatus.BUILT_IN, voiceOutputStatusOf(VoiceOutputEngine.AndroidTts, null))
    }

    @Test
    fun `sherpa engine without its pack is not downloaded`() {
        assertEquals(VoiceSectionStatus.NOT_DOWNLOADED, voiceOutputStatusOf(VoiceOutputEngine.SherpaExperimental, null))
    }

    @Test
    fun `other engines are not claimed as cori status`() {
        assertEquals(VoiceSectionStatus.OTHER_ENGINE, voiceOutputStatusOf(VoiceOutputEngine.KokoroExperimental, null))
    }

    @Test
    fun `android native speech is built in whatever the asr4all files`() {
        assertEquals(
            VoiceSectionStatus.BUILT_IN,
            speechStatusOf(VoiceInputEngine.AndroidNative, emptyMap(), Asr4AllSize.M),
        )
    }

    @Test
    fun `automatic speech needs the asr4all files, not the hey jandal verifier`() {
        val m = Asr4AllInstall.models(Asr4AllSize.M)
        assertEquals(VoiceSectionStatus.READY, speechStatusOf(VoiceInputEngine.Auto, downloaded(m), Asr4AllSize.M))
        assertEquals(
            VoiceSectionStatus.NOT_DOWNLOADED,
            speechStatusOf(VoiceInputEngine.Auto, downloaded(m.drop(1)), Asr4AllSize.M),
        )
    }

    @Test
    fun `asr4all speech checks the files of the effective size`() {
        val m = downloaded(Asr4AllInstall.models(Asr4AllSize.M))
        assertEquals(VoiceSectionStatus.READY, speechStatusOf(VoiceInputEngine.Asr4all, m, Asr4AllSize.M))
        assertEquals(VoiceSectionStatus.NOT_DOWNLOADED, speechStatusOf(VoiceInputEngine.Asr4all, m, Asr4AllSize.L))
    }

    @Test
    fun `a sherpa engine reports only its own spec files`() {
        val whisper = downloaded(SherpaSttModelSpec.WHISPER.kernelModels())
        assertEquals(VoiceSectionStatus.READY, speechStatusOf(VoiceInputEngine.SherpaWhisper, whisper, Asr4AllSize.M))
        assertEquals(
            VoiceSectionStatus.NOT_DOWNLOADED,
            speechStatusOf(VoiceInputEngine.SherpaParaformer, whisper, Asr4AllSize.M),
        )
    }

    @Test
    fun `the speech row names the asr4all size it runs, for the engines that use it`() {
        assertTrue(speechDetailOf(VoiceInputEngine.Auto, Asr4AllSize.M)!!.startsWith("asr4all M · "))
        assertTrue(speechDetailOf(VoiceInputEngine.Asr4all, Asr4AllSize.L)!!.startsWith("asr4all L · "))
        assertNull(speechDetailOf(VoiceInputEngine.AndroidNative, Asr4AllSize.M))
        assertNull(speechDetailOf(VoiceInputEngine.SherpaWhisper, Asr4AllSize.M))
    }

    @Test
    fun `a stored L size is shown as M on a tier that cannot run L`() {
        val effective = Asr4AllInstall.effectiveSize(Asr4AllSize.L, HardwareTier.LOW_POWER)
        assertEquals(Asr4AllSize.M, effective)
        assertEquals(
            "asr4all M · ${ModelRoleCopy.sizeLabel(Asr4AllInstall.models(Asr4AllSize.M).sumOf { it.approxSizeBytes })}",
            speechDetailOf(VoiceInputEngine.Asr4all, effective),
        )
    }

    private fun downloaded(models: List<KernelModel>): Map<KernelModel, DownloadState> =
        models.associateWith { DownloadState.Downloaded("/models/${it.name}") }
}
