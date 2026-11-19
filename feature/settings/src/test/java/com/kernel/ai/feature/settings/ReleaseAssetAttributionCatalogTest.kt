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
    fun everyDownloadCatalogueAssetHasAnAttributionEntry() {
        val entries = ReleaseAssetAttributionCatalog.entries
        val ids = entries.map { it.id }.toSet()
        assertEquals(KernelModel.entries.map { "model:${it.name}" }.toSet(), ids.filter { it.startsWith("model:") }.toSet())
        assertEquals(
            SherpaSttModelSpec.ALL.values.flatMap { it.requiredFileNames }.toSet() + setOf("AndroidNative"),
            ids.filter { it.startsWith("stt:") }.map { it.removePrefix("stt:") }.toSet(),
        )
        assertEquals(SherpaPiperVoice.entries.map { "piper:${it.name}" }.toSet(), ids.filter { it.startsWith("piper:") }.toSet())
        assertEquals(SherpaKokoroVoice.entries.map { "kokoro:${it.name}" }.toSet(), ids.filter { it.startsWith("kokoro:") }.toSet())
        assertFalse("stt:Vosk" in ids)
    }

    @Test
    fun releaseExposedEntriesDiscloseSourceLicenceStatusAndLineage() {
        val releaseEntries = ReleaseAssetAttributionCatalog.entries.filterNot {
            it.releaseStatus.contains("excluded", ignoreCase = true) ||
                it.releaseStatus.contains("debug", ignoreCase = true) ||
                it.releaseStatus.contains("post-launch", ignoreCase = true)
        }

        assertFalse(releaseEntries.isEmpty())
        assertTrue(releaseEntries.all { it.releaseStatus.isNotBlank() })
        assertTrue(releaseEntries.all { it.sourceText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
        assertTrue(releaseEntries.all { it.licenceText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
        assertTrue(releaseEntries.all { it.lineageText != ReleaseAssetAttributionCatalog.UNDER_REVIEW })
    }

    @Test
    fun wakeWordAssetsAreExplicitlyDebugOnly() {
        val wakeWordEntries = ReleaseAssetAttributionCatalog.entries.filter { it.id.startsWith("wakeword:") }
        assertEquals(3, wakeWordEntries.size)
        assertTrue(wakeWordEntries.all { it.releaseStatus == "Debug-only · excluded from v0.1 release" })
        assertTrue(wakeWordEntries.all { it.sourceText.isNotBlank() && it.lineageText.isNotBlank() })
        assertTrue(
            wakeWordEntries.filter { it.id != "wakeword:hey-jandal" }
                .all { it.licenceText.contains("unresolved", ignoreCase = true) },
        )
    }

    @Test
    fun unresolvedSherpaPiperAndKokoroEntriesAreExplicitlyExcludedFromV01() {
        val speechRuntimeEntries = ReleaseAssetAttributionCatalog.entries.filter {
            it.id.startsWith("stt:") && it.id != "stt:AndroidNative" ||
                it.id.startsWith("piper:") ||
                it.id.startsWith("kokoro:") ||
                it.id == "tts:SherpaExperimental" ||
                it.id == "tts:InflectMicroExperimental" ||
                it.id == "tts:KokoroExperimental"
        }
        assertTrue(speechRuntimeEntries.isNotEmpty())
        assertTrue(speechRuntimeEntries.all {
            it.releaseStatus.contains("excluded", ignoreCase = true) ||
                it.releaseStatus.contains("post-launch", ignoreCase = true)
        })
    }
}
