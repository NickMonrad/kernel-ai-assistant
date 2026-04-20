package com.kernel.ai.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val context: Context = mockk()
    private val packageManager: PackageManager = mockk()
    private val packageInfo: PackageInfo = mockk()
    private val dataStore: DataStore<Preferences> = mockk()
    private val cacheDir: File = mockk()

    private lateinit var viewModel: AboutViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.kernel.ai.test"
        every { context.cacheDir } returns cacheDir
        every { packageInfo.versionName } returns "1.2.3"
        every { packageManager.getPackageInfo("com.kernel.ai.test", 0) } returns packageInfo
        every { cacheDir.absolutePath } returns "/data/user/0/com.kernel.ai.test/cache"
        every { cacheDir.mkdirs() } returns true
        every { dataStore.data } returns kotlinx.coroutines.flow.flowOf(emptyMap<Preferences.Key<*>, Any>())
        viewModel = AboutViewModel(context, dataStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `exportLogs generates filename with version and timestamp`() = testScope.runTest {
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }
        every { cacheDir.listFiles() } returns emptyArray()

        val filenameSlot = slot<String>()
        every { File(any(), capture(filenameSlot)) } answers {
            mockk<File>(relaxed = true) {
                every { absolutePath } returns "/data/user/0/com.kernel.ai.test/cache/${filenameSlot.captured}"
                every { writeText(any()) } returns Unit
                every { exists() } returns true
                every { canWrite() } returns true
            }
        }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()

        val filename = filenameSlot.captured
        assertTrue(filename.startsWith("kernel_debug_log_1.2.3_"))
        assertTrue(filename.endsWith(".txt"))
        assertEquals(ExportState.Loading, viewModel.uiState.value.exportState)

        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Ready)
    }

    @Test
    fun `exportLogs handles missing package info gracefully`() = testScope.runTest {
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }
        every { cacheDir.listFiles() } returns emptyArray()

        every { packageManager.getPackageInfo("com.kernel.ai.test", 0) } throws
            PackageManager.NameNotFoundException("not found")

        val filenameSlot = slot<String>()
        every { File(any(), capture(filenameSlot)) } answers {
            mockk<File>(relaxed = true) {
                every { absolutePath } returns "/data/user/0/com.kernel.ai.test/cache/${filenameSlot.captured}"
                every { writeText(any()) } returns Unit
                every { exists() } returns true
                every { canWrite() } returns true
            }
        }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Ready)

        val filename = filenameSlot.captured
        assertTrue(filename.startsWith("kernel_debug_log_unknown_"))
    }

    @Test
    fun `exportLogs sets error state on exception`() = testScope.runTest {
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()

        every { cacheDir.listFiles() } returns emptyArray()
        every { File(any(), any()) } answers {
            mockk<File>(relaxed = true) {
                every { writeText(any()) } throws RuntimeException("disk full")
            }
        }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Error)
        assertEquals("disk full", (state as ExportState.Error).message)
    }

    @Test
    fun `exportLogs generates unique filenames per timestamp`() = testScope.runTest {
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }
        every { cacheDir.listFiles() } returns emptyArray()

        val filenames = mutableListOf<String>()
        every { File(any(), any()) } answers {
            val filename = it.invocation.args[1] as String
            filenames.add(filename)
            mockk<File>(relaxed = true) {
                every { absolutePath } returns "/data/user/0/com.kernel.ai.test/cache/$filename"
                every { writeText(any()) } returns Unit
                every { exists() } returns true
                every { canWrite() } returns true
            }
        }

        repeat(3) {
            viewModel.exportLogs()
            testDispatcher.scheduler.advanceUntilIdle()
            testDispatcher.scheduler.advanceTimeBy(1.seconds)
            viewModel.clearExportState()
        }

        assertEquals(3, filenames.distinct().size)
    }

    @Test
    fun `exportLogs creates share intent with correct action`() = testScope.runTest {
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        val intentCaptor = slot<Intent>()
        every { Intent.createChooser(capture(intentCaptor), any()) } answers { firstArg<Intent>() }
        every { cacheDir.listFiles() } returns emptyArray()

        every { File(any(), any()) } answers {
            mockk<File>(relaxed = true) {
                every { absolutePath } returns "/data/user/0/com.kernel.ai.test/cache/test.txt"
                every { writeText(any()) } returns Unit
                every { exists() } returns true
                every { canWrite() } returns true
            }
        }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        val shareIntent = intentCaptor.captured
        assertEquals(Intent.ACTION_SEND, shareIntent.action)
        assertEquals("text/plain", shareIntent.type)
    }

    @Test
    fun `exportState transitions through Loading to Ready`() = testScope.runTest {
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }
        every { cacheDir.listFiles() } returns emptyArray()

        every { File(any(), any()) } answers {
            mockk<File>(relaxed = true) {
                every { absolutePath } returns "/data/user/0/com.kernel.ai.test/cache/test.txt"
                every { writeText(any()) } returns Unit
                every { exists() } returns true
                every { canWrite() } returns true
            }
        }

        val states = mutableListOf<ExportState>()

        val collectJob = testScope.launch {
            viewModel.uiState.collect { states.add(it.exportState) }
        }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(states.any { it is ExportState.Loading })

        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        assertTrue(states.any { it is ExportState.Ready })

        collectJob.cancelAndJoin()
    }

    @Test
    fun `clearExportState resets to Idle`() = testScope.runTest {
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }
        every { cacheDir.listFiles() } returns emptyArray()

        every { File(any(), any()) } answers {
            mockk<File>(relaxed = true) {
                every { absolutePath } returns "/data/user/0/com.kernel.ai.test/cache/test.txt"
                every { writeText(any()) } returns Unit
                every { exists() } returns true
                every { canWrite() } returns true
            }
        }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        assertEquals(ExportState.Ready::class, viewModel.uiState.value.exportState::class)

        viewModel.clearExportState()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ExportState.Idle, viewModel.uiState.value.exportState)
    }
}
