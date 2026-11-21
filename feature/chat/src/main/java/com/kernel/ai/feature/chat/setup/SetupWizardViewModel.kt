package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.voice.VoiceDefaultsProvisioner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.DownloadConsent
import com.kernel.ai.core.inference.download.DownloadState
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
import com.kernel.ai.core.voice.WakeWordPreferences
import com.kernel.ai.core.voice.queueHeyJandalSttModel
import com.kernel.ai.core.voice.InflectMicroSetup
import com.kernel.ai.core.voice.heyJandalDownloadModels
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
private const val KEY_SELECTED_WAKE_WORD = "selected_wake_word"
private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
private const val KEY_ONLINE_SPEECH = "allow_online_speech"

/** One screen of the setup wizard. [loaded] is false until persisted progress is read. */
data class SetupWizardUiState(
    val loaded: Boolean = false,
    val step: SetupStep = SetupStep.HOW_IT_WORKS,
    /** Speech input choices this device can use (#1855 part 2). Automatic is always among them. */
    val inputOptions: List<VoiceInputEngine> = listOf(VoiceInputEngine.Auto),
    /** Why Android native is not offered, or null when it is offered. */
    val inputReason: String? = null,
    /** Null when a stored speech input is not offered here: the wizard keeps it and highlights nothing. */
    val inputEngine: VoiceInputEngine? = null,
    /** Speech output choices this device can use. Cori is always among them. */
    val outputOptions: List<VoiceOutputEngine> = listOf(VoiceOutputEngine.SherpaExperimental),
    /** Null when a stored speech output is not offered here: the wizard keeps it and highlights nothing. */
    val outputEngine: VoiceOutputEngine? = null,
    /** Radio selection on the download step. Wi-Fi is preselected; nothing is saved until Continue. */
    val choice: DownloadNetworkChoice = DownloadNetworkChoice.WIFI_ONLY,
    /** True when a network choice is already saved (a re-run from Settings, or a finished first run). */
    val choiceRecorded: Boolean = false,
    /** Models the first-run plan still has to download, shared with the auto-queue (#1855). */
    val plannedModels: List<KernelModel> = emptyList(),
    /** Voice packs still to download for the selected output engine (#1855). */
    val plannedVoices: List<SherpaPiperVoice> = emptyList(),
    /** Live phone state for the Make Jandal yours rows (#1855 part 3). Refreshed on load and on every return. */
    val platform: MakeYoursPlatform = MakeYoursPlatform(),
    /** Hey Jandal as stored before this run. Written only by Finish, and only when it changed. */
    val wakeWordStored: Boolean = false,
    /** Hey Jandal choice on its row. Equals [wakeWordStored] until the user changes it in this run. */
    val wakeWordChoice: Boolean = false,
    /** True once the notification row launched the system prompt in this run; Finish then skips the download gate. */
    val notificationsAsked: Boolean = false,
    /** Online speech switch on the speech step. Saved on Finish or Skip, never on Back (#1936). */
    val allowOnlineSpeech: Boolean = false,
    /**
     * Bytes each speech output still has to download, keyed by output. 0 means the output is already on disk
     * ("Downloaded"); a missing key means the output has no download ("No download"). Cori and Inflect (#1931).
     */
    val outputSizes: Map<VoiceOutputEngine, Long> = emptyMap(),
    /** Extra download the Hey Jandal card names: the files turning it on still needs, beyond the step-3 plan (#1931). */
    val heyJandalExtraBytes: Long = 0L,
    /** The Zipformer part of [heyJandalExtraBytes]: non-zero only with Android native input. Named by Why?. */
    val heyJandalZipformerBytes: Long = 0L,
    /**
     * Total for the welcome screen, excluding files already on disk: on a first run the plan at the default choices
     * for this phone; on a re-run from Settings, what the current choices still have to download.
     */
    val welcomeTotalBytes: Long = 0L,
    /** True when the wizard was opened from Settings after a finished first run. */
    val isRerun: Boolean = false,
)

