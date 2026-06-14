package com.kernel.ai.core.inference.download

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [succeededWorkerDownloadState] — the filesystem-backed helper
 * that determines correct [DownloadState] for a SUCCEEDED WorkManager worker.
 *
 * A stale SUCCEEDED record must NOT cause a model to appear downloaded
 * when the file is missing or empty.
 */
class SucceededWorkerDownloadStateTest {

    private lateinit var tempDir: File
    private lateinit var ctx: Context

    @BeforeEach
    fun setUp() {
        tempDir = java.nio.file.Files.createTempDirectory("dl_succeeded_state").toFile()
        ctx = mockk<Context> {
            every { filesDir } returns tempDir
            every { getExternalFilesDir(any()) } returns File(tempDir, "external")
            every { getDatabasePath(any()) } returns File(tempDir, "test.db")
            every { cacheDir } returns File(tempDir, "cache")
        }
        // Create the external files dir since localFile uses it
        File(tempDir, "external/models").mkdirs()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `succeeded with missing file maps to NotDownloaded`() = runBlocking {
        val result = succeededWorkerDownloadState(KernelModel.GEMMA_4_E4B, ctx)
        assertEquals(DownloadState.NotDownloaded, result)
    }

    @Test
    fun `succeeded with present non-empty file maps to Downloaded`() = runBlocking {
        val file = KernelModel.GEMMA_4_E4B.localFile(ctx)
        file.parentFile?.mkdirs()
        file.writeText("model content")

        val result = succeededWorkerDownloadState(KernelModel.GEMMA_4_E4B, ctx)
        assertTrue(result is DownloadState.Downloaded)
        assertEquals(file.absolutePath, (result as DownloadState.Downloaded).localPath)
    }

    @Test
    fun `succeeded with empty file maps to NotDownloaded`() = runBlocking {
        val file = KernelModel.GEMMA_4_E4B.localFile(ctx)
        file.parentFile?.mkdirs()
        file.createNewFile()
        assertTrue(file.exists())
        assertEquals(0, file.length())

        val result = succeededWorkerDownloadState(KernelModel.GEMMA_4_E4B, ctx)
        assertEquals(DownloadState.NotDownloaded, result)
    }

    @Test
    fun `succeeded with E2B file present maps to Downloaded`() = runBlocking {
        val file = KernelModel.GEMMA_4_E2B.localFile(ctx)
        file.parentFile?.mkdirs()
        file.writeText("e2b content")

        val result = succeededWorkerDownloadState(KernelModel.GEMMA_4_E2B, ctx)
        assertTrue(result is DownloadState.Downloaded)
        assertEquals(file.absolutePath, (result as DownloadState.Downloaded).localPath)
    }

    @Test
    fun `different models are independent`() = runBlocking {
        // E4B file missing, E2B file present
        val e2bFile = KernelModel.GEMMA_4_E2B.localFile(ctx)
        e2bFile.parentFile?.mkdirs()
        e2bFile.writeText("e2b content")

        assertEquals(
            DownloadState.NotDownloaded,
            succeededWorkerDownloadState(KernelModel.GEMMA_4_E4B, ctx)
        )
        assertTrue(
            succeededWorkerDownloadState(KernelModel.GEMMA_4_E2B, ctx) is DownloadState.Downloaded
        )
    }

    @Test
    fun `file deleted after being present maps to NotDownloaded`() = runBlocking {
        val file = KernelModel.GEMMA_4_E4B.localFile(ctx)
        file.parentFile?.mkdirs()
        file.writeText("content")
        assertTrue(
            succeededWorkerDownloadState(KernelModel.GEMMA_4_E4B, ctx) is DownloadState.Downloaded
        )

        // Simulate manual delete
        file.delete()
        assertFalse(file.exists())

        val result = succeededWorkerDownloadState(KernelModel.GEMMA_4_E4B, ctx)
        assertEquals(DownloadState.NotDownloaded, result)
    }
}
