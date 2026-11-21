package com.kernel.ai.core.voice

import android.content.Context
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Deterministic tests for the app-scoped asr4all size switch. Files live in a temp dir; the download
 * manager is a mock whose [DownloadState] flow the test drives.
 *
 * Switch jobs run on the test's foreground scope, so [advanceUntilIdle] drains them. The `start()`
 * collectors run forever, so those tests use `backgroundScope` and drive it with [runCurrent].
 */
class Asr4allSizeSwitcherTest {
    @TempDir lateinit var tempDir: File

    private val stored = MutableStateFlow(Asr4AllSize.M)
    private val pending = MutableStateFlow<Asr4AllSize?>(null)
    private val downloads = MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
    private val context = mockk<Context>(relaxed = true)
    private val prefs = mockk<VoiceInputPreferences>(relaxed = true)
    private val detector = mockk<HardwareProfileDetector>(relaxed = true)
    private val manager = mockk<ModelDownloadManager>(relaxed = true)
    private val controller = mockk<Asr4allVoiceInputController>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { context.getExternalFilesDir(any()) } returns tempDir
        every { context.filesDir } returns tempDir
        every { detector.profile.tier } returns HardwareTier.FLAGSHIP
        every { prefs.asr4allSize } returns stored
        every { prefs.asr4allPendingSize } returns pending
        coEvery { prefs.setAsr4allSize(any()) } coAnswers { stored.value = firstArg() }
        coEvery { prefs.setAsr4allPendingSize(any()) } coAnswers { pending.value = firstArg() }
        every { manager.downloadStates } returns downloads
        every { manager.startDownload(any()) } just Runs
        every { manager.cancelDownload(any()) } just Runs
        every { manager.refreshState(any()) } answers {
            val model = firstArg<KernelModel>()
            val present = model.localFile(context).let { it.exists() && it.length() > 0 }
            val next = if (present) DownloadState.Downloaded("/models/${model.fileName}") else DownloadState.NotDownloaded
            downloads.update { current -> current + (model to next) }
        }
        coEvery { controller.withEngineReleased<Unit>(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
            Unit
        }
    }

    private fun CoroutineScope.switcher(): Asr4allSizeSwitcher =
        Asr4allSizeSwitcher(context, this, prefs, detector, manager, controller)

    private fun writeFiles(size: Asr4AllSize): List<File> =
        Asr4AllInstall.models(size).map { model ->
            model.localFile(context).also { it.parentFile.mkdirs(); it.writeText("x") }
        }

    private fun markDownloaded(size: Asr4AllSize) {
        downloads.update { current ->
            current + Asr4AllInstall.models(size).map { model ->
                model to DownloadState.Downloaded("/models/${model.fileName}")
            }
        }
    }

    @Test
    fun `a successful switch keeps the old size until the new one is verified then removes it`() = runTest {
        val oldFiles = writeFiles(Asr4AllSize.M)
        val switcher = switcher()

        switcher.select(Asr4AllSize.L)
        runCurrent()
        verify(atLeast = 1) { manager.startDownload(any()) }
        assertTrue(oldFiles.all { it.exists() }, "the active size stays installed while the new one downloads")
        assertEquals(Asr4AllSize.M, stored.value)

        val newFiles = writeFiles(Asr4AllSize.L)
        markDownloaded(Asr4AllSize.L)
        advanceUntilIdle()

        coVerify(exactly = 1) { controller.withEngineReleased<Unit>(any()) }
        assertEquals(Asr4AllSize.L, stored.value)
        assertNull(pending.value)
        assertNull(switcher.state.value.pendingSize)
        assertTrue(newFiles.all { it.exists() }, "the new size's files survive the sweep")
        assertTrue(oldFiles.none { it.exists() }, "the old size is removed once the new one is stored")
    }

