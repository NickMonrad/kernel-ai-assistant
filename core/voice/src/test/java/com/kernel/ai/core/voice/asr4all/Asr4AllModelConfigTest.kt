package com.kernel.ai.core.voice.asr4all

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Asr4AllModelConfigTest {
    private val temporaryDirectories = mutableListOf<File>()

    @AfterEach
    fun cleanup() {
        temporaryDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `M export files parse into the published geometry`() {
        val config = Asr4AllTestData.configM()

        assertEquals("asr_medium6k_v2", config.codename)
        assertEquals(16000, config.sampleRate)
        assertEquals(257, config.vocabSize)
        assertEquals(256, config.blankId)
        assertEquals(512, config.hiddenDim)
        assertEquals("stream_c16r4", config.defaultTier)
        assertEquals(
            listOf("stream_c16r4", "stream_c32r8", "stream_c64r16", "stream_c128r24", "stream_c256r32", "stream_c512r8"),
            config.tiers.keys.toList(),
        )
        val c16 = config.tier(null)
        assertEquals(Asr4AllTier("stream_c16r4", 16, 22, 38, 24960, 10240, 16, "pcec_c16", "pcec_c16_flush"), c16)
        val c512 = config.tier("stream_c512r8")
        assertEquals(512, c512.commitFrames)
        assertEquals(355200, c512.windowSamples)
        assertEquals("pcec_c64", c512.pcecMethod)
        assertEquals(64, c512.pcecRung)
        assertEquals(16, config.pcec.lagTokens)
        assertEquals(2, config.pcec.slotsPerToken)
        assertEquals(6149, config.pcec.blankId)
        assertEquals(listOf(6144, 6145, 6146, 6147, 6148), with(config.pcec) { listOf(capId, allCapsId, periodId, commaId, questionId) })
        assertEquals(256, config.pcec.padInputId)
        assertEquals(9, config.pcec.pauseBlackout)
    }

    @Test
    fun `S files opened as M are rejected`() {
        val e = assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(Asr4AllTestData.modelDir("s"), Asr4AllSize.M) }
        assertTrue(e.message!!.contains("not a M"), e.message)
    }

    @Test
    fun `metadata from a different size is rejected`() {
        // M config, geometry and preprocessor files with S's metadata.json, opened as either size.
        val dir = copyOf("m")
        Asr4AllTestData.modelDir("s").resolve(Asr4AllTestData.metadataPath)
            .copyTo(dir.resolve(Asr4AllTestData.metadataPath), overwrite = true)

        assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(dir, Asr4AllSize.M) }
        val asS = assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(dir, Asr4AllSize.S) }
        assertTrue(asS.message!!.contains("different sizes"), asS.message)
    }

    @Test
    fun `geometry from a different size is rejected`() {
        val dir = copyOf("m")
        Asr4AllTestData.modelDir("s").resolve("release_geom.json").copyTo(dir.resolve("release_geom.json"), overwrite = true)

        val e = assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(dir, Asr4AllSize.M) }
        assertTrue(e.message!!.contains("geometry differs"), e.message)
    }

    @Test
    fun `unknown tier is a config error`() {
        assertThrows<Asr4AllConfigException> { Asr4AllTestData.configM().tier("stream_c8r2") }
    }

    @Test
    fun `audio format must match the preprocessor config`() {
        val dir = copyOf("m")
        val pre = dir.resolve("preprocessor_config.json")
        pre.writeText(pre.readText().replace("\"sampling_rate\": 16000", "\"sampling_rate\": 8000"))

        val e = assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(dir, Asr4AllSize.M) }
        assertTrue(e.message!!.contains("sampling_rate"), e.message)
    }

    @Test
    fun `missing and malformed files are config errors`() {
        val missing = copyOf("m").also { it.resolve("config.json").delete() }
        assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(missing, Asr4AllSize.M) }

        val truncated = copyOf("m").also { it.resolve(Asr4AllTestData.metadataPath).writeText("{\"schema_version\": 1}") }
        assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(truncated, Asr4AllSize.M) }

        val notJson = copyOf("m").also { it.resolve("release_geom.json").writeText("not json") }
        assertThrows<Asr4AllConfigException> { Asr4AllModelConfig.load(notJson, Asr4AllSize.M) }
    }

    private fun copyOf(size: String): File {
        val dir = Files.createTempDirectory("asr4all-config").toFile().also(temporaryDirectories::add)
        Asr4AllTestData.modelDir(size).copyRecursively(dir, overwrite = true)
        return dir
    }
}
