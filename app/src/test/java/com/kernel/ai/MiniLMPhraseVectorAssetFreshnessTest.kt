package com.kernel.ai

import com.kernel.ai.core.skills.MiniLMIntentPhraseVectorAsset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.File

class MiniLMPhraseVectorAssetFreshnessTest {
    @Test
    fun `committed phrase vectors match the model vocab and phrase inputs`() {
        val assets = appAssetsDirectory()
        val model = assets.resolve("minilm-l6-v2-int8.tflite")
        val vocab = assets.resolve("vocab.txt")
        val phrases = assets.resolve("intent_phrases.json")
        val vectorAsset = assets.resolve(MiniLMIntentPhraseVectorAsset.FILE_NAME)
        val phraseBytes = phrases.readBytes()
        val phraseText = phraseBytes.toString(Charsets.UTF_8)
        val hashes = MiniLMIntentPhraseVectorAsset.SourceHashes(
            model = model.inputStream().use(MiniLMIntentPhraseVectorAsset::sha256),
            vocab = vocab.inputStream().use(MiniLMIntentPhraseVectorAsset::sha256),
            phrases = MiniLMIntentPhraseVectorAsset.sha256(phraseBytes),
        )

        val result = MiniLMIntentPhraseVectorAsset.load(
            vectorAsset.readBytes(),
            hashes,
            MiniLMIntentPhraseVectorAsset.phraseCounts(phraseText),
        )
        val loaded = assertInstanceOf(MiniLMIntentPhraseVectorAsset.LoadResult.Loaded::class.java, result)
        assertEquals(515, loaded.vectors.values.sumOf { it.size })
        assertEquals(39, loaded.vectors.size)
    }

    private fun appAssetsDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            workingDirectory.resolve("src/main/assets"),
            workingDirectory.resolve("app/src/main/assets"),
        ).firstOrNull { it.resolve("intent_phrases.json").isFile }
            ?: error("Cannot locate app/src/main/assets from ${workingDirectory.path}")
    }
}
