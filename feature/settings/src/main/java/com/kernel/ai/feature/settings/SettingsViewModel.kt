package com.kernel.ai.feature.settings

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.prefs.ModelPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hardwareProfileDetector: HardwareProfileDetector,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val authRepository: HuggingFaceAuthRepository,
) : ViewModel() {

    data class SettingsUiState(
        val activeModelLabel: String = "",
        val activeBackend: String = "",
        val activeTier: String = "",
        val preferredModel: KernelModel? = null,   // null = auto
        val e4bDownloaded: Boolean = false,
        /** True when a valid HF token is stored. */
        val hfAuthenticated: Boolean = false,
        /** HuggingFace username from OIDC id_token, or null. */
        val hfUsername: String? = null,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        modelPreferences.preferredConversationModel,
        modelDownloadManager.downloadStates,
        authRepository.isAuthenticated,
        authRepository.username,
    ) { preferredModel, downloadStates, hfAuthenticated, hfUsername ->
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
            hfAuthenticated = hfAuthenticated,
            hfUsername = hfUsername,
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

    /** Signs the user out of HuggingFace and clears the stored token. */
    fun signOutHuggingFace() {
        authRepository.signOut()
        _saveSuccess.tryEmit("Signed out of HuggingFace")
    }

    /** Returns an [Intent] for the HuggingFace OAuth Chrome Custom Tab flow. */
    fun buildAuthIntent(): Intent = authRepository.buildAuthIntent()

    /**
     * Called with the result [Intent] from [android.app.Activity.RESULT_OK] after AppAuth
     * redirects back to the app. Performs the token exchange and updates auth state.
     */
    fun handleAuthResponse(intent: Intent) {
        viewModelScope.launch {
            authRepository.handleAuthResponse(intent)
                .onFailure { e ->
                    Log.e("KernelAI", "SettingsViewModel: HF auth failed", e)
                    _saveError.tryEmit("Sign-in failed: ${e.message}")
                }
                .onSuccess {
                    _saveSuccess.tryEmit("Signed in to HuggingFace ✓")
                }
        }
    }
}
