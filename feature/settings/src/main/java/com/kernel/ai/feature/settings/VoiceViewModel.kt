package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceUiState(
    val spokenResponsesEnabled: Boolean = true,
    val selectedInputEngine: VoiceInputEngine = VoiceInputEngine.Vosk,
    val androidNativeAvailabilityMessage: String? = null,
)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                androidNativeAvailabilityMessage = androidNativeRecognitionSupport
                    .getAvailability()
                    .unavailableReason,
            )
        }
        viewModelScope.launch {
            voiceInputPreferences.selectedEngine.collect { engine ->
                _uiState.update { it.copy(selectedInputEngine = engine) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.spokenResponsesEnabled.collect { enabled ->
                _uiState.update { it.copy(spokenResponsesEnabled = enabled) }
            }
        }
    }

    fun setVoiceInputEngine(engine: VoiceInputEngine) {
        _uiState.update { it.copy(selectedInputEngine = engine) }
        viewModelScope.launch {
            voiceInputPreferences.setSelectedEngine(engine)
        }
    }

    fun setSpokenResponsesEnabled(enabled: Boolean) {
        _uiState.update { it.copy(spokenResponsesEnabled = enabled) }
        viewModelScope.launch {
            voiceOutputPreferences.setSpokenResponsesEnabled(enabled)
        }
    }
}
