package com.kernel.ai.feature.settings

import android.net.Uri
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.memory.wallpaper.WallpaperManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Chat Preferences screen.
 */
@HiltViewModel
class ChatPreferencesViewModel @Inject constructor(
    private val chatPreferences: ChatPreferences,
    private val wallpaperManager: WallpaperManager,
) : ViewModel() {

    // ---- Archive ----

    val archiveRetentionDays: StateFlow<Int> = chatPreferences.archiveRetentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)

    fun setArchiveRetentionDays(days: Int) {
        viewModelScope.launch { chatPreferences.setArchiveRetentionDays(days) }
    }

    // ---- Visual customisation (#906) ----

    val fontSize: StateFlow<Int> = chatPreferences.fontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    fun setFontSize(size: Int) {
        viewModelScope.launch { chatPreferences.setFontSize(size) }
    }

    val bubbleTheme: StateFlow<String> = chatPreferences.bubbleTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    fun setBubbleTheme(theme: String) {
        viewModelScope.launch { chatPreferences.setBubbleTheme(theme) }
    }

    val userFontColor: StateFlow<Long?> = chatPreferences.userFontColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setUserFontColor(color: Long?) {
        viewModelScope.launch { chatPreferences.setUserFontColor(color) }
    }

    val assistantFontColor: StateFlow<Long?> = chatPreferences.assistantFontColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAssistantFontColor(color: Long?) {
        viewModelScope.launch { chatPreferences.setAssistantFontColor(color) }
    }

    val wallpaperType: StateFlow<String> = chatPreferences.wallpaperType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "none")

    fun setWallpaperType(type: String) {
        viewModelScope.launch {
            chatPreferences.setWallpaperType(type)
            // Clear the active image reference when switching away from image mode (#1206)
            if (type != "image") {
                chatPreferences.setWallpaperImageUri(null)
            }
            refreshImportedWallpapers()
        }
    }

    val wallpaperColor: StateFlow<Long?> = chatPreferences.wallpaperColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWallpaperColor(color: Long?) {
        viewModelScope.launch { chatPreferences.setWallpaperColor(color) }
    }

    val wallpaperImageUri: StateFlow<String?> = chatPreferences.wallpaperImageUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Wallpaper management (#1206) ----

    /** All imported wallpaper files in app-private storage, sorted by recency. */
    private val _importedWallpapers = MutableStateFlow<List<ImportedWallpaper>>(emptyList())
    val importedWallpapers: StateFlow<List<ImportedWallpaper>> = _importedWallpapers.asStateFlow()

    /** Error message from the last wallpaper import attempt, or null. */
    private val _wallpaperImportError = MutableStateFlow<String?>(null)
    val wallpaperImportError: StateFlow<String?> = _wallpaperImportError.asStateFlow()

    /** True while a legacy external wallpaper URI is being migrated. */
    private val _migrationRunning = MutableStateFlow(false)
    val migrationRunning: StateFlow<Boolean> = _migrationRunning.asStateFlow()

    init {
        // Migrate any legacy external content:// URI into app-private storage
        migrateLegacyWallpaper()
        refreshImportedWallpapers()
    }

    /**
     * Import an image from [uri] (Gallery or Files) into app-private storage and activate it.
     * Runs import on [Dispatchers.IO]; the composable callback remains lightweight.
     */
    fun importWallpaper(uri: Uri) {
        viewModelScope.launch {
            val result = wallpaperManager.importWallpaper(uri)
            result.fold(
                onSuccess = { path ->
                    chatPreferences.setWallpaperImageUri(path)
                    chatPreferences.setWallpaperType("image")
                    _wallpaperImportError.value = null
                    refreshImportedWallpapers()
                },
                onFailure = { error ->
                    _wallpaperImportError.value = error.message ?: "Failed to import wallpaper"
                },
            )
        }
    }

    /**
     * Delete the currently active imported wallpaper file and reset to default (none).
     * Only affects app-owned files; never touches the original Gallery/Files source.
     */
    fun deleteCurrentWallpaper() {
        viewModelScope.launch {
            val currentUri = chatPreferences.wallpaperImageUri.first()
            if (currentUri != null) {
                wallpaperManager.deleteWallpaper(currentUri)
            }
            chatPreferences.setWallpaperImageUri(null)
            chatPreferences.setWallpaperType("none")
            refreshImportedWallpapers()
        }
    }

    /**
     * Delete all imported wallpaper files that are not currently active.
     * Only affects app-owned files under the wallpaper import directory.
     */
    fun deleteUnusedWallpapers() {
        viewModelScope.launch {
            val currentType = chatPreferences.wallpaperType.first()
            val currentUri = chatPreferences.wallpaperImageUri.first()
            // Only treat the image as active when the mode is actually "image" (#1206)
            val active = if (currentType == "image" && currentUri != null) setOf(currentUri) else emptySet()
            wallpaperManager.deleteUnusedWallpapers(active)
            refreshImportedWallpapers()
        }
    }

    /**
     * Select an already-imported wallpaper from app-private storage and activate it.
     * Does not launch Gallery/Files or re-import the file.
     */
    fun selectImportedWallpaper(path: String) {
        viewModelScope.launch {
            // Verify the file still exists before activating
            if (!File(path).exists()) {
                refreshImportedWallpapers()
                _wallpaperImportError.value = "Selected wallpaper file was not found"
                return@launch
            }
            chatPreferences.setWallpaperImageUri(path)
            chatPreferences.setWallpaperType("image")
            refreshImportedWallpapers()
        }
    }

    fun clearWallpaperImportError() {
        _wallpaperImportError.value = null
    }

    private fun refreshImportedWallpapers() {
        viewModelScope.launch {
            val currentType = chatPreferences.wallpaperType.first()
            val currentUri = chatPreferences.wallpaperImageUri.first()
            val activeUri = currentUri.takeIf { currentType == "image" }
            val files = wallpaperManager.getImportedWallpapers()
            _importedWallpapers.value = files.map { file ->
                ImportedWallpaper(
                    path = file.absolutePath,
                    name = file.name,
                    isActive = file.absolutePath == activeUri,
                    lastModified = file.lastModified(),
                )
            }
        }
    }

    /**
     * Migrate a legacy external [content://] wallpaper URI into app-private storage.
     * Called once at init. On failure the active wallpaper is silently reset to default.
     */
    private fun migrateLegacyWallpaper() {
        viewModelScope.launch {
            val type = chatPreferences.wallpaperType.first()
            val uri = chatPreferences.wallpaperImageUri.first()
            if (type != "image" || uri == null || !isExternalContentUri(uri)) return@launch

            _migrationRunning.value = true
            val result = wallpaperManager.importWallpaper(Uri.parse(uri))
            result.fold(
                onSuccess = { path ->
                    chatPreferences.setWallpaperImageUri(path)
                    // wallpaperType stays "image"
                },
                onFailure = {
                    // Fall back gracefully — don't crash, don't keep a broken reference
                    chatPreferences.setWallpaperType("none")
                    chatPreferences.setWallpaperImageUri(null)
                },
            )
            _migrationRunning.value = false
        }
    }

    /** True if [uriStr] is an external content:// URI rather than an app-owned file path. */
    private fun isExternalContentUri(uriStr: String): Boolean = uriStr.startsWith("content://")

    // ---- Conversation copy (#1024) ----

    val copyToolCalls: StateFlow<Boolean> = chatPreferences.copyToolCalls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setCopyToolCalls(enabled: Boolean) {
        viewModelScope.launch { chatPreferences.setCopyToolCalls(enabled) }
    }

    val copyThinking: StateFlow<Boolean> = chatPreferences.copyThinking
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setCopyThinking(enabled: Boolean) {
        viewModelScope.launch { chatPreferences.setCopyThinking(enabled) }
    }

    // ---- Dynamic Colour (#1183) ----

    val useSystemColors: StateFlow<Boolean> = chatPreferences.useSystemColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setUseSystemColors(enabled: Boolean) {
        viewModelScope.launch { chatPreferences.setUseSystemColors(enabled) }
    }
}

/** Metadata for an imported wallpaper file in app-private storage. */
data class ImportedWallpaper(
    val path: String,
    val name: String,
    val isActive: Boolean,
    val lastModified: Long,
)
