package com.kernel.ai.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelSettingsViewModel @Inject constructor(
    private val modelSettingsRepository: ModelSettingsRepository,
) : ViewModel() {

    data class ModelSettingsUiState(
        val e2bSettings: ModelSettingsEntity? = null,
        val e4bSettings: ModelSettingsEntity? = null,
        val isSaving: Boolean = false,
    )

    private val _uiState = MutableStateFlow(ModelSettingsUiState())
    val uiState: StateFlow<ModelSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val e2b = modelSettingsRepository.getSettings(MODEL_ID_E2B)
            val e4b = modelSettingsRepository.getSettings(MODEL_ID_E4B)
            _uiState.update { it.copy(e2bSettings = e2b, e4bSettings = e4b) }
        }
    }

    fun updateE2bSettings(settings: ModelSettingsEntity) {
        _uiState.update { it.copy(e2bSettings = settings) }
        saveSettings(settings)
    }

    fun updateE4bSettings(settings: ModelSettingsEntity) {
        _uiState.update { it.copy(e4bSettings = settings) }
        saveSettings(settings)
    }

    fun resetE2bToDefaults() {
        viewModelScope.launch {
            val defaults = modelSettingsRepository.resetToDefaults(MODEL_ID_E2B)
            _uiState.update { it.copy(e2bSettings = defaults) }
        }
    }

    fun resetE4bToDefaults() {
        viewModelScope.launch {
            val defaults = modelSettingsRepository.resetToDefaults(MODEL_ID_E4B)
            _uiState.update { it.copy(e4bSettings = defaults) }
        }
    }

    private fun saveSettings(entity: ModelSettingsEntity) {
        viewModelScope.launch {
            try {
                modelSettingsRepository.saveSettings(entity)
            } catch (e: Exception) {
                Log.e("KernelAI", "ModelSettingsViewModel: failed to save settings", e)
            }
        }
    }

    companion object {
        const val MODEL_ID_E2B = "gemma4_e2b"
        const val MODEL_ID_E4B = "gemma4_e4b"
    }
}
