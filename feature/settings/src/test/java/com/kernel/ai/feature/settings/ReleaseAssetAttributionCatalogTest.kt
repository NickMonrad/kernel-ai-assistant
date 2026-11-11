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
            SherpaSttModelSpec.ALL.values.flatMap { it.requiredFileNames }.toSet() + setOf("Vosk", "AndroidNative"),
            ids.filter { it.startsWith("stt:") }.map { it.removePrefix("stt:") }.toSet(),
        )
        assertEquals(SherpaPiperVoice.entries.map { "piper:${it.name}" }.toSet(), ids.filter { it.startsWith("piper:") }.toSet())
        assertEquals(SherpaKokoroVoice.entries.map { "kokoro:${it.name}" }.toSet(), ids.filter { it.startsWith("kokoro:") }.toSet())
    }

    @Test
    fun catalogueEntriesHaveExplicitReleaseStatusAndUnreviewedFieldsAreNamed() {
        val entries = ReleaseAssetAttributionCatalog.entries

        assertFalse(entries.isEmpty())
        assertTrue(entries.all { it.releaseStatus.isNotBlank() })
        assertTrue(entries.all { it.sourceText.isNotBlank() })
        assertTrue(entries.all { it.licenceText.isNotBlank() })
        assertTrue(entries.all { it.lineageText.isNotBlank() })
        assertTrue(entries.any { it.licenceText == ReleaseAssetAttributionCatalog.UNDER_REVIEW })
    }
}
