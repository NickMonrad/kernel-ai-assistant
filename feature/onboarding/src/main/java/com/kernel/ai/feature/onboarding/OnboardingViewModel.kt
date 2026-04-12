package com.kernel.ai.feature.onboarding

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager,
    private val authRepository: HuggingFaceAuthRepository,
) : ViewModel() {

    data class OnboardingUiState(
        val isAuthenticated: Boolean = false,
        val username: String? = null,
        /** Download progress 0..1 for the required E2B model. */
        val downloadProgress: Float = 0f,
        val downloadState: DownloadState = DownloadState.NotDownloaded,
    )

    val uiState: StateFlow<OnboardingUiState> = combine(
        authRepository.isAuthenticated,
        authRepository.username,
        modelDownloadManager.downloadStates,
    ) { isAuthenticated, username, downloadStates ->
        val e2bState = downloadStates[KernelModel.GEMMA_4_E2B] ?: DownloadState.NotDownloaded
        val progress = when (e2bState) {
            is DownloadState.Downloading -> e2bState.progress
            is DownloadState.Downloaded -> 1f
            else -> 0f
        }
        OnboardingUiState(
            isAuthenticated = isAuthenticated,
            username = username,
            downloadProgress = progress,
            downloadState = e2bState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnboardingUiState(),
    )

    private val _events = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

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
                    Log.e("KernelAI", "OnboardingViewModel: HF auth failed", e)
                    _events.tryEmit(OnboardingEvent.AuthError("Sign-in failed: ${e.message}"))
                }
                .onSuccess {
                    _events.tryEmit(OnboardingEvent.AuthSuccess)
                }
        }
    }

    fun signOut() {
        authRepository.signOut()
    }

    /**
     * Starts downloading a [model]. If the model is gated and the user is not signed in,
     * emits [OnboardingEvent.GatedModelRequiresAuth] instead.
     */
    fun startDownload(model: KernelModel) {
        if (model.isGated && !uiState.value.isAuthenticated) {
            _events.tryEmit(OnboardingEvent.GatedModelRequiresAuth(model))
            return
        }
        modelDownloadManager.startDownload(model)
    }

    sealed interface OnboardingEvent {
        data object AuthSuccess : OnboardingEvent
        data class AuthError(val message: String) : OnboardingEvent
        /** Fired when the user tries to download a gated model without signing in. */
        data class GatedModelRequiresAuth(val model: KernelModel) : OnboardingEvent
    }
}
