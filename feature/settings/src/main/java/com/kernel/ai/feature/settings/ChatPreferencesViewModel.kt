package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.prefs.ChatPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatPreferencesViewModel @Inject constructor(
    private val chatPreferences: ChatPreferences,
) : ViewModel() {

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
        viewModelScope.launch { chatPreferences.setWallpaperType(type) }
    }

    val wallpaperColor: StateFlow<Long?> = chatPreferences.wallpaperColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWallpaperColor(color: Long?) {
        viewModelScope.launch { chatPreferences.setWallpaperColor(color) }
    }

    val wallpaperImageUri: StateFlow<String?> = chatPreferences.wallpaperImageUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWallpaperImageUri(uri: String?) {
        viewModelScope.launch { chatPreferences.setWallpaperImageUri(uri) }
    }

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