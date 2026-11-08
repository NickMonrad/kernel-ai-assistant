package com.kernel.ai.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.model.availability.AvailabilitySummary
import com.kernel.ai.core.model.availability.GatedModelStatus
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.model.availability.computeAvailabilitySummary
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.prefs.ModelPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hardwareProfileDetector: HardwareProfileDetector,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val authRepository: HuggingFaceAuthRepository,
    private val gatedModelStatusRepository: GatedModelStatusRepository,
) : ViewModel() {

    data class SettingsUiState(
        val activeModelLabel: String = "",
        val activeBackend: String = "",
        val activeTier: String = "",
        val preferredModel: KernelModel? = null,   // null = auto
        val e2bDownloaded: Boolean = false,
        val e4bDownloaded: Boolean = false,
        /** True when a valid HF token is stored. */
        val hfAuthenticated: Boolean = false,
        /** HuggingFace username from OIDC id_token, or null. */
        val hfUsername: String? = null,
        val modelAvailabilitySummary: AvailabilitySummary = AvailabilitySummary(total = 0),
    )

    private val _gatedStatuses = MutableStateFlow<Map<KernelModel, GatedModelStatus>>(emptyMap())

    init {
        viewModelScope.launch {
            val gatedModels = KernelModel.entries.filter {
                it.showInModelManagement && !it.isDeprecated && it.isGated
            }
            gatedModels.forEach { model ->
                launch {
                    gatedModelStatusRepository.get(model).collect { status ->
                        _gatedStatuses.update { it.toMutableMap().apply { put(model, status) } }
                    }
                }
            }
        }
        // Forward authResult outcomes so the Settings screen can surface sign-in feedback.
        viewModelScope.launch {
            authRepository.authResult.collect { result ->
                result.onSuccess { _saveSuccess.tryEmit("Signed in to HuggingFace ✓") }
                result.onFailure { e -> _saveError.tryEmit("Sign-in failed: ${e.message}") }
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        modelPreferences.preferredConversationModel,
        modelDownloadManager.downloadStates,
        modelDownloadManager.downloadSources,
        authRepository.isAuthenticated,
        authRepository.username,
        _gatedStatuses,
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        val preferredModel = array[0] as KernelModel?
        @Suppress("UNCHECKED_CAST")
        val downloadStates = array[1] as Map<KernelModel, DownloadState>
        @Suppress("UNCHECKED_CAST")
        val downloadSources = array[2] as Map<KernelModel, com.kernel.ai.core.inference.download.DownloadSource>
        val hfAuthenticated = array[3] as Boolean
        @Suppress("UNCHECKED_CAST")
        val hfUsername = array[4] as String?
        @Suppress("UNCHECKED_CAST")
        val gatedStatuses = array[5] as Map<KernelModel, GatedModelStatus>
        val profile = hardwareProfileDetector.profile
        val e4bDownloaded = downloadStates[KernelModel.GEMMA_4_E4B] is DownloadState.Downloaded
        val e2bDownloaded = downloadStates[KernelModel.GEMMA_4_E2B] is DownloadState.Downloaded

        fun isDownloadedInStates(model: KernelModel) = downloadStates[model] is DownloadState.Downloaded

        val activeModel: KernelModel = when {
            preferredModel != null && isDownloadedInStates(preferredModel) -> preferredModel
            else -> {
                val tierModel = KernelModel.entries
                    .firstOrNull { it.preferredForTier == profile.tier && isDownloadedInStates(it) }
                tierModel ?: KernelModel.GEMMA_4_E2B
            }
        }

        val summary = computeAvailabilitySummary(
            models = KernelModel.entries.filter { it.showInModelManagement && !it.isDeprecated },
            downloadStates = downloadStates,
            hfAuth = hfAuthenticated,
            downloadSources = downloadSources,
            gatedStatuses = gatedStatuses,
        )

        SettingsUiState(
            activeModelLabel = activeModel.displayName,
            activeBackend = profile.recommendedBackend.name,
            activeTier = profile.tier.name,
            preferredModel = preferredModel,
            e2bDownloaded = e2bDownloaded,
            e4bDownloaded = e4bDownloaded,
            hfAuthenticated = hfAuthenticated,
            hfUsername = hfUsername,
            modelAvailabilitySummary = summary,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    private val _saveError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saveError: SharedFlow<String> = _saveError.asSharedFlow()

    private val _saveSuccess = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val saveSuccess: SharedFlow<String> = _saveSuccess.asSharedFlow()

    fun setPreferredModel(model: KernelModel?) {
        viewModelScope.launch {
            val current = uiState.value.preferredModel
            if (model == current) return@launch  // no change — don't show toast
            try {
                modelPreferences.setPreferredModel(model)
                val label = model?.displayName ?: "Auto"
                _saveSuccess.tryEmit("Preference set to $label — takes effect on next launch")
            } catch (e: IOException) {
                Log.e("KernelAI", "SettingsViewModel: failed to save model preference", e)
                _saveError.tryEmit("Couldn't save preference — please try again")
            }
        }
    }

    fun downloadModel(model: KernelModel) {
        modelDownloadManager.startDownload(model)
        _saveSuccess.tryEmit("Downloading ${model.displayName}…")
    }

    /** Signs the user out of HuggingFace and clears the stored token. */
    fun signOutHuggingFace() {
        authRepository.signOut()
        _saveSuccess.tryEmit("Signed out of HuggingFace")
    }

    /**
     * Starts the HuggingFace OAuth flow by launching a Chrome Custom Tab via AppAuth.
     * The result is delivered back to [MainActivity.onNewIntent] via a PendingIntent,
     * bypassing [ActivityResultLauncher] to survive Samsung's memory management (#195).
     *
     * Must be called on the main thread (button-click handler).
     */
    fun startAuth() = authRepository.startAuthFlow()
}
