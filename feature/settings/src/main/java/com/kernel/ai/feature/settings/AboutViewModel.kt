package com.kernel.ai.feature.settings

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.voice.VoiceOutputPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named

data class AboutUiState(
    val verboseLogging: Boolean = false,
    val exportState: ExportState = ExportState.Idle,
)

sealed class ExportState {
    object Idle : ExportState()
    object Loading : ExportState()
    data class Ready(val intent: Intent) : ExportState()
    data class Error(val message: String) : ExportState()
}

/** Build identity already rendered on the About screen, passed into the export call. */
data class DiagnosticBuildInfo(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val gitSha: String,
    val buildTimestamp: String,
)

/** Most recent exit records to include, newest first (bounded). */
internal const val MAX_EXIT_RECORDS = 5

/** Maximum characters of a textual ANR trace to include in the export (bounded). */
internal const val ANR_TRACE_MAX_CHARS = 8192

/** Maximum characters of logcat stderr detail kept in a warning (bounded). */
internal const val MAX_STDERR_WARNING_CHARS = 500

/**
 * Result of inspecting an [ApplicationExitInfo] trace stream, preserving the
 * distinction between "no trace", "trace available", and "trace access failed".
 */
private sealed class TraceState {
    /** Android reports no trace stream for this exit. */
    object Absent : TraceState()

    /** A trace stream was obtained; [text] holds the bounded ANR excerpt when read. */
    data class Available(val text: String? = null, val truncated: Boolean = false) : TraceState()

    /**
     * Obtaining/reading the trace failed. [streamExisted] is true when a stream was
     * obtained but could not be read, false when obtaining the stream itself failed.
     */
    data class AccessFailed(val detail: String, val streamExisted: Boolean) : TraceState()
}

