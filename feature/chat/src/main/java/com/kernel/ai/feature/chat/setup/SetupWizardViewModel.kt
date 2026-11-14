package com.kernel.ai.feature.chat.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.inference.setup.SetupWizardFlow
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceOutputEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val KEY_SELECTED_CHOICE = "selected_download_choice"

/** One screen of the setup wizard. [loaded] is false until persisted progress is read. */
data class SetupWizardUiState(
    val loaded: Boolean = false,
    val step: SetupStep = SetupStep.HOW_IT_WORKS,
    /** Radio selection on the download step. Wi-Fi is preselected; nothing is saved until Continue. */
    val choice: DownloadNetworkChoice = DownloadNetworkChoice.WIFI_ONLY,
    /** True when a network choice is already saved (a re-run from Settings, or a finished first run). */
    val choiceRecorded: Boolean = false,
    /** Models the first-run plan downloads, shared with the auto-queue (#1855). Loaded with the progress. */
    val plannedModels: List<KernelModel> = emptyList(),
    /** Voice packs the first run queues for the default output engine (Cori on Piper tiers). */
    val plannedVoices: List<SherpaPiperVoice> = emptyList(),
)

/**
 * Drives the first-run setup wizard (#1855, part 1: how it works and download consent).
 * Progress is restored from [SetupWizardPreferences] so process death resumes the same step.
 * The selected network is kept in [SavedStateHandle] until Continue or Skip saves it, so process death
 * during the notification prompt keeps the selection.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
    private val setupWizardPreferences: SetupWizardPreferences,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupWizardUiState())
    val uiState: StateFlow<SetupWizardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val progress = setupWizardPreferences.progress.first()
            // A completed wizard (re-run from Settings) always starts from the first step.
            val start = if (progress.completed) SetupStep.HOW_IT_WORKS else progress.step
            val stored = downloadNetworkPreferences.choice.first()
            val selected = savedStateHandle.get<String>(KEY_SELECTED_CHOICE)
                ?.let { DownloadNetworkChoice.fromStorageName(it) }
            _uiState.value = _uiState.value.copy(
                loaded = true,
                step = start,
                choice = selected ?: stored ?: DownloadNetworkChoice.WIFI_ONLY,
                choiceRecorded = stored != null,
                plannedModels = downloadManager.plannedFirstRunModels(),
                plannedVoices = plannedVoicesFor(downloadManager.deviceTier),
            )
        }
    }

    fun onChoiceSelected(choice: DownloadNetworkChoice) {
        savedStateHandle[KEY_SELECTED_CHOICE] = choice.name
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
     * (never paused forever). An explicit earlier choice is kept. [onDone] receives the choice that
     * applies now, so the caller can tell the user the truth.
     */
    fun onSkip(onDone: (DownloadNetworkChoice) -> Unit) {
        viewModelScope.launch {
            val existing = downloadNetworkPreferences.choice.first()
            val effective = existing ?: DownloadNetworkChoice.WIFI_ONLY
            if (existing == null) downloadManager.setDownloadNetworkChoice(effective)
            setupWizardPreferences.markCompleted()
            onDone(effective)
        }
    }

    companion object {
        /**
         * The Piper voice the first run queues: Cori on tiers whose default output engine is Piper,
         * none where the default is Android TTS (low-power devices).
         */
        internal fun plannedVoicesFor(tier: HardwareTier): List<SherpaPiperVoice> =
            if (VoiceOutputEngine.defaultForTier(tier) == VoiceOutputEngine.SherpaExperimental) {
                listOf(SherpaPiperVoice.CoriHigh)
            } else {
                emptyList()
            }
    }
}
