package com.kernel.ai.core.voice

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InflectMicroModelSpecTest {
    private val temporaryDirectories = mutableListOf<File>()

    @AfterEach
    fun cleanup() {
        temporaryDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `model directory uses app-specific external models directory when available`() {
        val externalModelsDirectory = temporaryDirectory("inflect-external-models")
        val internalFilesDirectory = temporaryDirectory("inflect-internal-files")
        val context = mockk<Context> {
            every { getExternalFilesDir("models") } returns externalModelsDirectory
            every { filesDir } returns internalFilesDirectory
        }

        val actual = InflectMicroModelSpec.modelDirectory(context)

        assertEquals(externalModelsDirectory.canonicalFile, actual.canonicalFile)
        assertTrue(actual.isDirectory)
    }

    @Test
    fun `model directory falls back to internal models directory when external storage is unavailable`() {
        val internalFilesDirectory = temporaryDirectory("inflect-internal-files")
        val context = mockk<Context> {
            every { getExternalFilesDir("models") } returns null
            every { filesDir } returns internalFilesDirectory
        }
        val expected = File(internalFilesDirectory, "models")

        val actual = InflectMicroModelSpec.modelDirectory(context)

        assertEquals(expected.canonicalFile, actual.canonicalFile)
        assertTrue(actual.isDirectory)
    }

    private fun temporaryDirectory(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also(temporaryDirectories::add)
}
