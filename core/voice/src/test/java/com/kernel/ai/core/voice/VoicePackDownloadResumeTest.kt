package com.kernel.ai.core.voice

import java.io.IOException
import java.net.HttpURLConnection
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VoicePackDownloadResumeTest {

    @Test
    fun `full response replaces partial file when server ignores range`() {
        val directory = createTempDirectory("voice-pack-full-response").toFile()
        try {
            val partialFile = directory.resolve("voice.tar.bz2.tmp")
            partialFile.writeText("partial")

            val (output, initialBytes) = openVoicePackDownloadOutput(
                partialFile,
                HttpURLConnection.HTTP_OK,
                partialFile.length(),
            )
            output.use { it.write("complete".toByteArray()) }

            assertEquals(0L, initialBytes)
            assertArrayEquals("complete".toByteArray(), partialFile.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `partial response appends to partial file`() {
        val directory = createTempDirectory("voice-pack-partial-response").toFile()
        try {
            val partialFile = directory.resolve("voice.tar.bz2.tmp")
            partialFile.writeText("part")

            val (output, initialBytes) = openVoicePackDownloadOutput(
                partialFile,
                HttpURLConnection.HTTP_PARTIAL,
                partialFile.length(),
            )
            output.use { it.write("ial".toByteArray()) }

            assertEquals(4L, initialBytes)
            assertArrayEquals("partial".toByteArray(), partialFile.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `input stream acquisition failure leaves partial archive untouched`() {
        val directory = createTempDirectory("voice-pack-input-failure").toFile()
        try {
            val partialFile = directory.resolve("voice.tar.bz2.tmp")
            partialFile.writeText("partial")

            assertThrows(IOException::class.java) {
                runBlocking {
                    withVoicePackDownloadStreams(
                        openInputStream = { throw IOException("connection closed") },
                        tmpFile = partialFile,
                        responseCode = HttpURLConnection.HTTP_OK,
                        resumeFrom = partialFile.length(),
                    ) { _, _, _ -> error("Stream consumer must not run") }
                }
            }

            assertEquals("partial", partialFile.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
