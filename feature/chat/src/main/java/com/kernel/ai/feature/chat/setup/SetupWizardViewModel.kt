package com.kernel.ai.feature.chat.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.inference.setup.SetupWizardFlow
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.voice.PiperVoiceSetup
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val KEY_SELECTED_CHOICE = "selected_download_choice"
private const val KEY_SELECTED_INPUT = "selected_speech_input"
private const val KEY_SELECTED_OUTPUT = "selected_speech_output"

/** One screen of the setup wizard. [loaded] is false until persisted progress is read. */
data class SetupWizardUiState(
    val loaded: Boolean = false,
    val step: SetupStep = SetupStep.HOW_IT_WORKS,
    /** Speech input choices this device can use (#1855 part 2). Automatic is always among them. */
    val inputOptions: List<VoiceInputEngine> = listOf(VoiceInputEngine.Auto),
    /** Why Android native is not offered, or null when it is offered. */
    val inputReason: String? = null,
    val inputEngine: VoiceInputEngine = VoiceInputEngine.Auto,
    /** Speech output choices this device can use. Cori is always among them. */
    val outputOptions: List<VoiceOutputEngine> = listOf(VoiceOutputEngine.SherpaExperimental),
    val outputEngine: VoiceOutputEngine = VoiceOutputEngine.SherpaExperimental,
    /** Radio selection on the download step. Wi-Fi is preselected; nothing is saved until Continue. */
    val choice: DownloadNetworkChoice = DownloadNetworkChoice.WIFI_ONLY,
    /** True when a network choice is already saved (a re-run from Settings, or a finished first run). */
    val choiceRecorded: Boolean = false,
    /** Models the first-run plan still has to download, shared with the auto-queue (#1855). */
    val plannedModels: List<KernelModel> = emptyList(),
    /** Voice packs still to download for the selected output engine (#1855). */
    val plannedVoices: List<SherpaPiperVoice> = emptyList(),
)

