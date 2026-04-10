package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.prefs.ModelPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hardwareProfileDetector: HardwareProfileDetector,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
) : ViewModel() {

    data class SettingsUiState(
        val activeModelLabel: String = "",
        val activeBackend: String = "",
        val activeTier: String = "",
        val preferredModel: KernelModel? = null,   // null = auto
        val e4bDownloaded: Boolean = false,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        modelPreferences.preferredConversationModel,
        modelDownloadManager.downloadStates,
    ) { preferredModel, downloadStates ->
        val profile = hardwareProfileDetector.profile
        val e4bDownloaded = downloadStates[KernelModel.GEMMA_4_E4B] is DownloadState.Downloaded

        fun isDownloadedInStates(model: KernelModel) = downloadStates[model] is DownloadState.Downloaded

        val activeModel: KernelModel = when {
            preferredModel != null && isDownloadedInStates(preferredModel) -> preferredModel
            else -> {
                val tierModel = KernelModel.entries
                    .firstOrNull { it.preferredForTier == profile.tier && isDownloadedInStates(it) }
                tierModel ?: KernelModel.GEMMA_4_E2B
            }
        }

        SettingsUiState(
            activeModelLabel = activeModel.displayName,
            activeBackend = profile.recommendedBackend.name,
            activeTier = profile.tier.name,
            preferredModel = preferredModel,
            e4bDownloaded = e4bDownloaded,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setPreferredModel(model: KernelModel?) {
        viewModelScope.launch { modelPreferences.setPreferredModel(model) }
    }
}
