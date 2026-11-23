package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaSttModelSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseAssetAttributionCatalogTest {
    @Test
    fun releaseListHasOneEntryPerReleaseAssetAndNoPerFileDuplicates() {
        val entries = ReleaseAssetAttributionCatalog.entries
        val ids = entries.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(
            KernelModel.entries.filterNot { it.name.startsWith("SHERPA_") }.map { "model:${it.name}" }.toSet(),
            ids.filter { it.startsWith("model:") }.toSet(),
        )
        assertEquals(
            setOf("stt:${SherpaSttModelSpec.WHISPER.engine.name}", "stt:AndroidNative"),
            ids.filter { it.startsWith("stt:") }.toSet(),
        )
        assertEquals(setOf("piper:${SherpaPiperVoice.CoriHigh.name}"), ids.filter { it.startsWith("piper:") }.toSet())
        assertEquals(emptySet<String>(), ids.filter { it.startsWith("kokoro:") }.toSet())
        assertFalse("stt:Vosk" in ids)
    }

    @Test
    fun releaseExposedEntriesDisclosePurposeSourceLicenceStatusAndLineage() {
        val releaseEntries = ReleaseAssetAttributionCatalog.entries.filterNot {
            it.releaseStatus.contains("excluded", ignoreCase = true) ||
                it.releaseStatus.contains("debug", ignoreCase = true) ||
                it.releaseStatus.contains("post-launch", ignoreCase = true)
        }

        assertFalse(releaseEntries.isEmpty())
        assertTrue(ReleaseAssetAttributionCatalog.entries.all { it.purposeCategory.isNotBlank() })
        assertTrue(releaseEntries.all { it.purposeCategory.isNotBlank() })
        assertEquals("Conversation model", releaseEntries.single { it.id == "model:GEMMA_4_E2B" }.purposeCategory)
        assertEquals("Memory search embedding model", releaseEntries.single { it.id == "model:ARCTIC_EMBED_M_V1_5" }.purposeCategory)
        assertEquals("Speech recognition (STT) service", releaseEntries.single { it.id == "stt:AndroidNative" }.purposeCategory)
        assertEquals("Speech synthesis (TTS) service", releaseEntries.single { it.id == "tts:AndroidTts" }.purposeCategory)
        assertTrue(releaseEntries.all { it.releaseStatus.isNotBlank() })
        assertTrue(releaseEntries.all { it.sourceText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
        assertTrue(releaseEntries.all { it.licenceText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
        assertTrue(releaseEntries.all { it.lineageText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
    }

    @Test
    fun wakeWordAssetsAreBundledInReleaseUnderCcByNcSa() {
        val wakeWordEntries = ReleaseAssetAttributionCatalog.entries.filter { it.id.startsWith("wakeword:") }
        assertEquals(3, wakeWordEntries.size)
        assertTrue(wakeWordEntries.all { it.releaseStatus == "Bundled in release · CC BY-NC-SA 4.0" })
        assertTrue(wakeWordEntries.all { it.sourceText.isNotBlank() && it.lineageText.isNotBlank() })
        assertTrue(wakeWordEntries.all { it.licenceText.contains("CC BY-NC-SA 4.0") })
    }

    @Test
    fun whisperVerifierFamilyIsReleaseVisibleAndDownloadedWithHeyJandal() {
        val whisper = ReleaseAssetAttributionCatalog.entries.filter {
            it.id == "stt:${SherpaSttModelSpec.WHISPER.engine.name}"
        }
        assertEquals(1, whisper.size)
        assertTrue(whisper.all { it.releaseStatus == "Downloaded with Hey Jandal · release-visible" })
        assertTrue(whisper.all { it.licenceText.contains("Apache-2.0") })
    }

    @Test
    fun sherpaSttNonCoriPiperAndKokoroAreHiddenWhileCoriAndSherpaEnginesAreReleaseVisible() {
        val entries = ReleaseAssetAttributionCatalog.entries
        assertTrue(entries.none { it.isDebugOnly })
        assertTrue(entries.none { it.id.startsWith("kokoro:") || it.id == "tts:KokoroExperimental" })
        assertTrue(entries.none { it.id.startsWith("piper:") && it.id != "piper:CoriHigh" })

        val released = entries.filter {
            it.id == "piper:CoriHigh" || it.id == "tts:SherpaExperimental" || it.id == "tts:InflectMicroExperimental"
        }
        assertEquals(3, released.size)
        assertTrue(released.none { it.releaseStatus.contains("excluded", ignoreCase = true) })
    }
}
