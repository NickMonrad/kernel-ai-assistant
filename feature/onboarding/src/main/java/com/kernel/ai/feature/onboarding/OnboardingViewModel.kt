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
        val gemmaDownloadState: DownloadState = DownloadState.NotDownloaded,
        val routerDownloadState: DownloadState = DownloadState.NotDownloaded,
    ) {
        val overallProgress: Float
            get() {
                val gemmaP = when (gemmaDownloadState) {
                    is DownloadState.Downloading -> gemmaDownloadState.progress
                    is DownloadState.Downloaded -> 1f
                    else -> 0f
                }
                val routerP = when (routerDownloadState) {
                    is DownloadState.Downloading -> routerDownloadState.progress
                    is DownloadState.Downloaded -> 1f
                    else -> 0f
                }
                // FunctionGemma is ~289MB, Gemma-4 E2B is ~2.4GB — weight accordingly
                return (gemmaP * 0.89f) + (routerP * 0.11f)
            }
        val allDownloaded: Boolean
            get() = gemmaDownloadState is DownloadState.Downloaded &&
                    routerDownloadState is DownloadState.Downloaded
        val anyError: Boolean
            get() = gemmaDownloadState is DownloadState.Error ||
                    routerDownloadState is DownloadState.Error
        val isDownloading: Boolean
            get() = gemmaDownloadState is DownloadState.Downloading ||
                    routerDownloadState is DownloadState.Downloading
    }

    val uiState: StateFlow<OnboardingUiState> = combine(
        authRepository.isAuthenticated,
        authRepository.username,
        modelDownloadManager.downloadStates,
    ) { isAuthenticated, username, downloadStates ->
        OnboardingUiState(
            isAuthenticated = isAuthenticated,
            username = username,
            gemmaDownloadState = downloadStates[KernelModel.GEMMA_4_E2B] ?: DownloadState.NotDownloaded,
            routerDownloadState = downloadStates[KernelModel.FUNCTION_GEMMA_270M] ?: DownloadState.NotDownloaded,
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

    /** Emits an [OnboardingEvent.AuthError] directly — used when the screen detects a
     *  RESULT_OK callback that carries no data intent. */
    fun emitAuthError(message: String) {
        _events.tryEmit(OnboardingEvent.AuthError(message))
    }

    fun signOut() {
        authRepository.signOut()
    }

    /**
     * Starts downloading a [model]. If the model is gated and the user is not signed in,
     * emits [OnboardingEvent.GatedModelRequiresAuth] instead.
     *
     * Always queues [KernelModel.FUNCTION_GEMMA_270M] alongside the primary model so that
     * [com.kernel.ai.core.inference.FunctionGemmaRouter] can initialise correctly and skills fire.
     */
    fun startDownload(model: KernelModel) {
        if (model.isGated && !uiState.value.isAuthenticated) {
            _events.tryEmit(OnboardingEvent.GatedModelRequiresAuth(model))
            return
        }
        modelDownloadManager.startDownload(model)
        // FunctionGemma is always required and never gated — queue alongside any Gemma-4 download
        if (model != KernelModel.FUNCTION_GEMMA_270M) {
            modelDownloadManager.startDownload(KernelModel.FUNCTION_GEMMA_270M)
        }
    }

    sealed interface OnboardingEvent {
        data object AuthSuccess : OnboardingEvent
        data class AuthError(val message: String) : OnboardingEvent
        /** Fired when the user tries to download a gated model without signing in. */
        data class GatedModelRequiresAuth(val model: KernelModel) : OnboardingEvent
    }
}