private data class LogcatCapture(val output: String?, val failureDetail: String?)

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("about") private val dataStore: DataStore<Preferences>,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : ViewModel() {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private companion object {
        val KEY_VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging")
        val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        val EXIT_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

        /** `MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message` from `logcat -v threadtime`. */
        val THREADTIME_ENTRY: Regex =
            Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s""")

        /** Normal logcat buffer headings, e.g. `--------- beginning of main`. */
        val LOGCAT_HEADING: Regex = Regex("""^-+ beginning of \w+""")
    }

    private val defaultVerboseLoggingEnabled: Boolean
        get() = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _uiState = MutableStateFlow(AboutUiState(verboseLogging = defaultVerboseLoggingEnabled))
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private val verboseLoggingMutex = Mutex()

    init {
        viewModelScope.launch {
            verboseLoggingMutex.withLock {
                try {
                    val enabled = dataStore.data.map { prefs -> prefs[KEY_VERBOSE_LOGGING] ?: defaultVerboseLoggingEnabled }.first()
                    _uiState.update { it.copy(verboseLogging = enabled) }
                    voiceOutputPreferences.setVerboseLogging(enabled)
                } catch (e: Exception) {
                    // Silently fail — verbose logging is optional
                    _uiState.update { it.copy(verboseLogging = defaultVerboseLoggingEnabled) }
                    voiceOutputPreferences.setVerboseLogging(defaultVerboseLoggingEnabled)
                }
            }
        }
    }

    fun setVerboseLogging(enabled: Boolean) {
        _uiState.update { it.copy(verboseLogging = enabled) }
        viewModelScope.launch {
            verboseLoggingMutex.withLock {
                try {
                    dataStore.edit { prefs ->
                        prefs[KEY_VERBOSE_LOGGING] = enabled
                    }
                    voiceOutputPreferences.setVerboseLogging(enabled)
                } catch (e: Exception) {
                    _uiState.update { it.copy(verboseLogging = !enabled) }
                    runCatching { voiceOutputPreferences.setVerboseLogging(!enabled) }
                }
            }
        }
    }

    fun exportLogs(buildInfo: DiagnosticBuildInfo) {
        _uiState.update { it.copy(exportState = ExportState.Loading) }
        viewModelScope.launch {
            try {
                val shareIntent = withContext(ioDispatcher) {
                    val content = buildDiagnosticExport(buildInfo)
                    val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER)
                    val logFile = File(context.cacheDir, "kernel_debug_log_${buildInfo.versionName}_$timestamp.txt")
                    logFile.writeText(content)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        logFile,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    Intent.createChooser(intent, "Export logs")
                }
                _uiState.update { it.copy(exportState = ExportState.Ready(shareIntent)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(exportState = ExportState.Error(e.message ?: "Export failed")) }
            }
        }
    }

    /**
     * Assembles the bounded diagnostic text bundle. Each evidence source is isolated:
     * failure of one never prevents the others from exporting. Only when no useful
     * evidence exists at all does this throw, which surfaces as [ExportState.Error].
     */
    private fun buildDiagnosticExport(buildInfo: DiagnosticBuildInfo): String {
        val warnings = mutableListOf<String>()
        val exitRecords = queryExitHistory(warnings)
        val logcat = captureCurrentLogcat(warnings)

        if (exitRecords.isEmpty() && logcat.output == null) {
            val detail = logcat.failureDetail ?: "no recent process exits were recorded"
            throw Exception(
                "No useful diagnostics were available ($detail). This device may restrict " +
                    "logcat access for user apps. Use ADB instead:\nadb logcat -s KernelAI",
            )
        }

        return buildString {
            appendLine("Jandal diagnostic export")
            appendLine()
            appendLine("App/build information")
            appendLine("---------------------")
            appendLine("Version: ${buildInfo.versionName}")
            appendLine("Version code: ${buildInfo.versionCode}")
            appendLine("Build type: ${buildInfo.buildType}")
            appendLine("Commit: ${buildInfo.gitSha}")
            appendLine("Built: ${buildInfo.buildTimestamp}")
            appendLine("Package: ${context.packageName}")
            appendLine()
            appendLine("Recent process exits")
            appendLine("--------------------")
            if (exitRecords.isEmpty()) {
                appendLine("No recent process exit records found.")
            } else {
                exitRecords.forEachIndexed { index, record ->
                    val block = StringBuilder()
                    runCatching { renderExitRecord(block, index + 1, record, warnings) }
                        .onSuccess { append(block) }
                        .onFailure { e ->
                            warnings += "Could not render exit record ${index + 1}: " +
                                "${e.message ?: e.javaClass.simpleName}"
                        }
                }
            }
            appendLine()
            appendLine("Current process logcat")
            appendLine("----------------------")
            appendLine(logcat.output ?: "No current-process logcat captured (see Exporter warnings below).")
            appendLine()
            appendLine("Exporter warnings")
            appendLine("-----------------")
            if (warnings.isEmpty()) {
                appendLine("None.")
            } else {
                warnings.forEach { appendLine("- $it") }
            }
        }
    }

    private fun queryExitHistory(warnings: MutableList<String>): List<ApplicationExitInfo> {
        val activityManager = try {
            context.getSystemService(ActivityManager::class.java)
        } catch (e: Exception) {
            warnings += "Could not query process exit history: ${e.message ?: e.javaClass.simpleName}"
            return emptyList()
        }
        if (activityManager == null) {
            warnings += "Could not query process exit history: ActivityManager service unavailable"
            return emptyList()
        }
        return try {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS).orEmpty()
        } catch (e: Exception) {
            warnings += "Could not query process exit history: ${e.message ?: e.javaClass.simpleName}"
            emptyList()
        }
    }

    private fun renderExitRecord(
        sb: StringBuilder,
        index: Int,
        info: ApplicationExitInfo,
        warnings: MutableList<String>,
    ) {
        sb.appendLine("Exit $index")
        sb.appendLine("Timestamp: ${EXIT_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(info.timestamp).atOffset(ZoneOffset.UTC))}")
        sb.appendLine("Process name: ${info.processName}")
        sb.appendLine("Reason: ${formatExitReason(info.reason)}")
        if (info.reason == ApplicationExitInfo.REASON_SIGNALED) {
            sb.appendLine("Status/signal: ${info.status} (signal)")
        } else {
            sb.appendLine("Status: ${info.status}")
        }
        sb.appendLine("Importance: ${formatImportance(info.importance)}")
        if (!info.description.isNullOrBlank()) {
            sb.appendLine("Description: ${info.description}")
        }
        sb.appendLine("PSS: ${formatMemory(info.pss)}")
        sb.appendLine("RSS: ${formatMemory(info.rss)}")
        when (info.reason) {
            ApplicationExitInfo.REASON_ANR -> renderAnrTrace(sb, info, warnings)
            ApplicationExitInfo.REASON_CRASH_NATIVE -> renderNativeTraceNote(sb, info, warnings)
            else -> renderTraceAvailability(sb, info, warnings)
        }
        sb.appendLine()
    }

    /** Renders a textual ANR trace (bounded to [ANR_TRACE_MAX_CHARS]); never fails the export. */
    private fun renderAnrTrace(sb: StringBuilder, info: ApplicationExitInfo, warnings: MutableList<String>) {
        val state = queryTraceState(info)
        if (state is TraceState.Available) {
            sb.appendLine("System trace available: yes")
            sb.appendLine("ANR trace excerpt:")
            sb.append(state.text ?: "")
            if (state.truncated) {
                sb.appendLine("\n[ANR trace excerpt truncated at $ANR_TRACE_MAX_CHARS characters]")
            }
            return
        }
        renderTraceState(sb, state, warnings)
    }

    /** Native tombstone traces are never decoded; only availability is recorded. */
    private fun renderNativeTraceNote(sb: StringBuilder, info: ApplicationExitInfo, warnings: MutableList<String>) {
        val state = queryTraceState(info)
        if (state is TraceState.Available) {
            sb.appendLine("System trace available: yes")
            sb.appendLine("Trace note: native tombstone; use native/ADB diagnostics")
            return
        }
        renderTraceState(sb, state, warnings)
    }

    private fun renderTraceAvailability(sb: StringBuilder, info: ApplicationExitInfo, warnings: MutableList<String>) {
        renderTraceState(sb, queryTraceState(info), warnings)
    }

    private fun renderTraceState(sb: StringBuilder, state: TraceState, warnings: MutableList<String>) {
        when (state) {
            TraceState.Absent -> sb.appendLine("System trace available: no")
            is TraceState.Available -> sb.appendLine("System trace available: yes")
            is TraceState.AccessFailed -> {
                if (state.streamExisted) {
                    warnings += "Could not read ANR trace: ${state.detail}"
                    sb.appendLine("System trace available: yes (could not be read)")
                } else {
                    warnings += "Trace access failed: ${state.detail}"
                    sb.appendLine("System trace available: unknown (access failed)")
                }
            }
        }
    }

    /**
     * Inspects the trace stream without conflating "absent" with "could not be read".
     * ANR streams are read as text within the fixed bound; native/other streams are
     * only opened and immediately closed to report availability.
     */
    private fun queryTraceState(info: ApplicationExitInfo): TraceState {
        val stream = try {
            info.traceInputStream
        } catch (e: Exception) {
            return TraceState.AccessFailed(
                detail = e.message ?: e.javaClass.simpleName,
                streamExisted = false,
            )
        }
        if (stream == null) {
            return TraceState.Absent
        }
        if (info.reason != ApplicationExitInfo.REASON_ANR) {
            // Availability only — never read native/other trace content.
            runCatching { stream.close() }
            return TraceState.Available()
        }
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(ANR_TRACE_MAX_CHARS)
                var count = 0
                while (count < ANR_TRACE_MAX_CHARS) {
                    val n = reader.read(buffer, count, ANR_TRACE_MAX_CHARS - count)
                    if (n == -1) break
                    count += n
                }
                TraceState.Available(
                    text = String(buffer, 0, count),
                    truncated = count == ANR_TRACE_MAX_CHARS,
                )
            }
        } catch (e: Exception) {
            TraceState.AccessFailed(
                detail = e.message ?: e.javaClass.simpleName,
                streamExisted = true,
            )
        }
    }

    private fun formatExitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "REASON_UNKNOWN ($reason)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF ($reason)"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED ($reason)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY ($reason)"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH ($reason)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE ($reason)"
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR ($reason)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE ($reason)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE ($reason)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE ($reason)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED ($reason)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED ($reason)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED ($reason)"
        ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER ($reason)"
        ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER ($reason)"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "REASON_PACKAGE_STATE_CHANGE ($reason)"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "REASON_PACKAGE_UPDATED ($reason)"
        else -> "UNKNOWN ($reason)"
    }

    private fun formatImportance(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "IMPORTANCE_FOREGROUND ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "IMPORTANCE_FOREGROUND_SERVICE ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "IMPORTANCE_TOP_SLEEPING ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "IMPORTANCE_VISIBLE ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "IMPORTANCE_PERCEPTIBLE ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "IMPORTANCE_SERVICE ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "IMPORTANCE_BACKGROUND ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "IMPORTANCE_EMPTY ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "IMPORTANCE_GONE ($importance)"
        else -> "UNKNOWN ($importance)"
    }

    private fun formatMemory(bytes: Long): String = if (bytes >= 0) "$bytes KB" else "n/a"

    /** Captures current-process logcat; a failure is a warning, never an export failure by itself. */
    private fun captureCurrentLogcat(warnings: MutableList<String>): LogcatCapture {
        val pid = Process.myPid()
        val process = try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", "500"))
        } catch (e: IOException) {
            return unavailable(warnings, "could not start logcat: ${e.message ?: e.javaClass.simpleName}")
        }
        val stdout = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return unavailable(warnings, "could not read logcat output: ${e.message ?: e.javaClass.simpleName}")
        }
        val stderr = runCatching { process.errorStream.bufferedReader().use { it.readText() } }
            .getOrDefault("")
            .trim()
        val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
        if (stderr.isNotBlank()) {
            warnings += "logcat stderr: ${stderr.take(MAX_STDERR_WARNING_CHARS)}"
        }
        if (exitCode != 0) {
            warnings += "logcat exited with code $exitCode"
        }
        if (stdout.isBlank()) {
            val detail = stderr.ifEmpty { "exit code $exitCode" }
            return unavailable(warnings, "no log entries captured ($detail)")
        }
        if (!isPlausibleLogcat(stdout)) {
            return unavailable(warnings, "logcat output did not look like threadtime logcat")
        }
        return LogcatCapture(stdout, null)
    }

    private fun unavailable(warnings: MutableList<String>, detail: String): LogcatCapture {
        warnings += "Current-process logcat unavailable: $detail"
        return LogcatCapture(null, detail)
    }

    /**
     * Narrow plausibility check for `logcat -v threadtime` output: at least one line must
     * look like a threadtime entry or a logcat buffer heading. Rejects opaque vendor
     * payloads (e.g. MagicOS `HKS…HKE` blobs) without trying to parse logcat.
     */
    private fun isPlausibleLogcat(output: String): Boolean {
        return output.lineSequence().any { line ->
            THREADTIME_ENTRY.containsMatchIn(line) || LOGCAT_HEADING.containsMatchIn(line)
        }
    }

    fun clearExportState() {
        _uiState.update { it.copy(exportState = ExportState.Idle) }
    }
}