    @Test
    fun `a failed download keeps the active size and reports the failure`() = runTest {
        val oldFiles = writeFiles(Asr4AllSize.M)
        val switcher = switcher()

        switcher.select(Asr4AllSize.L)
        runCurrent()
        downloads.update { current ->
            current + (Asr4AllInstall.models(Asr4AllSize.L).first() to DownloadState.Error("download failed"))
        }
        advanceUntilIdle()

        assertEquals(Asr4AllSize.M, stored.value)
        assertNull(pending.value)
        assertNull(switcher.state.value.pendingSize)
        assertEquals(Asr4AllSize.L, switcher.state.value.failure?.size)
        assertTrue(switcher.state.value.failure?.message?.contains("did not finish") == true)
        assertTrue(oldFiles.all { it.exists() }, "a failed download keeps the active model")
        coVerify(exactly = 0) { controller.withEngineReleased<Unit>(any()) }
    }

    @Test
    fun `cancelling a switch removes its partial download and keeps the active size`() = runTest {
        val oldFiles = writeFiles(Asr4AllSize.M)
        val switcher = switcher()

        switcher.select(Asr4AllSize.L)
        runCurrent()
        val partial = Asr4AllInstall.models(Asr4AllSize.L).first().localFile(context)
            .also { it.parentFile.mkdirs(); it.writeText("partial") }
        downloads.update { current ->
            current + Asr4AllInstall.models(Asr4AllSize.L).associateWith { DownloadState.Downloading() }
        }
        switcher.cancel()
        advanceUntilIdle()

        verify(exactly = Asr4AllInstall.models(Asr4AllSize.L).size) { manager.cancelDownload(any()) }
        assertFalse(partial.exists(), "the cancelled size's partial file is removed")
        assertTrue(oldFiles.all { it.exists() }, "a cancelled switch keeps the active model")
        assertEquals(Asr4AllSize.M, stored.value)
        assertNull(switcher.state.value.pendingSize)
        assertNull(switcher.state.value.failure)
    }

    @Test
    fun `a switch with no size installed stores the choice at once and removes an in-flight download of the old size`() = runTest {
        val switcher = switcher()
        val inFlight = Asr4AllInstall.models(Asr4AllSize.M).first().localFile(context)
            .also { it.parentFile.mkdirs(); it.writeText("partial") }
        downloads.update { current ->
            current + Asr4AllInstall.models(Asr4AllSize.M).associateWith { DownloadState.Downloading() }
        }

        switcher.select(Asr4AllSize.L)
        advanceUntilIdle()

        verify(exactly = 0) { manager.startDownload(any()) }
        coVerify(exactly = 1) { controller.withEngineReleased<Unit>(any()) }
        assertEquals(Asr4AllSize.L, stored.value)
        assertNull(switcher.state.value.pendingSize)
        verify(exactly = Asr4AllInstall.models(Asr4AllSize.M).size) { manager.cancelDownload(any()) }
        assertFalse(inFlight.exists(), "the in-flight download of the old size is removed")
    }

    @Test
    fun `a wedged engine release keeps the old size, stores nothing, and reports the failure`() = runTest {
        val oldFiles = writeFiles(Asr4AllSize.M)
        val newFiles = writeFiles(Asr4AllSize.L)
        markDownloaded(Asr4AllSize.L)
        coEvery { controller.withEngineReleased<Unit>(any()) } returns null
        val switcher = switcher()

        switcher.select(Asr4AllSize.L)
        advanceUntilIdle()

        assertEquals(Asr4AllSize.M, stored.value)
        coVerify(exactly = 0) { prefs.setAsr4allSize(any()) }
        assertNull(pending.value)
        assertNull(switcher.state.value.pendingSize)
        assertEquals(Asr4AllSize.L, switcher.state.value.failure?.size)
        assertTrue(oldFiles.all { it.exists() }, "a wedged capture must keep the model it still reads")
        assertTrue(newFiles.none { it.exists() }, "the unstored size is swept, so no orphan is left")
    }

    @Test
    fun `choosing the active size abandons a pending switch and removes its download`() = runTest {
        val oldFiles = writeFiles(Asr4AllSize.M)
        val switcher = switcher()

        switcher.select(Asr4AllSize.L)
        runCurrent()
        assertEquals(Asr4AllSize.L, switcher.state.value.pendingSize)
        switcher.select(Asr4AllSize.M)
        advanceUntilIdle()

        assertNull(switcher.state.value.pendingSize)
        assertNull(pending.value)
        assertEquals(Asr4AllSize.M, stored.value)
        coVerify(exactly = 0) { controller.withEngineReleased<Unit>(any()) }
        assertTrue(oldFiles.all { it.exists() })
    }