/**
 * Drives the first-run setup wizard (#1855): how it works, speech input and output, and download consent.
 * Progress is restored from [SetupWizardPreferences] so process death resumes the same step.
 *
 * Selections (download network, speech input, speech output) are kept in [SavedStateHandle] until Finish or
 * Skip saves them, so Back never stores anything. Finish and Skip save the speech choices first, then the
 * network choice (which releases the consent wait in VoiceDefaultsProvisioner), then start Cori if chosen.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
    private val setupWizardPreferences: SetupWizardPreferences,
    private val voicePackDownloadManager: SherpaVoicePackDownloadManager,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val piperVoiceSetup: PiperVoiceSetup,
    private val setupVoiceDevice: SetupVoiceDevice,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupWizardUiState())
    val uiState: StateFlow<SetupWizardUiState> = _uiState.asStateFlow()

    /** Voice pack states captured at load; Cori counts as on disk only when its pack is downloaded. */
    private var voiceStates: Map<SherpaPiperVoice, VoicePackDownloadState> = emptyMap()

    init {
        viewModelScope.launch {
            val progress = setupWizardPreferences.progress.first()
            // A completed wizard (re-run from Settings) always starts from the first step.
            val start = if (progress.completed) SetupStep.HOW_IT_WORKS else progress.step
            val stored = downloadNetworkPreferences.choice.first()
            val selected = savedStateHandle.get<String>(KEY_SELECTED_CHOICE)
                ?.let { DownloadNetworkChoice.fromStorageName(it) }

            val capabilities = setupVoiceDevice.speechInputCapabilities()
            val inputOptions = SetupSpeechOptions.inputOptions(capabilities)
            val outputOptions = SetupSpeechOptions.outputOptions(setupVoiceDevice.hasSystemTtsEngine())
            val storedInput = voiceInputPreferences.selectedEngine.first()
            val storedOutput = voiceOutputPreferences.selectedEngine.first()
            val savedInput = savedStateHandle.get<String>(KEY_SELECTED_INPUT)
                ?.let { VoiceInputEngine.fromStorage(it) }
            val savedOutput = savedStateHandle.get<String>(KEY_SELECTED_OUTPUT)
                ?.let { name -> VoiceOutputEngine.entries.firstOrNull { it.name == name } }

            voiceStates = voicePackDownloadManager.downloadStates.value
            val outputEngine = savedOutput?.let { SetupSpeechOptions.resolve(it, outputOptions) }
                ?: SetupSpeechOptions.initialOutput(storedOutput, setupVoiceDevice.hardwareTier(), outputOptions)
            _uiState.value = _uiState.value.copy(
                loaded = true,
                step = start,
                inputOptions = inputOptions,
                inputReason = SetupSpeechOptions.inputReason(capabilities),
                inputEngine = SetupSpeechOptions.resolve(savedInput ?: storedInput, inputOptions),
                outputOptions = outputOptions,
                outputEngine = outputEngine,
                choice = selected ?: stored ?: DownloadNetworkChoice.WIFI_ONLY,
                choiceRecorded = stored != null,
                plannedModels = downloadManager.plannedFirstRunModels(),
                plannedVoices = plannedVoicesFor(outputEngine) { isDownloaded(it) },
            )
        }
    }

    private fun isDownloaded(voice: SherpaPiperVoice): Boolean =
        voiceStates[voice] is VoicePackDownloadState.Downloaded

    fun onInputSelected(engine: VoiceInputEngine) {
        savedStateHandle[KEY_SELECTED_INPUT] = engine.name
        _uiState.value = _uiState.value.copy(inputEngine = engine)
    }

    fun onOutputSelected(engine: VoiceOutputEngine) {
        savedStateHandle[KEY_SELECTED_OUTPUT] = engine.name
        _uiState.value = _uiState.value.copy(
            outputEngine = engine,
            plannedVoices = plannedVoicesFor(engine) { isDownloaded(it) },
        )
    }

    fun onChoiceSelected(choice: DownloadNetworkChoice) {
        savedStateHandle[KEY_SELECTED_CHOICE] = choice.name
        _uiState.value = _uiState.value.copy(choice = choice)
    }

    /**
     * Advances one step. Nothing is saved here: choices are stored only by [onFinish] or [onSkip].
     * On the last step, finishes the wizard, then [onDone].
     */
    fun onContinue(onDone: () -> Unit) {
        val state = _uiState.value
        val next = SetupWizardFlow.next(state.step)
        if (next != null) {
            _uiState.value = state.copy(step = next)
            viewModelScope.launch { setupWizardPreferences.saveStep(next) }
            return
        }
        onFinish(onDone)
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

    private fun onFinish(onDone: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            saveSpeechChoices(state)
            downloadManager.setDownloadNetworkChoice(state.choice)
            startChosenVoice(state)
            setupWizardPreferences.markCompleted()
            onDone()
        }
    }

    /**
     * Ends the wizard. With no choice recorded yet, saves Wi-Fi only so models still download on Wi-Fi
     * (never paused forever). An explicit earlier choice is kept. [onDone] receives the choice that
     * applies now, so the caller can tell the user the truth.
     */
    fun onSkip(onDone: (DownloadNetworkChoice) -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            saveSpeechChoices(state)
            val existing = downloadNetworkPreferences.choice.first()
            val effective = existing ?: DownloadNetworkChoice.WIFI_ONLY
            if (existing == null) downloadManager.setDownloadNetworkChoice(effective)
            startChosenVoice(state)
            setupWizardPreferences.markCompleted()
            onDone(effective)
        }
    }

    /** Saves the speech choices before the network choice, so VoiceDefaultsProvisioner reads them after consent. */
    private suspend fun saveSpeechChoices(state: SetupWizardUiState) {
        voiceInputPreferences.setSelectedEngine(state.inputEngine)
        voiceOutputPreferences.setSelectedEngine(state.outputEngine)
    }

    /**
     * Starts Cori only after the network choice is saved, so the download follows consent. Starts nothing
     * for Android TTS. Cori already on disk is left alone (PiperVoiceSetup checks state).
     */
    private suspend fun startChosenVoice(state: SetupWizardUiState) {
        piperVoiceSetup.onEngineChosen(state.outputEngine)
    }

    companion object {
        /**
         * Voice packs the first run still has to download for [engine]: Cori on the Sherpa output engine,
         * none on Android TTS. Packs already on disk are excluded, so the estimate matches the work left.
         */
        internal fun plannedVoicesFor(
            engine: VoiceOutputEngine,
            isDownloaded: (SherpaPiperVoice) -> Boolean,
        ): List<SherpaPiperVoice> =
            if (engine == VoiceOutputEngine.SherpaExperimental) {
                listOf(SherpaPiperVoice.CoriHigh).filterNot(isDownloaded)
            } else {
                emptyList()
            }
    }
}
