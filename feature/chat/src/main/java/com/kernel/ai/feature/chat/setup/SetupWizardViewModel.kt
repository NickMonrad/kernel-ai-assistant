package com.kernel.ai.feature.chat.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.DownloadConsent
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.inference.setup.SetupWizardFlow
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One screen of the setup wizard. [loaded] is false until persisted progress is read. */
data class SetupWizardUiState(
    val loaded: Boolean = false,
    val step: SetupStep = SetupStep.HOW_IT_WORKS,
    /** Radio selection on the download step. Wi-Fi is preselected; nothing is saved until Continue. */
    val choice: DownloadNetworkChoice = DownloadNetworkChoice.WIFI_ONLY,
    val plannedModels: List<KernelModel> = emptyList(),
)

/**
 * Drives the first-run setup wizard (#1855, part 1: how it works and download consent).
 * Progress is restored from [SetupWizardPreferences] so process death resumes the same step.
 * The download choice is only saved when the user presses Continue on the download step.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
    private val setupWizardPreferences: SetupWizardPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SetupWizardUiState(plannedModels = DownloadConsent.plannedModels(downloadManager.deviceTier)),
    )
    val uiState: StateFlow<SetupWizardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val progress = setupWizardPreferences.progress.first()
            // A completed wizard (re-run from Settings) always starts from the first step.
            val start = if (progress.completed) SetupStep.HOW_IT_WORKS else progress.step
            val choice = downloadNetworkPreferences.choice.first() ?: DownloadNetworkChoice.WIFI_ONLY
            _uiState.value = _uiState.value.copy(loaded = true, step = start, choice = choice)
        }
    }

    fun onChoiceSelected(choice: DownloadNetworkChoice) {
        _uiState.value = _uiState.value.copy(choice = choice)
    }

    /** Advances one step. On the last step, saves the download choice and completes the wizard, then [onDone]. */
    fun onContinue(onDone: () -> Unit) {
        val state = _uiState.value
        val next = SetupWizardFlow.next(state.step)
        if (next != null) {
            _uiState.value = state.copy(step = next)
            viewModelScope.launch { setupWizardPreferences.saveStep(next) }
            return
        }
        viewModelScope.launch {
            downloadManager.setDownloadNetworkChoice(state.choice)
            setupWizardPreferences.markCompleted()
            onDone()
        }
    }

    /** Goes back one step. On the first step, leaves the wizard via [onExit] without completing it. */
    fun onBack(onExit: () -> Unit) {
        val previous = SetupWizardFlow.previous(_uiState.value.step)
        if (previous == null) {
            onExit()
            return
        }
        _uiState.value = _uiState.value.copy(step = previous)
        viewModelScope.launch { setupWizardPreferences.saveStep(previous) }
    }

    /**
     * Ends the wizard. With no choice recorded yet, saves Wi-Fi only so models still download on Wi-Fi
     * (never paused forever). An explicit earlier choice is kept.
     */
    fun onSkip(onDone: () -> Unit) {
        viewModelScope.launch {
            if (downloadNetworkPreferences.choice.first() == null) {
                downloadManager.setDownloadNetworkChoice(DownloadNetworkChoice.WIFI_ONLY)
            }
            setupWizardPreferences.markCompleted()
            onDone()
        }
    }
}
