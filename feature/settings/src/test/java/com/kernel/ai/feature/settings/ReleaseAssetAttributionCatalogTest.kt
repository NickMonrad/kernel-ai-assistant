package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaSttModelSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseAssetAttributionCatalogTest {
    private val release = ReleaseAssetAttributionCatalog.entriesFor(isReleaseBuild = true)
    private val full = ReleaseAssetAttributionCatalog.entriesFor(isReleaseBuild = false)

    @Test
    fun eachAssetFamilyHasExactlyOneRowAndNoPerFileDuplicates() {
        val ids = full.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(1, ids.count { it == "stt:ASR4ALL" })
        assertEquals(1, ids.count { it == "model:ARCTIC_EMBED" })
        assertEquals(1, ids.count { it == "model:INFLECT_MICRO" })
        val familyMembers = KernelModel.entries.filter {
            it.name.startsWith("ASR4ALL_") || it.name.startsWith("ARCTIC_EMBED") || it.name.startsWith("INFLECT_")
        }.map { "model:${it.name}" }
        assertTrue(ids.none { it in familyMembers })
    }

    @Test
    fun gemmaModelsStaySeparateAndEveryOtherNonFamilyModelHasItsOwnRow() {
        val modelIds = full.map { it.id }.filter { it.startsWith("model:") }.toSet()
        assertTrue("model:GEMMA_4_E2B" in modelIds)
        assertTrue("model:GEMMA_4_E4B" in modelIds)
        assertTrue("model:MINI_LM" in modelIds)
        val covered = KernelModel.entries.filter {
            !it.name.startsWith("SHERPA_") && !it.name.startsWith("ASR4ALL_") &&
                !it.name.startsWith("ARCTIC_EMBED") && !it.name.startsWith("INFLECT_") &&
                !it.name.startsWith("KOKORO_")
        }.map { "model:${it.name}" }.toSet()
        assertTrue(covered.all { it in modelIds })
        assertEquals(1, full.count { it.id == "kokoro:pack" })
    }

    @Test
    fun releaseBuildHidesDebugOnlyRowsAndDebugBuildShowsTheFullInventory() {
        assertTrue(release.none { it.isDebugOnly })
        assertTrue(full.any { it.isDebugOnly })
        assertTrue(full.size > release.size)
        assertTrue(full.any { it.id.startsWith("kokoro:") || it.id == "tts:KokoroExperimental" })
        assertTrue(release.any { it.id == "kokoro:pack" })
        assertTrue(release.any { it.id == "tts:KokoroExperimental" })
        assertTrue(full.any { it.id == "piper:${SherpaPiperVoice.entries.first().name}" })
    }

    @Test
    fun releaseExposedEntriesDisclosePurposeSourceLicenceStatusAndLineage() {
        assertFalse(release.isEmpty())
        assertTrue(release.all { it.purposeCategory.isNotBlank() && it.releaseStatus.isNotBlank() })
        assertTrue(release.all { it.sourceText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
        assertTrue(release.all { it.licenceText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
        assertTrue(release.all { it.lineageText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
        assertEquals("Conversation model", release.single { it.id == "model:GEMMA_4_E2B" }.purposeCategory)
        assertEquals("Memory search embedding model and tokenizer", release.single { it.id == "model:ARCTIC_EMBED" }.purposeCategory)
        assertEquals("Speech recognition (STT) service", release.single { it.id == "stt:AndroidNative" }.purposeCategory)
        assertEquals("Speech synthesis (TTS) service", release.single { it.id == "tts:AndroidTts" }.purposeCategory)
    }

    @Test
    fun displayTextCarriesNoIssuePrPathOrHashReferences() {
        val internalReference = Regex("""#\d+|\bPR \d+|third_party|docs/|training/|SHA-256|\b[0-9a-f]{64}\b|owner decision""")
        val offending = full.flatMap { row ->
            listOf(row.name, row.purposeCategory, row.sourceText, row.licenceText, row.lineageText, row.releaseStatus)
                .filter { internalReference.containsMatchIn(it) }
                .map { "${row.id}: $it" }
        }
        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun wakeWordAssetsAreBundledInReleaseUnderCcByNcSa() {
        val wakeWordEntries = release.filter { it.id.startsWith("wakeword:") }
        assertEquals(3, wakeWordEntries.size)
        assertTrue(wakeWordEntries.all { it.releaseStatus == "Bundled in release · CC BY-NC-SA 4.0" })
        assertTrue(wakeWordEntries.all { it.sourceText.isNotBlank() && it.lineageText.isNotBlank() })
        assertTrue(wakeWordEntries.all { it.licenceText.contains("CC BY-NC-SA 4.0") })
    }

    @Test
    fun releaseVisibleRowsCarryNoUnresolvedLicenceWording() {
        val pendingWording = listOf("under review", "unresolved")
        val offending = release.flatMap { row ->
            listOf(row.sourceText, row.licenceText, row.lineageText, row.releaseStatus)
                .filter { text -> pendingWording.any { text.contains(it, ignoreCase = true) } }
                .map { "${row.id}: $it" }
        }
        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun whisperVerifierFamilyIsReleaseVisibleAndDownloadedWithHeyJandal() {
        val whisper = release.filter { it.id == "stt:${SherpaSttModelSpec.WHISPER.engine.name}" }
        assertEquals(1, whisper.size)
        assertEquals("Downloaded with Hey Jandal · release-visible", whisper.single().releaseStatus)
        assertTrue(whisper.single().licenceText.contains("Apache-2.0"))
        assertFalse(whisper.single().licenceText.contains("under review"))
        assertFalse(whisper.single().lineageText.contains("under review"))
    }

    @Test
    fun releaseShowsCoriSherpaAndKokoroEnginesButNoNonCoriPiper() {
        assertTrue(release.any { it.id == "kokoro:pack" })
        assertTrue(release.none { it.id.startsWith("piper:") && it.id != "piper:CoriHigh" })
        assertTrue(release.any { it.id == "piper:CoriHigh" })
        assertTrue(release.any { it.id == "tts:SherpaExperimental" })
        assertTrue(release.any { it.id == "tts:InflectMicroExperimental" })
        assertTrue(release.none { it.releaseStatus.contains("excluded", ignoreCase = true) })
    }
}
