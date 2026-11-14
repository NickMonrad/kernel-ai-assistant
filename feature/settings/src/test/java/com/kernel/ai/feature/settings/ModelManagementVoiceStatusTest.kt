package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `speech status needs every zipformer file`() {
        val all = ZIPFORMER_STT_MODELS.associateWith { DownloadState.Downloaded("/models/${it.name}") as DownloadState }
        assertEquals(VoiceSectionStatus.READY, speechStatusOf(all))
        val missingOne = all - ZIPFORMER_STT_MODELS.first()
        assertEquals(VoiceSectionStatus.NOT_DOWNLOADED, speechStatusOf(missingOne))
    }

    @Test
    fun `zipformer list is the four core spec files`() {
        assertEquals(4, ZIPFORMER_STT_MODELS.size)
        assertEquals(setOf(KernelModel.SHERPA_STT_ENCODER, KernelModel.SHERPA_STT_DECODER, KernelModel.SHERPA_STT_JOINER, KernelModel.SHERPA_STT_TOKENS), ZIPFORMER_STT_MODELS.toSet())
    }
}
