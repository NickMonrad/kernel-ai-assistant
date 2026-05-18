package com.kernel.ai.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import com.kernel.ai.core.voice.VoiceOutputPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val context: Context = mockk()
    private val applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_DEBUGGABLE }
    private val packageManager: PackageManager = mockk()
    private val packageInfo = PackageInfo().apply { versionName = "1.2.3" }
    private lateinit var cacheDir: File
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk(relaxed = true)
    private val preferencesState = MutableStateFlow<Preferences>(emptyPreferences())
    private val dataStore: DataStore<Preferences> = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = preferencesState

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(preferencesState.value)
            preferencesState.value = updated
            return updated
        }
    }

    private lateinit var viewModel: AboutViewModel

    @BeforeEach
    fun setUp() {
        cacheDir = Files.createTempDirectory("kernel-test-cache").toFile()
        Dispatchers.setMain(testDispatcher)
        every { context.packageManager } returns packageManager
        every { context.applicationInfo } returns applicationInfo
        every { context.packageName } returns "com.kernel.ai.test"
        every { context.cacheDir } returns cacheDir
        every { packageManager.getPackageInfo("com.kernel.ai.test", 0) } returns packageInfo
        preferencesState.value = emptyPreferences()
        viewModel = AboutViewModel(context, dataStore, voiceOutputPreferences)
        viewModel.ioDispatcher = testDispatcher
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
    }

    /** Stubs Runtime.getRuntime().exec() to return empty log output, avoiding real logcat calls. */
    private fun stubRuntime() {
        mockkStatic(Runtime::class)
        val mockProcess = mockk<Process>()
        every { Runtime.getRuntime() } returns mockk(relaxed = true) {
            every { exec(any<Array<String>>()) } returns mockProcess
        }
        every { mockProcess.inputStream } returns "".byteInputStream()
        every { mockProcess.errorStream } returns "".byteInputStream()
        every { mockProcess.waitFor() } returns 0
    }

    @Test
    fun `exportLogs generates filename with version and timestamp`() = testScope.runTest {
        stubRuntime()
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Ready)

        val logFile = cacheDir.listFiles()?.firstOrNull { it.name.endsWith(".txt") }
        assertTrue(logFile != null, "Expected a log file to be written to cacheDir")
        assertTrue(logFile!!.name.startsWith("kernel_debug_log_1.2.3_"))
        assertTrue(logFile.name.endsWith(".txt"))
    }

    @Test
    fun `exportLogs handles missing package info gracefully`() = testScope.runTest {
        stubRuntime()
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }
        every { packageManager.getPackageInfo("com.kernel.ai.test", 0) } throws
            PackageManager.NameNotFoundException("not found")

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Ready)

        val logFile = cacheDir.listFiles()?.firstOrNull { it.name.endsWith(".txt") }
        assertTrue(logFile != null, "Expected a log file to be written to cacheDir")
        assertTrue(logFile!!.name.startsWith("kernel_debug_log_unknown_"))
    }

    @Test
    fun `exportLogs sets error state on exception`() = testScope.runTest {
        stubRuntime()
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } throws RuntimeException("disk full")

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Error)
        assertEquals("disk full", (state as ExportState.Error).message)
    }

    @Test
    fun `exportLogs generates unique filenames per timestamp`() = testScope.runTest {
        stubRuntime()
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }

        repeat(3) {
            viewModel.exportLogs()
            testDispatcher.scheduler.advanceUntilIdle()
            testDispatcher.scheduler.advanceTimeBy(1.seconds)
            viewModel.clearExportState()
        }

        val logFiles = cacheDir.listFiles()?.filter { it.name.endsWith(".txt") } ?: emptyList()
        assertEquals(3, logFiles.map { it.name }.distinct().size)
    }

    @Test
    fun `exportLogs creates share intent with correct action`() = testScope.runTest {
        stubRuntime()
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        var createChooserCalled = false
        every { Intent.createChooser(any(), any()) } answers {
            createChooserCalled = true
            firstArg<Intent>()
        }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        assertTrue(createChooserCalled, "Expected Intent.createChooser to be called")
        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
    }

    @Test
    fun `exportState transitions through Loading to Ready`() = testScope.runTest {
        stubRuntime()
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }

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
        stubRuntime()
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }

        viewModel.exportLogs()
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)

        assertEquals(ExportState.Ready::class, viewModel.uiState.value.exportState::class)

        viewModel.clearExportState()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ExportState.Idle, viewModel.uiState.value.exportState)
    }

    @Test
    fun `verbose logging defaults to debug when preference absent`() = testScope.runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.verboseLogging)
    }

    @Test
    fun `setVerboseLogging updates ui state immediately`() = testScope.runTest {
        viewModel.setVerboseLogging(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.verboseLogging)
    }

}