    @Test
    fun `switching back to a size removed by an earlier switch downloads it again`() = runTest {
        writeFiles(Asr4AllSize.M)
        writeFiles(Asr4AllSize.L)
        markDownloaded(Asr4AllSize.L)
        val switcher = switcher()
        switcher.select(Asr4AllSize.L)
        advanceUntilIdle()
        assertEquals(Asr4AllSize.L, stored.value)

        switcher.select(Asr4AllSize.M)
        runCurrent()
        verify(atLeast = 1) { manager.startDownload(any()) }
        markDownloaded(Asr4AllSize.M)
        advanceUntilIdle()

        assertEquals(Asr4AllSize.M, stored.value)
        assertNull(pending.value)
    }

    @Test
    fun `a delete that arrives during the commit deletes the size the commit stored`() = runTest {
        writeFiles(Asr4AllSize.M)
        val newFiles = writeFiles(Asr4AllSize.L)
        markDownloaded(Asr4AllSize.L)
        val gate = CompletableDeferred<Unit>()
        coEvery { controller.withEngineReleased<Unit>(any()) } coAnswers {
            gate.await() // the commit is parked inside the engine release
            firstArg<suspend () -> Unit>().invoke()
            Unit
        }
        val switcher = switcher()

        switcher.select(Asr4AllSize.L)
        runCurrent()
        val deletion = async { switcher.deleteActive() }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(deletion.await(), "the delete runs after the commit and its release succeeds")
        assertEquals(Asr4AllSize.L, stored.value)
        assertTrue(newFiles.none { it.exists() }, "the size the commit stored is the one deleted")
    }

    @Test
    fun `a stale download error from an earlier attempt does not fail a new switch`() = runTest {
        writeFiles(Asr4AllSize.M)
        downloads.update { current ->
            current + (Asr4AllInstall.models(Asr4AllSize.L).first() to DownloadState.Error("stale"))
        }
        val switcher = switcher()

        switcher.select(Asr4AllSize.L)
        runCurrent()
        assertNull(switcher.state.value.failure, "a stale Error must not settle this attempt")
        assertEquals(Asr4AllSize.L, switcher.state.value.pendingSize)

        markDownloaded(Asr4AllSize.L)
        advanceUntilIdle()
        assertEquals(Asr4AllSize.L, stored.value)
    }

    @Test
    fun `start resumes a switch that was pending when the process ended`() = runTest {
        writeFiles(Asr4AllSize.M)
        pending.value = Asr4AllSize.L
        val switcher = backgroundScope.switcher()

        switcher.start()
        runCurrent()
        verify(atLeast = 1) { manager.startDownload(any()) }
        markDownloaded(Asr4AllSize.L)
        runCurrent()

        assertEquals(Asr4AllSize.L, stored.value)
        assertNull(pending.value)
    }

    @Test
    fun `start drops a persisted pending size that already matches the stored size`() = runTest {
        pending.value = Asr4AllSize.M
        val switcher = backgroundScope.switcher()

        switcher.start()
        runCurrent()

        assertNull(pending.value)
        verify(exactly = 0) { manager.startDownload(any()) }
    }

    @Test
    fun `start sweeps an inactive size left on disk by an earlier process`() = runTest {
        val activeFiles = writeFiles(Asr4AllSize.M)
        val orphanFiles = writeFiles(Asr4AllSize.L)
        val switcher = backgroundScope.switcher()

        switcher.start()
        runCurrent()

        assertTrue(activeFiles.all { it.exists() }, "the active size is never swept")
        assertTrue(orphanFiles.none { it.exists() }, "the orphaned inactive size is removed")
    }

    @Test
    fun `a delete the engine release refuses removes nothing and reports false`() = runTest {
        val files = writeFiles(Asr4AllSize.M)
        coEvery { controller.withEngineReleased<Unit>(any()) } returns null
        val switcher = switcher()

        val deleted = switcher.deleteActive()

        assertFalse(deleted)
        assertTrue(files.all { it.exists() }, "a refused release deletes nothing")
    }
}
