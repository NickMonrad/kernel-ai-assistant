package com.kernel.ai.feature.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
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

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("about") private val dataStore: DataStore<Preferences>,
) : ViewModel() {

    private companion object {
        val KEY_VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging")
    }

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val enabled = dataStore.data.map { prefs -> prefs[KEY_VERBOSE_LOGGING] ?: false }.first()
                _uiState.update { it.copy(verboseLogging = enabled) }
            } catch (e: Exception) {
                // Silently fail — verbose logging is optional
            }
        }
    }

    fun setVerboseLogging(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_VERBOSE_LOGGING] = enabled
            }
        }
    }

    fun exportLogs() {
        _uiState.update { it.copy(exportState = ExportState.Loading) }
        viewModelScope.launch {
            try {
                val shareIntent = withContext(Dispatchers.IO) {
                    val pid = android.os.Process.myPid()
                    val process = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", "500"),
                    )
                    val logText = process.inputStream.bufferedReader().use { it.readText() }
                    process.errorStream.bufferedReader().use { it.readText() } // drain stderr to avoid deadlock
                    process.waitFor()
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                    val versionName = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                    } catch (_: Exception) {
                        "unknown"
                    }
                    val logFile = File(context.cacheDir, "kernel_debug_log_${versionName}_${timestamp}.txt")
                    logFile.writeText(logText)
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

    fun clearExportState() {
        _uiState.update { it.copy(exportState = ExportState.Idle) }
    }
}