/**
 * Drives the first-run setup wizard (#1855): how it works, speech input and output, download consent, and
 * optional Make Jandal yours rows. Progress is restored from [SetupWizardPreferences] so process death resumes
 * the same step.
 *
 * Selections (download network, speech input, speech output, Hey Jandal) are kept in [SavedStateHandle] until
 * Finish or Skip saves them, so Back never stores anything. Finish saves the speech choices, then the network
 * choice, then queues the chosen downloads (Zipformer for Automatic, Cori for Sherpa), then applies a changed
 * Hey Jandal choice. Skip saves speech and network choices only, as before, and never applies Hey Jandal.
 *
 * The Make Jandal yours rows request nothing themselves. The screen launches each system prompt or page on a
 * tap; this view model only reads state ([refreshPlatform]) and records choices.
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
    private val voiceDefaultsProvisioner: VoiceDefaultsProvisioner,
    private val wakeWordPreferences: WakeWordPreferences,
    private val platformReader: MakeYoursPlatformReader,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupWizardUiState())
    val uiState: StateFlow<SetupWizardUiState> = _uiState.asStateFlow()

    /** Voice pack states captured at load; Cori counts as on disk only when its pack is downloaded. */
    private var voiceStates: Map<SherpaPiperVoice, VoicePackDownloadState> = emptyMap()

    /** Speech input and output stored before this wizard run, or null when nothing was stored (#1868). */
    private var storedInput: VoiceInputEngine? = null
    private var storedOutput: VoiceOutputEngine? = null

    /** Network choice stored before this run, or null on a first run. */
    private var storedChoice: DownloadNetworkChoice? = null

    /** Every first-run model before the Zipformer filter applied by the speech input choice. */
    private var allPlannedModels: List<KernelModel> = emptyList()
    private var releaseBuild: Boolean = false

    init {
        viewModelScope.launch {
            val progress = setupWizardPreferences.progress.first()
            // A completed wizard (re-run from Settings) always starts from the first step.
            val start = if (progress.completed) SetupStep.HOW_IT_WORKS else progress.step
            val stored = downloadNetworkPreferences.choice.first()
            storedChoice = stored
            val selected = savedStateHandle.get<String>(KEY_SELECTED_CHOICE)
                ?.let { DownloadNetworkChoice.fromStorageName(it) }

            val nativeReady = setupVoiceDevice.androidNativeInputReady()
            val isRelease = setupVoiceDevice.isReleaseBuild()
            releaseBuild = isRelease
            val inputOptions = SetupSpeechOptions.inputOptions(nativeReady, isRelease)
            val tier = setupVoiceDevice.hardwareTier()
            val outputOptions = SetupSpeechOptions.outputOptions(
                hasSystemTts = setupVoiceDevice.hasSystemTtsEngine(),
                isRelease = isRelease,
                inflectEligible = setupVoiceDevice.inflectEligible(),
            )
            val currentInput = if (voiceInputPreferences.hasStoredEngine()) {
                voiceInputPreferences.selectedEngine.first()
            } else {
                null
            }
            val currentOutput = if (voiceOutputPreferences.hasStoredEngine()) {
                voiceOutputPreferences.selectedEngine.first()
            } else {
                null
            }
            storedInput = currentInput
            storedOutput = currentOutput

            val savedInput = savedStateHandle.get<String>(KEY_SELECTED_INPUT)
                ?.let { VoiceInputEngine.fromStorage(it) }
            val savedOutput = savedStateHandle.get<String>(KEY_SELECTED_OUTPUT)
                ?.let { name -> VoiceOutputEngine.entries.firstOrNull { it.name == name } }

            // Selected choices win; then the stored choice when offered; then the tier default. A stored
            // choice that is not offered shows nothing selected, and is never overwritten (see inputToSave).
            val inputEngine = when {
                savedInput != null -> SetupSpeechOptions.offered(savedInput, inputOptions)
                    ?: if (isRelease) VoiceInputEngine.AndroidNative else null
                currentInput == null -> inputOptions.first()
                else -> SetupSpeechOptions.offered(currentInput, inputOptions)
            }
            val outputEngine = when {
                savedOutput != null ->
                    SetupSpeechOptions.offered(savedOutput, outputOptions)
                        ?: SetupSpeechOptions.defaultOutput(tier, outputOptions)
                currentOutput == null -> SetupSpeechOptions.defaultOutput(tier, outputOptions)
                else -> SetupSpeechOptions.offered(currentOutput, outputOptions)
            }

            val wakeStored = wakeWordPreferences.heyJandalEnabled.first()
            voiceStates = voicePackDownloadManager.downloadStates.value
            allPlannedModels = downloadManager.plannedFirstRunModels() +
                VoiceDefaultsProvisioner.asr4allSetupModels().filterNot { isModelDownloaded(it) }
            val input = effectiveInput(inputEngine)
            val output = effectiveOutput(outputEngine)
            val plannedModels = plannedModelsFor(input, output)
            val plannedVoices = plannedVoicesFor(output)
            val defaultInput = inputOptions.first()
            val defaultOutput = SetupSpeechOptions.defaultOutput(tier, outputOptions)
            val welcomeTotal = if (progress.completed) {
                estimateBytes(plannedModels, plannedVoices)
            } else {
                estimateBytes(plannedModelsFor(defaultInput, defaultOutput), plannedVoicesFor(defaultOutput))
            }
            _uiState.value = _uiState.value.copy(
                loaded = true,
                step = start,
                inputOptions = inputOptions,
                inputReason = SetupSpeechOptions.inputReason(nativeReady, isRelease),
                inputEngine = inputEngine,
                outputOptions = outputOptions,
                outputEngine = outputEngine,
                choice = selected ?: stored ?: DownloadNetworkChoice.WIFI_ONLY,
                choiceRecorded = stored != null,
                plannedModels = plannedModels,
                plannedVoices = plannedVoices,
                platform = platformReader.read(),
                wakeWordStored = wakeStored,
                wakeWordChoice = savedStateHandle.get<Boolean>(KEY_SELECTED_WAKE_WORD) ?: wakeStored,
                notificationsAsked = savedStateHandle.get<Boolean>(KEY_NOTIFICATIONS_ASKED) == true,
                allowOnlineSpeech = savedStateHandle.get<Boolean>(KEY_ONLINE_SPEECH)
                    ?: voiceInputPreferences.allowOnlineSpeechFallback.first(),
                outputSizes = outputSizes(),
                heyJandalExtraBytes = heyJandalExtraBytes(input, plannedModels),
                heyJandalZipformerBytes = heyJandalZipformerBytes(input, plannedModels),
                welcomeTotalBytes = welcomeTotal,
                isRerun = progress.completed,
            )
        }
    }

    private fun estimateBytes(models: List<KernelModel>, voices: List<SherpaPiperVoice>): Long =
        DownloadConsent.firstRunEstimateBytes(models = models, voiceBytes = voices.sumOf { it.approxDownloadBytes })

    private fun isDownloaded(voice: SherpaPiperVoice): Boolean =
        voiceStates[voice] is VoicePackDownloadState.Downloaded

    /** The input the app will use: the shown choice, else the stored one (a kept choice). */
    private fun effectiveInput(engine: VoiceInputEngine?): VoiceInputEngine =
        engine ?: storedInput ?: VoiceInputEngine.Auto

    /** The output the app will use: the shown choice, else the stored one (a kept choice). */
    private fun effectiveOutput(engine: VoiceOutputEngine?): VoiceOutputEngine? = engine ?: storedOutput

    /**
     * Zipformer and asr4all files are needed only when the effective input uses them (see the provisioner's
     * needsZipformer and needsAsr4all). Inflect Micro's
     * graph files join the plan when Inflect is the output, so the step-3 rows and totals include them (#1931).
     */
    private fun plannedModelsFor(input: VoiceInputEngine, output: VoiceOutputEngine?): List<KernelModel> {
        val asr4all = VoiceDefaultsProvisioner.asr4allSetupModels()
        val speech = allPlannedModels.filter { model ->
            val isZipformer = model in VoiceDefaultsProvisioner.zipformerModels()
            val isAsr4all = model in asr4all
            (!isZipformer || VoiceDefaultsProvisioner.needsZipformer(input)) &&
                (!isAsr4all || VoiceDefaultsProvisioner.needsAsr4all(input))
        }
        val inflect = if (output == VoiceOutputEngine.InflectMicroExperimental) {
            InflectMicroSetup.kernelModels().filterNot { isModelDownloaded(it) }
        } else {
            emptyList()
        }
        return speech + inflect.filterNot { it in speech }
    }

    private fun isModelDownloaded(model: KernelModel): Boolean =
        downloadManager.downloadStates.value[model] is DownloadState.Downloaded

    /**
     * Bytes each speech output still has to download. Cori: its pack, or 0 when on disk. Inflect: its missing
     * graph files plus Cori when Cori is not on disk, or 0 when all are on disk (#1931). Android TTS has no entry.
     */
    private fun outputSizes(): Map<VoiceOutputEngine, Long> {
        val cori = if (isDownloaded(SherpaPiperVoice.CoriHigh)) 0L else SherpaPiperVoice.CoriHigh.approxDownloadBytes
        val inflectFiles = InflectMicroSetup.kernelModels().filterNot { isModelDownloaded(it) }.sumOf { it.approxSizeBytes }
        return mapOf(
            VoiceOutputEngine.SherpaExperimental to cori,
            VoiceOutputEngine.InflectMicroExperimental to inflectFiles + cori,
        )
    }

    /**
     * Extra download the Hey Jandal card names: the files turning it on still needs for this input
     * ([heyJandalDownloadModels]: the verifier, plus Zipformer in debug builds unless an online Sherpa recognizer is chosen), less
     * files on disk and files already in the step-3 plan, so no byte is counted twice (#1931).
     */
    private fun heyJandalExtraBytes(input: VoiceInputEngine, plannedModels: List<KernelModel>): Long =
        heyJandalMissing(input, plannedModels).sumOf { it.approxSizeBytes }

    /** Zipformer bytes Hey Jandal adds when Android native is the input (the Why? sheet names them). */
    private fun heyJandalZipformerBytes(input: VoiceInputEngine, plannedModels: List<KernelModel>): Long =
        if (input == VoiceInputEngine.AndroidNative) {
            heyJandalMissing(input, plannedModels)
                .filter { it in VoiceDefaultsProvisioner.zipformerModels() }
                .sumOf { it.approxSizeBytes }
        } else {
            0L
        }

    private fun heyJandalMissing(input: VoiceInputEngine, plannedModels: List<KernelModel>): List<KernelModel> =
        heyJandalDownloadModels(input, releaseBuild).filterNot { isModelDownloaded(it) || it in plannedModels }

    private fun plannedVoicesFor(output: VoiceOutputEngine?): List<SherpaPiperVoice> =
        output?.let { plannedVoicesFor(it) { voice -> isDownloaded(voice) } } ?: emptyList()

    /** True once the user changed this choice in this wizard run (survives process death). */
    private fun touched(key: String): Boolean = savedStateHandle.contains(key)

    fun onInputSelected(engine: VoiceInputEngine) {
        savedStateHandle[KEY_SELECTED_INPUT] = engine.name
        val models = plannedModelsFor(engine, effectiveOutput(_uiState.value.outputEngine))
        _uiState.value = _uiState.value.copy(
            inputEngine = engine,
            plannedModels = models,
            heyJandalExtraBytes = heyJandalExtraBytes(engine, models),
            heyJandalZipformerBytes = heyJandalZipformerBytes(engine, models),
        )
    }

    fun onOutputSelected(engine: VoiceOutputEngine) {
        savedStateHandle[KEY_SELECTED_OUTPUT] = engine.name
        val input = effectiveInput(_uiState.value.inputEngine)
        val models = plannedModelsFor(input, engine)
        _uiState.value = _uiState.value.copy(
            outputEngine = engine,
            plannedModels = models,
            plannedVoices = plannedVoicesFor(engine) { isDownloaded(it) },
            heyJandalExtraBytes = heyJandalExtraBytes(input, models),
            heyJandalZipformerBytes = heyJandalZipformerBytes(input, models),
        )
    }

    /** Online speech switch on the speech step. Written only by Finish or Skip (#1936). */
    fun onOnlineSpeechChanged(allowed: Boolean) {
        savedStateHandle[KEY_ONLINE_SPEECH] = allowed
        _uiState.value = _uiState.value.copy(allowOnlineSpeech = allowed)
    }

    fun onChoiceSelected(choice: DownloadNetworkChoice) {
        savedStateHandle[KEY_SELECTED_CHOICE] = choice.name
        _uiState.value = _uiState.value.copy(choice = choice)
    }

    /** Re-reads the phone's state for the Make Jandal yours rows. Reads only; never writes or requests. */
    fun refreshPlatform() {
        viewModelScope.launch {
            val platform = platformReader.read()
            _uiState.value = _uiState.value.copy(platform = platform)
        }
    }

    /** Hey Jandal choice on its row. Written only by Finish (see [applyWakeWordChoice]). */
    fun onWakeWordChoiceChanged(enabled: Boolean) {
        savedStateHandle[KEY_SELECTED_WAKE_WORD] = enabled
        _uiState.value = _uiState.value.copy(wakeWordChoice = enabled)
    }

    /** Records that the notification row launched the system prompt, so Finish does not ask a second time. */
    fun onNotificationsRequested() {
        savedStateHandle[KEY_NOTIFICATIONS_ASKED] = true
        _uiState.value = _uiState.value.copy(notificationsAsked = true)
    }

    /**
     * Advances one step. Nothing is saved here: choices are stored only by [onFinish] or [onSkip].
     * On the last step, finishes the wizard, then [onDone].
     */
    fun onContinue(onDone: () -> Unit) {
        val state = _uiState.value
        // Saved on every Continue so the choice shown on the download step survives process death.
        savedStateHandle[KEY_SELECTED_CHOICE] = state.choice.name
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
            val output = saveSpeechChoices(state)
            saveNetworkChoice(state.choice)
            startChosenInput(effectiveInput(state.inputEngine))
            startChosenVoice(output)
            applyWakeWordChoice(state)
            setupWizardPreferences.markCompleted()
            onDone()
        }
    }

    /**
     * Ends the wizard. With no choice recorded yet, saves Wi-Fi only so models still download on Wi-Fi
     * (never paused forever). An explicit earlier choice is kept. [onDone] receives the choice that
     * applies now, so the caller can tell the user the truth. Cori starts only when the user picked it here.
     * Skip never applies the Make Jandal yours choices.
     */
    fun onSkip(onDone: (DownloadNetworkChoice) -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            val effective = storedChoice ?: DownloadNetworkChoice.WIFI_ONLY
            val output = saveSpeechChoices(state)
            if (storedChoice == null) downloadNetworkPreferences.setChoice(effective)
            // Inflect queues on Skip too; Cori alone never does (#1936). Queued after the network choice (consent).
            if (output == VoiceOutputEngine.InflectMicroExperimental) queueInflectMicro()
            setupWizardPreferences.markCompleted()
            onDone(effective)
        }
    }

    /**
     * The speech input this run may write: only a change the user made here, or the first-run default
     * when nothing is stored. A stored choice the wizard does not offer is never written back.
     */
    private fun inputToSave(state: SetupWizardUiState): VoiceInputEngine? =
        if (touched(KEY_SELECTED_INPUT) || storedInput == null) state.inputEngine else null

    private fun outputToSave(state: SetupWizardUiState): VoiceOutputEngine? =
        if (touched(KEY_SELECTED_OUTPUT) || storedOutput == null) state.outputEngine else null

    /**
     * Saves the speech choices before the network choice, so VoiceDefaultsProvisioner reads them after
     * consent. Returns the output written in this run, or null when the stored output was kept.
     */
    private suspend fun saveSpeechChoices(state: SetupWizardUiState): VoiceOutputEngine? {
        inputToSave(state)?.let { voiceInputPreferences.setSelectedEngine(it) }
        // Written only when changed in this run, so a stored value stands otherwise (#1936).
        if (touched(KEY_ONLINE_SPEECH)) voiceInputPreferences.setAllowOnlineSpeechFallback(state.allowOnlineSpeech)
        val output = outputToSave(state)
        output?.let { voiceOutputPreferences.setSelectedEngine(it) }
        return output
    }

    /** Writes the network choice only when it differs from the stored one, so a kept choice is untouched. */
    private suspend fun saveNetworkChoice(choice: DownloadNetworkChoice) {
        if (choice != storedChoice) downloadNetworkPreferences.setChoice(choice)
    }

    /**
     * Queues the Zipformer files the chosen input needs, after the network choice is saved. Files already on
     * disk are skipped, and a stored Automatic with deleted files is queued again, matching the size line.
     */
    private suspend fun startChosenInput(input: VoiceInputEngine) {
        voiceDefaultsProvisioner.queueSpeechInput(input)
    }

    /**
     * Starts Cori only after the network choice is saved, so the download follows consent. Starts nothing
     * for Android TTS or a kept output. Cori already on disk is left alone (PiperVoiceSetup checks state).
     */
    private suspend fun startChosenVoice(output: VoiceOutputEngine?) {
        when (output) {
            VoiceOutputEngine.SherpaExperimental -> piperVoiceSetup.onEngineChosen(output)
            VoiceOutputEngine.InflectMicroExperimental -> queueInflectMicro()
            else -> Unit
        }
    }

    /** Queues Inflect's graph files and the Cori pack it needs. Shared with Settings via [InflectMicroSetup]. */
    private fun queueInflectMicro() {
        InflectMicroSetup.queue(downloadManager, voicePackDownloadManager, SherpaPiperVoice.CoriHigh)
    }

    /**
     * Applies the Hey Jandal choice from its row, only when the user changed it in this run. Off is written when
     * Hey Jandal was stored On. On is written only when the wizard gate ([MakeYoursRows.heyJandalBlock]) passes; a
     * blocked On writes nothing, so a stored value stands. The wake-word model is queued before On is saved.
     */
    private suspend fun applyWakeWordChoice(state: SetupWizardUiState) {
        if (!touched(KEY_SELECTED_WAKE_WORD)) return
        if (!state.wakeWordChoice) {
            if (state.wakeWordStored) wakeWordPreferences.setHeyJandalEnabled(false)
            return
        }
        if (state.wakeWordStored || MakeYoursRows.heyJandalBlock(state.platform) != null) return
        queueHeyJandalSttModel(voiceInputPreferences, downloadManager, releaseBuild)
        wakeWordPreferences.setHeyJandalEnabled(true)
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
            if (engine == VoiceOutputEngine.SherpaExperimental || engine == VoiceOutputEngine.InflectMicroExperimental) {
                listOf(SherpaPiperVoice.CoriHigh).filterNot(isDownloaded)
            } else {
                emptyList()
            }
    }
}
