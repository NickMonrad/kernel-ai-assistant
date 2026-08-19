package com.kernel.ai.feature.settings

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.kernel.ai.core.voice.VoiceOutputPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import java.io.IOException
import java.io.InputStream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private val VALID_THREADTIME_LOG =
    "08-11 02:49:00.123  1234  5678 I KernelAI: test log entry\n" +
        "08-11 02:49:00.456  1234  5678 W KernelAI: second entry\n"

/** Representative opaque MagicOS/vendor payload (Honor HKS…HKE blob) — not real logcat. */
private const val OPAQUE_VENDOR_PAYLOAD =
    "HKS2026081113490000f3a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5f6a7b8c9d0e1f2a3b4c5HKE\n"

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val buildInfo = DiagnosticBuildInfo(
        versionName = "1.2.3",
        versionCode = 42,
        buildType = "debug",
        gitSha = "0123456789abcdef",
        buildTimestamp = "2026-08-09T12:00:00Z",
    )

    private val context: Context = mockk()
    private val applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_DEBUGGABLE }
    private val activityManager: ActivityManager = mockk()
    private lateinit var cacheDir: File
    private lateinit var filesDir: File
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
        filesDir = Files.createTempDirectory("kernel-test-files").toFile()
        Dispatchers.setMain(testDispatcher)
        every { context.applicationInfo } returns applicationInfo
        every { context.packageName } returns "com.kernel.ai.test"
        every { context.cacheDir } returns cacheDir
        every { context.filesDir } returns filesDir
        // Default: no exit history unless a test stubs ActivityManager explicitly.
        every { context.getSystemService(ActivityManager::class.java) } returns null
        preferencesState.value = emptyPreferences()
        viewModel = AboutViewModel(context, dataStore, voiceOutputPreferences)
        viewModel.ioDispatcher = testDispatcher
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        cacheDir.deleteRecursively()
        filesDir.deleteRecursively()
    }

    /** Stubs Runtime.getRuntime().exec() to return the given logcat output, avoiding real logcat calls. */
    private fun stubRuntime(
        logContent: String = VALID_THREADTIME_LOG,
        stderrText: String = "",
        exitCode: Int = 0,
    ) {
        mockkStatic(Runtime::class)
        val mockProcess = mockk<Process>()
        every { Runtime.getRuntime() } returns mockk(relaxed = true) {
            every { exec(any<Array<String>>()) } returns mockProcess
        }
        every { mockProcess.inputStream } answers { logContent.byteInputStream() }
        every { mockProcess.errorStream } answers { stderrText.byteInputStream() }
        every { mockProcess.waitFor() } returns exitCode
    }

    /** Stubs ActivityManager to return the given historical exit records (newest first). */
    private fun stubExitHistory(vararg records: ApplicationExitInfo) {
        every { context.getSystemService(ActivityManager::class.java) } returns activityManager
        every { activityManager.getHistoricalProcessExitReasons(any(), any(), any()) } returns records.toList()
    }

    /** Builds a mock ApplicationExitInfo record with deterministic values. */
    private fun mockExitInfo(
        reason: Int = ApplicationExitInfo.REASON_CRASH,
        timestamp: Long = 1_786_416_540_123L, // 2026-08-11T02:49:00.123Z
        processName: String = "com.kernel.ai.test",
        status: Int = 0,
        importance: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        description: String? = "Process crashed",
        pss: Long = 123_456,
        rss: Long = 65_432,
        pid: Int = 9999,
        trace: InputStream? = null,
    ): ApplicationExitInfo {
        val info = mockk<ApplicationExitInfo>()
        every { info.reason } returns reason
        every { info.timestamp } returns timestamp
        every { info.processName } returns processName
        every { info.status } returns status
        every { info.importance } returns importance
        every { info.description } returns description
        every { info.pss } returns pss
        every { info.rss } returns rss
        every { info.pid } returns pid
        every { info.traceInputStream } returns trace
        return info
    }

    private fun mockShare() {
        mockkStatic(FileProvider::class)
        mockkStatic(Intent::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk()
        every { Intent.createChooser(any(), any()) } answers { firstArg<Intent>() }
    }

    private fun runExport(buildInfo: DiagnosticBuildInfo = this.buildInfo) {
        viewModel.exportLogs(buildInfo)
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)
    }

    private fun latestExportFile(): File =
        cacheDir.listFiles()?.firstOrNull { it.name.endsWith(".txt") }
            ?: error("Expected an export file in cacheDir")

    private fun exportContent(): String = latestExportFile().readText()

    @Test
    fun `exportLogs generates filename with version and timestamp`() = testScope.runTest {
        stubRuntime()
        mockShare()

        runExport()

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Ready)

        val logFile = latestExportFile()
        assertTrue(logFile.name.startsWith("kernel_debug_log_1.2.3_"))
        assertTrue(logFile.name.endsWith(".txt"))
    }

    @Test
    fun `exportLogs sets error state on exception`() = testScope.runTest {
        stubRuntime()
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } throws RuntimeException("disk full")

        runExport()

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Error)
        assertEquals("disk full", (state as ExportState.Error).message)
    }

    @Test
    fun `exportLogs generates unique filenames per timestamp`() = testScope.runTest {
        stubRuntime()
        mockShare()

        repeat(3) {
            runExport()
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

        runExport()

        assertTrue(createChooserCalled, "Expected Intent.createChooser to be called")
        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
    }

    @Test
    fun `exportState transitions through Loading to Ready`() = testScope.runTest {
        stubRuntime()
        mockShare()

        viewModel.exportLogs(buildInfo)
        // Loading is set synchronously before the coroutine runs — check before advancing.
        assertTrue(viewModel.uiState.value.exportState is ExportState.Loading)

        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(1.seconds)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
    }

    @Test
    fun `clearExportState resets to Idle`() = testScope.runTest {
        stubRuntime()
        mockShare()

        runExport()
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

    @Test
    fun `blank logcat without exit history sets error state with ADB hint`() = testScope.runTest {
        // Simulates Honor MagicOS (and similar OEMs) where the OS SELinux policy silently
        // blocks logcat access for user apps — process exits 0 but stdout is empty.
        stubRuntime(logContent = "")

        runExport()

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Error, "Expected Error when no diagnostics are available")
        val message = (state as ExportState.Error).message
        assertTrue(message.contains("No useful diagnostics were available"), "Error should explain missing diagnostics")
        assertTrue(message.contains("adb logcat"), "Error should include ADB fallback hint")
    }

    @Test
    fun `blank logcat includes stderr detail in error message`() = testScope.runTest {
        stubRuntime(logContent = "", stderrText = "read: unexpected EOF!")

        runExport()

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Error)
        assertTrue((state as ExportState.Error).message.contains("read: unexpected EOF!"))
    }

    @Test
    fun `recent exit metadata is rendered`() = testScope.runTest {
        stubRuntime()
        stubExitHistory(mockExitInfo())
        mockShare()

        runExport()

        val content = exportContent()
        assertTrue(content.contains("Exit 1"))
        assertTrue(content.contains("Timestamp: 2026-08-11T02:49:00.123Z"))
        assertTrue(content.contains("Process name: com.kernel.ai.test"))
        assertTrue(content.contains("Reason: REASON_CRASH (4)"))
        assertTrue(content.contains("Status: 0"))
        assertTrue(content.contains("Importance: IMPORTANCE_FOREGROUND (100)"))
        assertTrue(content.contains("Description: Process crashed"))
        assertTrue(content.contains("PSS: 123456 KB"))
        assertTrue(content.contains("RSS: 65432 KB"))
        assertTrue(content.contains("System trace available: no"))
    }

    @Test
    fun `exit history is queried for any pid and survives a new process`() = testScope.runTest {
        stubRuntime()
        val pidSlot = slot<Int>()
        every { context.getSystemService(ActivityManager::class.java) } returns activityManager
        every { activityManager.getHistoricalProcessExitReasons(any(), capture(pidSlot), any()) } returns
            listOf(mockExitInfo(pid = 9999))
        mockShare()

        runExport()

        // pid 0 asks for records of every process of the package, including a previous PID.
        assertEquals(0, pidSlot.captured)
        val content = exportContent()
        assertTrue(content.contains("Exit 1"))
        assertTrue(content.contains("Process name: com.kernel.ai.test"))
        assertTrue(content.contains("Reason: REASON_CRASH (4)"))
    }

    @Test
    fun `signaled exit renders human readable reason and signal status`() = testScope.runTest {
        stubRuntime()
        stubExitHistory(
            mockExitInfo(
                reason = ApplicationExitInfo.REASON_SIGNALED,
                status = 9,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE,
            ),
        )
        mockShare()

        runExport()

        val content = exportContent()
        assertTrue(content.contains("Reason: REASON_SIGNALED (2)"))
        assertTrue(content.contains("Status/signal: 9 (signal)"))
        assertTrue(content.contains("Importance: IMPORTANCE_GONE (1000)"))
    }

    @Test
    fun `ANR textual trace is included and bounded`() = testScope.runTest {
        stubRuntime()
        val hugeTrace = "X".repeat(ANR_TRACE_MAX_CHARS + 5000) + "TRACE-END-MARKER"
        stubExitHistory(mockExitInfo(reason = ApplicationExitInfo.REASON_ANR, trace = hugeTrace.byteInputStream()))
        mockShare()

        runExport()

        val content = exportContent()
        assertTrue(content.contains("System trace available: yes"))
        assertTrue(content.contains("ANR trace excerpt:"))
        assertTrue(content.contains("truncated at $ANR_TRACE_MAX_CHARS characters"))
        assertFalse(content.contains("TRACE-END-MARKER"), "Trace tail beyond the bound must not be exported")
        assertFalse(content.contains("X".repeat(ANR_TRACE_MAX_CHARS + 1)), "Excerpt must stay within the bound")
    }

    @Test
    fun `ANR trace read failure adds warning and continues`() = testScope.runTest {
        stubRuntime()
        val failingStream = mockk<InputStream>(relaxed = true)
        every { failingStream.read() } throws IOException("trace corrupt")
        every { failingStream.read(any(), any(), any()) } throws IOException("trace corrupt")
        stubExitHistory(mockExitInfo(reason = ApplicationExitInfo.REASON_ANR, trace = failingStream))
        mockShare()

        runExport()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertTrue(content.contains("Exit 1"))
        assertTrue(content.contains("Reason: REASON_ANR (6)"))
        assertTrue(content.contains("System trace available: yes (could not be read)"))
        assertFalse(content.contains("System trace available: no"), "Trace existed, so availability must not read 'no'")
        assertTrue(content.contains("Could not read ANR trace"))
        assertTrue(content.contains("Current process logcat"))
    }

    @Test
    fun `trace access failure reports unknown state with warning and continues`() = testScope.runTest {
        stubRuntime()
        val info = mockExitInfo(reason = ApplicationExitInfo.REASON_CRASH_NATIVE)
        every { info.traceInputStream } throws IOException("trace file unreadable")
        stubExitHistory(info)
        mockShare()

        runExport()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertTrue(content.contains("System trace available: unknown (access failed)"))
        assertFalse(content.contains("System trace available: no"), "Access failure must not be reported as no trace")
        assertTrue(content.contains("Trace access failed: trace file unreadable"))
        assertTrue(content.contains("Reason: REASON_CRASH_NATIVE (5)"))
    }

    @Test
    fun `native crash trace is not decoded as text and notes ADB diagnostics`() = testScope.runTest {
        stubRuntime()
        val nativeStream = mockk<InputStream>(relaxed = true)
        every { nativeStream.read() } throws AssertionError("native trace must not be decoded as text")
        every { nativeStream.read(any(), any(), any()) } throws AssertionError("native trace must not be decoded as text")
        stubExitHistory(mockExitInfo(reason = ApplicationExitInfo.REASON_CRASH_NATIVE, trace = nativeStream))
        mockShare()

        runExport()

        // If the exporter tried to read the tombstone stream, the AssertionError would fail the export.
        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertTrue(content.contains("System trace available: yes"))
        assertTrue(content.contains("Trace note: native tombstone; use native/ADB diagnostics"))
        assertFalse(content.contains("ANR trace excerpt"), "Native tombstone must not be treated as textual ANR trace")
    }

    @Test
    fun `blank logcat with exit history succeeds with warning`() = testScope.runTest {
        stubRuntime(logContent = "")
        stubExitHistory(mockExitInfo())
        mockShare()

        runExport()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertTrue(content.contains("Reason: REASON_CRASH (4)"))
        assertTrue(content.contains("Current-process logcat unavailable: no log entries captured"))
        assertTrue(content.contains("No current-process logcat captured"))
        assertTrue(content.contains("Exporter warnings"))
    }

    @Test
    fun `logcat launch failure with exit history succeeds with warning`() = testScope.runTest {
        mockkStatic(Runtime::class)
        every { Runtime.getRuntime() } returns mockk(relaxed = true) {
            every { exec(any<Array<String>>()) } throws IOException("logcat not found")
        }
        stubExitHistory(mockExitInfo())
        mockShare()

        runExport()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertTrue(content.contains("could not start logcat"))
        assertTrue(content.contains("Reason: REASON_CRASH (4)"))
    }

    @Test
    fun `non zero logcat exit with exit history succeeds with warning`() = testScope.runTest {
        stubRuntime(exitCode = 1)
        stubExitHistory(mockExitInfo())
        mockShare()

        runExport()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertTrue(content.contains("logcat exited with code 1"))
        assertTrue(content.contains("I KernelAI: test log entry"))
    }

    @Test
    fun `opaque MagicOS vendor payload is rejected as unusable logcat`() = testScope.runTest {
        stubRuntime(logContent = OPAQUE_VENDOR_PAYLOAD)
        stubExitHistory(mockExitInfo())
        mockShare()

        runExport()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertFalse(content.contains("HKS"), "Opaque vendor payload must not be presented as logcat")
        assertTrue(content.contains("did not look like threadtime logcat"))
        assertTrue(content.contains("Reason: REASON_CRASH (4)"))
    }

    @Test
    fun `valid threadtime logcat is accepted`() = testScope.runTest {
        stubRuntime()
        stubExitHistory(mockExitInfo())
        mockShare()

        runExport()

        val content = exportContent()
        assertTrue(content.contains("I KernelAI: test log entry"))
        assertTrue(content.contains("W KernelAI: second entry"))
        assertFalse(content.contains("unavailable"))
    }

    @Test
    fun `exit history plus valid logcat produces both sections`() = testScope.runTest {
        stubRuntime()
        stubExitHistory(mockExitInfo())
        mockShare()

        runExport()

        val content = exportContent()
        assertTrue(content.contains("Recent process exits"))
        assertTrue(content.contains("Current process logcat"))
        assertTrue(content.contains("Exit 1"))
        assertTrue(content.contains("I KernelAI: test log entry"))
        assertTrue(content.contains("Exporter warnings"))
    }

    @Test
    fun `no exit history with valid logcat still succeeds`() = testScope.runTest {
        stubRuntime()
        mockShare()

        runExport()

        assertTrue(viewModel.uiState.value.exportState is ExportState.Ready)
        val content = exportContent()
        assertTrue(content.contains("No recent process exit records found."))
        assertTrue(content.contains("I KernelAI: test log entry"))
    }

    @Test
    fun `no exit history with unusable logcat produces error`() = testScope.runTest {
        stubRuntime(logContent = OPAQUE_VENDOR_PAYLOAD)

        runExport()

        val state = viewModel.uiState.value.exportState
        assertTrue(state is ExportState.Error)
        val message = (state as ExportState.Error).message
        assertTrue(message.contains("No useful diagnostics were available"))
        assertTrue(message.contains("did not look like threadtime logcat"))
        assertTrue(message.contains("adb logcat"))
    }

    @Test
    fun `app and build metadata appears in exported content`() = testScope.runTest {
        stubRuntime()
        mockShare()

        runExport(
            buildInfo = DiagnosticBuildInfo(
                versionName = "0.1.0",
                versionCode = 2916,
                buildType = "release",
                gitSha = "51c0a3f5",
                buildTimestamp = "2026-08-09T12:00:00Z",
            ),
        )

        val content = exportContent()
        assertTrue(content.contains("Jandal diagnostic export"))
        assertTrue(content.contains("Version: 0.1.0"))
        assertTrue(content.contains("Version code: 2916"))
        assertTrue(content.contains("Build type: release"))
        assertTrue(content.contains("Commit: 51c0a3f5"))
        assertTrue(content.contains("Built: 2026-08-09T12:00:00Z"))
        assertTrue(content.contains("Package: com.kernel.ai.test"))
    }

    @Test
    fun `export includes retained uncaught exception record when present`() = testScope.runTest {
        stubRuntime()
        mockShare()
        val recordFile = File(filesDir, LAST_UNCAUGHT_EXCEPTION_FILE_NAME)
        assertTrue(writeLastUncaughtExceptionRecord(recordFile, "main", IllegalStateException("recorded-boom")))

        runExport()

        val content = exportContent()
        assertTrue(content.contains("Last uncaught managed exception"))
        assertTrue(content.contains("Timestamp: "))
        assertTrue(content.contains("Thread: main"))
        assertTrue(content.contains("Exception: java.lang.IllegalStateException"))
        assertTrue(content.contains("Message: recorded-boom"))
        assertTrue(content.contains("Stack trace:"))
    }

    @Test
    fun `export succeeds and notes absence when no record is retained`() = testScope.runTest {
        stubRuntime()
        mockShare()

        runExport()

        val content = exportContent()
        assertTrue(content.contains("Last uncaught managed exception"))
        assertTrue(content.contains("No retained uncaught managed exception record found."))
        assertTrue(content.contains("Recent process exits"))
        assertTrue(content.contains("Current process logcat"))
    }

    @Test
    fun `export succeeds with warning when record is unreadable`() = testScope.runTest {
        stubRuntime()
        mockShare()
        val recordFile = File(filesDir, LAST_UNCAUGHT_EXCEPTION_FILE_NAME)
        recordFile.writeText("existing content")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            recordFile.setReadable(false),
            "filesystem must honour read permissions",
        )

        runExport()

        val content = exportContent()
        assertTrue(content.contains("Last uncaught managed exception"))
        assertTrue(content.contains("Could not read last uncaught exception record"))
        assertTrue(content.contains("Exporter warnings"))
    }

}
