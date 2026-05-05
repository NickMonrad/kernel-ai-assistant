package com.kernel.ai.core.voice

import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SherpaVoicePackFilesTest {

    @Test
    fun `normalize extracted pack renames raw sherpa files to stable names`() {
        val dir = createTempDirectory("sherpa-pack-test").toFile()
        dir.resolve("en_GB-jenny_dioco-medium.onnx").writeText("onnx")
        dir.resolve("en_GB-jenny_dioco-medium.onnx.json").writeText("{}")
        dir.resolve(SHERPA_TOKENS_FILE).writeText("a 1\n")
        dir.resolve(SHERPA_ESPEAK_DATA_DIR).mkdirs()

        normalizeExtractedSherpaVoicePack(dir)

        assertTrue(dir.resolve(SHERPA_MODEL_ONNX_FILE).isFile)
        assertTrue(dir.resolve(SHERPA_MODEL_JSON_FILE).isFile)
        assertTrue(hasRequiredSherpaVoicePackFiles(dir))

        dir.deleteRecursively()
    }

    @Test
    fun `required pack check fails when canonical model file is missing`() {
        val dir = createTempDirectory("sherpa-pack-missing").toFile()
        dir.resolve(SHERPA_TOKENS_FILE).writeText("a 1\n")
        dir.resolve(SHERPA_ESPEAK_DATA_DIR).mkdirs()

        assertFalse(hasRequiredSherpaVoicePackFiles(dir))

        dir.deleteRecursively()
    }
}
