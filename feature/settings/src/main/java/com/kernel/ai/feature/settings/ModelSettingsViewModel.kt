package com.kernel.ai.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.KernelModel
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
        /** Current draft values shown in the UI. Not persisted until [saveSettings] is called. */
        val e2bSettings: ModelSettingsEntity? = null,
        val e4bSettings: ModelSettingsEntity? = null,
        /** Last-persisted snapshot — used to detect unsaved changes and to revert on Cancel. */
        val persistedE2b: ModelSettingsEntity? = null,
        val persistedE4b: ModelSettingsEntity? = null,
        val isSaving: Boolean = false,
    ) {
        val hasUnsavedChanges: Boolean
            get() = e2bSettings != persistedE2b || e4bSettings != persistedE4b
    }

    private val _uiState = MutableStateFlow(ModelSettingsUiState())
    val uiState: StateFlow<ModelSettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val e2b = modelSettingsRepository.getSettings(MODEL_ID_E2B)
            val e4b = modelSettingsRepository.getSettings(MODEL_ID_E4B)
            _uiState.update {
                it.copy(
                    e2bSettings = e2b,
                    e4bSettings = e4b,
                    persistedE2b = e2b,
                    persistedE4b = e4b,
                )
            }
        }
    }

    /** Updates the E-2B draft. Not persisted until [saveSettings] is called. */
    fun updateE2bSettings(settings: ModelSettingsEntity) {
        _uiState.update { it.copy(e2bSettings = settings) }
    }

    /** Updates the E-4B draft. Not persisted until [saveSettings] is called. */
    fun updateE4bSettings(settings: ModelSettingsEntity) {
        _uiState.update { it.copy(e4bSettings = settings) }
    }

    /**
     * Resets E-2B to hardware-aware defaults and persists them immediately.
     * A process restart is still required for the inference engine to load the new values.
     */
    fun resetE2bToDefaults() {
        viewModelScope.launch {
            val defaults = modelSettingsRepository.resetToDefaults(MODEL_ID_E2B)
            _uiState.update { it.copy(e2bSettings = defaults, persistedE2b = defaults) }
        }
    }

    /**
     * Resets E-4B to hardware-aware defaults and persists them immediately.
     * A process restart is still required for the inference engine to load the new values.
     */
    fun resetE4bToDefaults() {
        viewModelScope.launch {
            val defaults = modelSettingsRepository.resetToDefaults(MODEL_ID_E4B)
            _uiState.update { it.copy(e4bSettings = defaults, persistedE4b = defaults) }
        }
    }

    /** Reverts all unsaved draft changes back to the last-persisted values. */
    fun cancelChanges() {
        _uiState.update { it.copy(e2bSettings = it.persistedE2b, e4bSettings = it.persistedE4b) }
    }

    /**
     * Persists both draft settings, then invokes [onSaved].
     * The caller is responsible for triggering a process restart inside [onSaved].
     */
    fun saveSettings(onSaved: () -> Unit) {
        viewModelScope.launch {
            val draft2b = _uiState.value.e2bSettings ?: return@launch
            val draft4b = _uiState.value.e4bSettings ?: return@launch
            _uiState.update { it.copy(isSaving = true) }
            try {
                modelSettingsRepository.saveSettings(draft2b)
                modelSettingsRepository.saveSettings(draft4b)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        persistedE2b = draft2b,
                        persistedE4b = draft4b,
                    )
                }
                onSaved()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings", e)
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    companion object {
        val MODEL_ID_E2B = KernelModel.GEMMA_4_E2B.modelId
        val MODEL_ID_E4B = KernelModel.GEMMA_4_E4B.modelId
        private const val TAG = "KernelAI"
    }
}
