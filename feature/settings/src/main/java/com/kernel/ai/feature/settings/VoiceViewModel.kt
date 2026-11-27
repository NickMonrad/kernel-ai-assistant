package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.AndroidNativeRecognitionAvailability
import com.kernel.ai.core.voice.isLocaleReadyForWarning
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.readVoiceCapabilities
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.voice.kokoro.KokoroVoice
import com.kernel.ai.core.voice.kokoro.KokoroSetup
import com.kernel.ai.core.voice.VoiceOutputCopy
import com.kernel.ai.core.voice.kokoro.KokoroSpeedResult
import com.kernel.ai.core.voice.kokoro.KokoroVoiceOutputController
import java.util.Locale
import com.kernel.ai.core.voice.InflectMicroModelSpec
import com.kernel.ai.core.voice.wakeVerifierKernelModels
import com.kernel.ai.core.voice.InflectMicroSetup
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.PiperVoiceSetup
import com.kernel.ai.core.voice.SherpaOnnxVoiceOutputController
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
import com.kernel.ai.core.voice.SherpaSttModelSpec
import com.kernel.ai.core.voice.Asr4AllInstall
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import com.kernel.ai.core.voice.Asr4allSwitchState
import com.kernel.ai.core.voice.Asr4allSizeSwitcher
import com.kernel.ai.core.voice.kernelModels
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.voice.WakeWordDetector
import com.kernel.ai.core.voice.WAKE_WORD_DEFAULT_THRESHOLD
import com.kernel.ai.core.voice.WakeWordPreferences
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.terms.TERMS_NOT_ACCEPTED_MESSAGE
import com.kernel.ai.core.inference.terms.TermsAcceptancePreferences
import com.kernel.ai.core.inference.terms.TermsAcceptanceSource
import com.kernel.ai.core.model.availability.ModelRoleCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.model.availability.toAvailability
import com.kernel.ai.core.permissions.MicrophoneReadiness
import javax.inject.Inject

data class SherpaSttDownloadIssue(
    val message: String,
    val licenceRequired: Boolean,
    val licenceUrl: String?,
)

data class SherpaSttDownloadState(
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val issue: SherpaSttDownloadIssue? = null,
    /** Queued on Wi-Fi only while the phone is on mobile data, and nothing is transferring (#2074). */
    val waitingForWifi: Boolean = false,
)

data class SherpaVoiceRowUiState(
    val voice: SherpaPiperVoice,
    val downloadState: VoicePackDownloadState = VoicePackDownloadState.NotDownloaded,
)


data class VoiceUiState(
    val spokenResponsesEnabled: Boolean = true,
    val selectedInputEngine: VoiceInputEngine = VoiceInputEngine.Auto,
    val availableInputEngines: List<VoiceInputEngine> = VoiceInputEngine.entries,
    val asr4allSize: Asr4AllSize = Asr4AllInstall.defaultSize,
    val asr4allSizes: List<Asr4AllSize> = listOf(Asr4AllInstall.defaultSize),
    /** The size being downloaded before it replaces [asr4allSize]; null when no switch is pending. */
    val asr4allPendingSize: Asr4AllSize? = null,
    /** The message of the last failed size switch. Shown under the asr4all card whatever the card's state. */
    val asr4allSwitchFailure: String? = null,
    /**
     * Whether the Terms of Use are accepted for the current version, gating the asr4all download (#1998). Null
     * until the saved acceptance is read, so the Terms row is not drawn from a stale value (#2049).
     */
    val termsAccepted: Boolean? = null,
    val selectedOutputEngine: VoiceOutputEngine = VoiceOutputEngine.AndroidTts,
    val availableOutputEngines: List<VoiceOutputEngine> = VoiceOutputEngine.entries,
    val selectedSherpaVoice: SherpaPiperVoice = SherpaPiperVoice.JennyDioco,
    /** Why the last Play sample did not speak; null when it spoke or was never played. */
    val sherpaSampleError: String? = null,
    val sherpaSpeed: Float = 0.85f,
    val sherpaPitch: Float = 1.0f,
    val sherpaGain: Float = 1.5f,
    val autoSpeak: Boolean = true,
    val maxSpokenSentences: Int = 0,
    val sherpaVoices: List<SherpaVoiceRowUiState>,
    val autoStartAlertVoiceCommandsEnabled: Boolean = true,
    val allowOnlineSpeechFallback: Boolean = false,
    val androidNativeAvailabilityMessage: String? = null,
    /** Android native recognizer language name, e.g. "English (Australia)"; the card's third line (#1937). */
    val androidNativeLanguageName: String? = null,
    val androidNativeCapabilities: VoiceCapabilities? = null,
    val androidNativeLocaleReady: Boolean = true,
    val hasDownloadedSherpaVoice: Boolean = false,
    val isSelectedSherpaVoiceDownloaded: Boolean = false,
    /** Active speaker ID for multi-speaker voices (VCTK). Stored as sid 0–108. */
    val activeSpeakerId: Int = 0,
    // ── Kokoro ────────────────────────────────────────────────────────────────
    val kokoroStates: Map<KernelModel, DownloadState> = emptyMap(),
    val selectedKokoroVoice: KokoroVoice = KokoroVoice.DEFAULT,
    val kokoroSpeed: KokoroSpeedUiState = KokoroSpeedUiState.Idle,
    // ── Hey Jandal / Default Assistant ───────────────────────────────────────
    /** True when Jandal is the system's Default Digital Assistant (RoleManager.ROLE_ASSISTANT). */
    val isDefaultAssistant: Boolean = false,
    /** True when "Listen for Hey Jandal" is enabled in preferences. */
    val heyJandalEnabled: Boolean = false,
    /** Bytes the Whisper wake verifier still needs to download; 0 when on disk (#1931). */
    val heyJandalDownloadBytes: Long = 0L,
    /** True when the hey_jandal_int8.tflite model file is present on device. */
    val isWakeWordModelAvailable: Boolean = false,
    /** Wake word confidence threshold in [0, 1]. Reflects [WakeWordPreferences.confidenceThreshold]. */
    val wakeWordThreshold: Float = WAKE_WORD_DEFAULT_THRESHOLD,
    // ── Inflect Micro model pair ─────────────────────────────────────────────
    val inflectMicroStates: Map<KernelModel, DownloadState> = emptyMap(),
    val inflectMicroAvailability: ModelAvailabilityState =
        ModelAvailabilityState.NotDisplayed,
    /** True only when both Inflect graphs and the selected Sherpa eSpeak pack exist. */
    val isInflectMicroReady: Boolean = false,
    // ── Sherpa-ONNX STT model download states (per family) ──────────────────
    /** Per-family download state for each Sherpa STT engine. */
    val sherpaSttStates: Map<VoiceInputEngine, SherpaSttDownloadState> = emptyMap(),
    // ── Model availability states (for ModelCardCompact) ────────────────────
    /** Per-voice availability state for Sherpa Piper voices. */
    val sherpaVoiceAvailability: Map<SherpaPiperVoice, ModelAvailabilityState> = emptyMap(),
)
internal fun resolveAndroidNativeAvailabilityMessage(
    availability: AndroidNativeRecognitionAvailability,
): String? = availability.blockingReason

/**
 * Maps a [VoicePackDownloadState] to the UI-layer [ModelAvailabilityState].
 * Used by [ModelCardCompact] in the voice screen.
 */
internal fun VoicePackDownloadState.toModelAvailability(): ModelAvailabilityState = when (this) {
    is VoicePackDownloadState.Downloaded -> ModelAvailabilityState.Ready
    is VoicePackDownloadState.Downloading -> ModelAvailabilityState.Preparing(
        progress = progress,
        isAutoQueued = false,
    )
    is VoicePackDownloadState.Error -> ModelAvailabilityState.ActionRequired(
        ActionReason.DownloadFailed(message)
    )
    is VoicePackDownloadState.NotDownloaded -> ModelAvailabilityState.NotDisplayed
}

/**
 * Aggregates the Inflect graph states and the shared Cori frontend pack into the Inflect row's state.
 *
 * While Piper is the selected output, the Piper row owns Cori: Inflect shows only its own graphs and never
 * offers to cancel Cori. While Piper is not selected, Inflect owns the shared Cori pack only once it has
 * started its own graphs, hit a graph error, or has all graphs downloaded and Cori is downloading. Only then
 * is the row's Cancel shown with, and stops, that Cori download (#1868). Inflect is ready only when its graphs
 * and the selected Cori pack are both downloaded. Downloading takes precedence over an error.
 */
internal fun aggregateInflectMicroAvailability(
    requiredModels: List<KernelModel>,
    states: Map<KernelModel, DownloadState>,
    frontendState: VoicePackDownloadState,
    frontendApproxDownloadBytes: Long,
    piperSelected: Boolean,
): ModelAvailabilityState {
    val resolvedStates = requiredModels.map { model ->
        model to (states[model] ?: DownloadState.NotDownloaded)
    }
    val allGraphsDownloaded = resolvedStates.isNotEmpty() &&
        resolvedStates.all { (_, state) -> state is DownloadState.Downloaded }
    if (allGraphsDownloaded && frontendState is VoicePackDownloadState.Downloaded) {
        return ModelAvailabilityState.Ready
    }

    val graphDownloading = resolvedStates.any { (_, state) -> state is DownloadState.Downloading }
    val graphError = resolvedStates.firstOrNull { (_, state) -> state is DownloadState.Error }
    val frontendDownloading = frontendState is VoicePackDownloadState.Downloading
    val inflectOwnsFrontend = !piperSelected && (
        graphDownloading || graphError != null || (allGraphsDownloaded && frontendDownloading)
    )
    val frontend = if (inflectOwnsFrontend) frontendState else VoicePackDownloadState.NotDownloaded
    // Cori bytes count toward Inflect's progress only while Inflect owns the pack; a pack Piper owns is not
    // Inflect's work, and a downloaded pack Inflect owns is already done.
    val frontendBytes = if (inflectOwnsFrontend) frontendApproxDownloadBytes else 0L

    if (graphDownloading || (inflectOwnsFrontend && frontendDownloading)) {
        val totalBytes = resolvedStates.sumOf { (model, _) -> model.approxSizeBytes } + frontendBytes
        val downloadedBytes = resolvedStates.sumOf { (model, state) ->
            when (state) {
                is DownloadState.Downloaded -> model.approxSizeBytes
                is DownloadState.Downloading -> (model.approxSizeBytes * state.progress).toLong()
                else -> 0L
            }
        } + when (frontend) {
            is VoicePackDownloadState.Downloaded -> frontendBytes
            is VoicePackDownloadState.Downloading -> (frontendBytes * frontend.progress).toLong()
            else -> 0L
        }
        return ModelAvailabilityState.Preparing(
            progress = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                0f
            },
            isAutoQueued = false,
        )
    }

    if (graphError != null) {
        val (model, state) = graphError
        return (state as DownloadState.Error).toAvailability(model, hfAuth = false)
    }
    val frontendError = frontend as? VoicePackDownloadState.Error
    if (frontendError != null) {
        return ModelAvailabilityState.ActionRequired(
            ActionReason.DownloadFailed(frontendError.message),
        )
    }

    return ModelAvailabilityState.NotDisplayed
}

/**
 * Maps a [SherpaSttDownloadState] to the UI-layer [ModelAvailabilityState].
 *
 * An idle, not-yet-downloaded model is downloadable, not unbundled: it maps to
 * [ModelAvailabilityState.NotDisplayed] (no badge; the Download action is shown). Mapping it to
 * `NotBundled` told users the model could not be obtained while the card offered a Download button.
 */
internal fun SherpaSttDownloadState.toModelAvailability(): ModelAvailabilityState = when {
    isDownloaded -> ModelAvailabilityState.Ready
    isDownloading -> ModelAvailabilityState.Preparing(progress = progress, isAutoQueued = false)
    issue?.licenceRequired == true -> ModelAvailabilityState.ActionRequired(ActionReason.LicenseRequired)
    issue != null -> ModelAvailabilityState.ActionRequired(ActionReason.DownloadFailed(issue.message))
    else -> ModelAvailabilityState.NotDisplayed
}

/** The one status label for a speech model: the card badge and its row both read it (#2075). */
internal fun SherpaSttDownloadState.statusLabel(): String = when {
    isDownloaded -> "Downloaded"
    waitingForWifi -> ModelRoleCopy.WAITING_FOR_WIFI
    isDownloading -> ModelRoleCopy.VOICE_DOWNLOADING
    else -> ModelRoleCopy.VOICE_NOT_DOWNLOADED
}

/**
 * Download state of one speech model family from its required files. A queued file reports
 * [DownloadState.Downloading], so [waitingForWifi] needs queued files and no transferring file; otherwise the row
 * would show progress that is not moving (#2074).
 */
internal fun sherpaSttDownloadState(
    requiredModels: List<KernelModel>,
    states: Map<KernelModel, DownloadState>,
    waitingForWifi: Set<KernelModel>,
): SherpaSttDownloadState {
    val allDownloaded = requiredModels.all { states[it] is DownloadState.Downloaded }
    val anyDownloading = requiredModels.any { states[it] is DownloadState.Downloading }
    val transferring = requiredModels.any { states[it] is DownloadState.Downloading && it !in waitingForWifi }
    val totalBytes = requiredModels.sumOf { it.approxSizeBytes }.toFloat()
    val weightedProgress = if (totalBytes > 0f) {
        requiredModels.map { model ->
            val weight = model.approxSizeBytes.toFloat()
            when (val s = states[model]) {
                is DownloadState.Downloaded -> weight
                is DownloadState.Downloading -> s.progress * weight
                else -> 0f
            }
        }.sum() / totalBytes
    } else 0f
    val issue = requiredModels
        .mapNotNull { model ->
            val errorState = states[model] as? DownloadState.Error ?: return@mapNotNull null
            SherpaSttDownloadIssue(
                message = errorState.message,
                licenceRequired = errorState.licenceRequired,
                licenceUrl = if (errorState.licenceRequired) model.licenceUrl else null,
            )
        }
        .sortedByDescending { it.licenceRequired }
        .firstOrNull()
    return SherpaSttDownloadState(
        isDownloaded = allDownloaded,
        isDownloading = anyDownloading && !allDownloaded,
        progress = weightedProgress,
        issue = issue,
        waitingForWifi = !allDownloaded && !transferring && requiredModels.any { it in waitingForWifi },
    )
}

private data class SttSnapshot(
    val states: Map<KernelModel, DownloadState>,
    val waiting: Set<KernelModel>,
    val asr4allSize: Asr4AllSize,
    val switchState: Asr4allSwitchState,
    val termsAccepted: Boolean,
)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val sherpaVoicePackDownloadManager: SherpaVoicePackDownloadManager,
    private val wakeWordPreferences: WakeWordPreferences,
    private val wakeWordDetector: WakeWordDetector,
    private val modelDownloadManager: ModelDownloadManager,
    private val hardwareProfileDetector: HardwareProfileDetector,
    @ApplicationContext private val context: Context,
    private val sherpaVoiceOutputController: SherpaOnnxVoiceOutputController,
    private val kokoroVoiceOutputController: KokoroVoiceOutputController,
    private val piperVoiceSetup: PiperVoiceSetup,
    private val asr4allSizeSwitcher: Asr4allSizeSwitcher,
    private val termsAcceptancePreferences: TermsAcceptancePreferences,
) : ViewModel() {
    private val isReleaseBuild: Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0
    private val inflectReleaseEligible: Boolean =
        InflectMicroModelSpec.isReleaseEligible(hardwareProfileDetector.profile.tier)
    private val visibleSherpaVoices: List<SherpaPiperVoice> =
        SherpaPiperVoice.entriesForBuild(isReleaseBuild)
    private val inflectModels: List<KernelModel> = InflectMicroSetup.kernelModels()

    private val _uiState = MutableStateFlow(
        VoiceUiState(
            availableInputEngines = VoiceInputEngine.entriesForBuild(isReleaseBuild),
            availableOutputEngines = VoiceOutputEngine.entriesForBuild(
                isRelease = isReleaseBuild,
                inflectEligible = inflectReleaseEligible,
            ),
            sherpaVoices = visibleSherpaVoices.map { voice ->
                SherpaVoiceRowUiState(voice = voice)
            },
        ),
    )
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()


    init {
        viewModelScope.launch {
            termsAcceptancePreferences.record.collect { record ->
                _uiState.update { it.copy(termsAccepted = record.isAccepted()) }
            }
        }
        viewModelScope.launch {
            val availability = androidNativeRecognitionSupport.getAvailability()
            val nativeWarning = resolveAndroidNativeAvailabilityMessage(availability)
            _uiState.update {
                it.copy(
                    androidNativeAvailabilityMessage = nativeWarning,
                    androidNativeLanguageName = availability.languageDisplayName,
                    // Unknown means the language check could not run, not that the pack is missing (#2086).
                    androidNativeLocaleReady = availability.localeStatus.isLocaleReadyForWarning(),
                    androidNativeCapabilities = runCatching { readVoiceCapabilities(context) }.getOrNull(),
                )
            }
        }
        viewModelScope.launch {
            voiceInputPreferences.selectedEngine.collect { engine ->
                _uiState.update {
                    it.copy(
                        selectedInputEngine = engine,
                        heyJandalDownloadBytes = heyJandalDownloadBytes(modelDownloadManager.downloadStates.value),
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.spokenResponsesEnabled.collect { enabled ->
                _uiState.update { it.copy(spokenResponsesEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.selectedEngine.collect { engine ->
                val effectiveEngine = engine.takeIf {
                    it in VoiceOutputEngine.entriesForBuild(
                        isRelease = isReleaseBuild,
                        inflectEligible = inflectReleaseEligible,
                    )
                } ?: VoiceOutputEngine.AndroidTts
                _uiState.update { it.copy(selectedOutputEngine = effectiveEngine).withInflectAvailability() }
                if (effectiveEngine != engine) {
                    voiceOutputPreferences.setSelectedEngine(effectiveEngine)
                }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.selectedSherpaVoice.collect { voice ->
                _uiState.update { state ->
                    val selectedVoiceState =
                        sherpaVoicePackDownloadManager.downloadStates.value[voice]
                            ?: VoicePackDownloadState.NotDownloaded
                    val inflectAvailability = aggregateInflectMicroAvailability(
                        requiredModels = inflectModels,
                        states = state.inflectMicroStates,
                        frontendState = selectedVoiceState,
                        frontendApproxDownloadBytes = voice.approxDownloadBytes,
                        piperSelected = state.selectedOutputEngine == VoiceOutputEngine.SherpaExperimental,
                    )
                    state.copy(
                        selectedSherpaVoice = voice,
                        isSelectedSherpaVoiceDownloaded =
                            selectedVoiceState is VoicePackDownloadState.Downloaded,
                        inflectMicroAvailability = inflectAvailability,
                        isInflectMicroReady = inflectAvailability is ModelAvailabilityState.Ready,
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.sherpaSpeed.collect { speed ->
                _uiState.update { it.copy(sherpaSpeed = speed) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.voicePitch.collect { pitch ->
                _uiState.update { it.copy(sherpaPitch = pitch) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.voiceGain.collect { gain ->
                _uiState.update { it.copy(sherpaGain = gain) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.autoSpeak.collect { enabled ->
                _uiState.update { it.copy(autoSpeak = enabled) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.maxSpokenSentences.collect { count ->
                _uiState.update { it.copy(maxSpokenSentences = count) }
            }
        }
        viewModelScope.launch {
            sherpaVoicePackDownloadManager.downloadStates.collect { states ->
                _uiState.update {
                    val sherpaRows = visibleSherpaVoices.map { voice ->
                        SherpaVoiceRowUiState(
                            voice = voice,
                            downloadState = states[voice] ?: VoicePackDownloadState.NotDownloaded,
                        )
                    }
                    val selectedVoiceState =
                        states[it.selectedSherpaVoice] ?: VoicePackDownloadState.NotDownloaded
                    val inflectAvailability = aggregateInflectMicroAvailability(
                        requiredModels = inflectModels,
                        states = it.inflectMicroStates,
                        frontendState = selectedVoiceState,
                        frontendApproxDownloadBytes = it.selectedSherpaVoice.approxDownloadBytes,
                        piperSelected = it.selectedOutputEngine == VoiceOutputEngine.SherpaExperimental,
                    )
                    it.copy(
                        sherpaVoices = sherpaRows,
                        hasDownloadedSherpaVoice = sherpaRows.any { row ->
                            row.downloadState is VoicePackDownloadState.Downloaded
                        },
                        isSelectedSherpaVoiceDownloaded =
                            selectedVoiceState is VoicePackDownloadState.Downloaded,
                        inflectMicroAvailability = inflectAvailability,
                        isInflectMicroReady = inflectAvailability is ModelAvailabilityState.Ready,
                        sherpaVoiceAvailability = visibleSherpaVoices.associateWith { voice ->
                            (states[voice] ?: VoicePackDownloadState.NotDownloaded)
                                .toModelAvailability()
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.activeSpeakerId.collect { sid ->
                _uiState.update { it.copy(activeSpeakerId = sid) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.selectedKokoroVoice.collect { voice ->
                _uiState.update { it.copy(selectedKokoroVoice = voice) }
            }
        }
        viewModelScope.launch {
            voiceInputPreferences.allowOnlineSpeechFallback.collect { allowed ->
                _uiState.update { it.copy(allowOnlineSpeechFallback = allowed) }
            }
        }
        viewModelScope.launch {
            voiceInputPreferences.autoStartAlertVoiceCommandsEnabled.collect { enabled ->
                _uiState.update { it.copy(autoStartAlertVoiceCommandsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            voiceInputPreferences.asr4allSize.collect { stored ->
                val tier = hardwareProfileDetector.profile.tier
                _uiState.update {
                    it.copy(
                        asr4allSize = Asr4AllInstall.effectiveSize(stored, tier),
                        asr4allSizes = Asr4AllInstall.selectableSizes(tier),
                    )
                }
            }
        }
        viewModelScope.launch {
            wakeWordPreferences.heyJandalEnabled.collect { enabled ->
                _uiState.update { it.copy(heyJandalEnabled = enabled) }
                if (enabled) {
                    wakeVerifierKernelModels().forEach(modelDownloadManager::startDownload)
                }
            }
        }
        viewModelScope.launch {
            wakeWordPreferences.confidenceThreshold.collect { threshold ->
                _uiState.update { it.copy(wakeWordThreshold = threshold) }
            }
        }
        _uiState.update { it.copy(isWakeWordModelAvailable = wakeWordDetector.isAvailable) }
        viewModelScope.launch {
            combine(
                modelDownloadManager.downloadStates,
                modelDownloadManager.waitingForWifi,
                voiceInputPreferences.asr4allSize,
                asr4allSizeSwitcher.state,
                termsAcceptancePreferences.record,
            ) { states, waiting, stored, switchState, record ->
                SttSnapshot(
                    states = states,
                    waiting = waiting,
                    asr4allSize = Asr4AllInstall.effectiveSize(stored, hardwareProfileDetector.profile.tier),
                    switchState = switchState,
                    // Same combine as the failure it hides, so no emission order leaves a stale message (#2080).
                    termsAccepted = record.isAccepted(),
                )
            }.collect { (states, waiting, asr4allSize, switchState, termsAccepted) ->
                val pendingSize = switchState.pendingSize
                val perFamilyStates = (SherpaSttModelSpec.ALL.mapValues { (_, spec) ->
                    sherpaSttDownloadState(
                        spec.requiredFileNames.mapNotNull { fileName -> KernelModel.entries.firstOrNull { it.fileName == fileName } },
                        states,
                        waiting,
                    )
                }) + (VoiceInputEngine.Asr4all to sherpaSttDownloadState(Asr4AllInstall.models(pendingSize ?: asr4allSize), states, waiting))
                val inflectStates = inflectModels.associateWith { model -> states[model] ?: DownloadState.NotDownloaded }
                val kokoroStates = KokoroSetup.kernelModels().associateWith { model -> states[model] ?: DownloadState.NotDownloaded }
                _uiState.update {
                    val selectedVoiceState =
                        sherpaVoicePackDownloadManager.downloadStates.value[it.selectedSherpaVoice]
                            ?: VoicePackDownloadState.NotDownloaded
                    val inflectAvailability = aggregateInflectMicroAvailability(
                        requiredModels = inflectModels,
                        states = inflectStates,
                        frontendState = selectedVoiceState,
                        frontendApproxDownloadBytes = it.selectedSherpaVoice.approxDownloadBytes,
                        piperSelected = it.selectedOutputEngine == VoiceOutputEngine.SherpaExperimental,
                    )
                    it.copy(
                        heyJandalDownloadBytes = heyJandalDownloadBytes(states),
                        inflectMicroStates = inflectStates,
                        inflectMicroAvailability = inflectAvailability,
                        isInflectMicroReady = inflectAvailability is ModelAvailabilityState.Ready,
                        kokoroStates = kokoroStates,
                        asr4allPendingSize = pendingSize,
                        asr4allSwitchFailure = switchState.failure?.message.takeUnless { m ->
                            m == TERMS_NOT_ACCEPTED_MESSAGE && termsAccepted
                        },
                        sherpaSttStates = perFamilyStates,
                    )
                }
            }
        }
    }



    private fun heyJandalDownloadBytes(states: Map<KernelModel, DownloadState>): Long =
        wakeVerifierKernelModels()
            .filterNot { states[it] is DownloadState.Downloaded }
            .sumOf { it.approxSizeBytes }

    fun setVoiceInputEngine(engine: VoiceInputEngine) {
        val effective = engine.takeIf { it in _uiState.value.availableInputEngines }
            ?: VoiceInputEngine.AndroidNative
        _uiState.update { it.copy(selectedInputEngine = effective) }
        viewModelScope.launch {
            voiceInputPreferences.setSelectedEngine(effective)
        }
    }

    private fun modelsFor(engine: VoiceInputEngine): List<KernelModel> =
        if (engine == VoiceInputEngine.Asr4all) Asr4AllInstall.models(asr4allSizeSwitcher.state.value.pendingSize ?: _uiState.value.asr4allSize)
        else SherpaSttModelSpec.forEngine(engine)?.kernelModels().orEmpty()

    fun downloadSherpaStt(engine: VoiceInputEngine) {
        if (isReleaseBuild && engine.isSherpaFamily) return
        modelsFor(engine).forEach { modelDownloadManager.startDownload(it) }
    }

    /** "Use mobile data" for one speech model, for this download only (#2074). Mirrors [downloadSherpaStt]. */
    fun useMobileDataForSherpaStt(engine: VoiceInputEngine) {
        if (isReleaseBuild && engine.isSherpaFamily) return
        modelsFor(engine).forEach { modelDownloadManager.startDownload(it, restartRunning = true, allowMobileData = true) }
    }

    fun downloadInflectMicro() {
        InflectMicroSetup.queue(
            modelDownloadManager = modelDownloadManager,
            voicePackDownloadManager = sherpaVoicePackDownloadManager,
            frontendVoice = _uiState.value.selectedSherpaVoice,
        )
    }

    /**
     * Cancels Inflect's graph downloads. While Piper is not selected, also cancels a running Cori download,
     * which the Inflect row shows as its own progress. State-derived, so it works after the ViewModel is recreated (#1868).
     */
    fun cancelInflectMicroDownload() {
        val currentStates = modelDownloadManager.downloadStates.value
        inflectModels
            .filter { currentStates[it] is DownloadState.Downloading }
            .forEach { modelDownloadManager.cancelDownload(it) }
        if (_uiState.value.selectedOutputEngine == VoiceOutputEngine.SherpaExperimental) return
        val voice = _uiState.value.selectedSherpaVoice
        if (sherpaVoicePackDownloadManager.downloadStates.value[voice] is VoicePackDownloadState.Downloading) {
            sherpaVoicePackDownloadManager.cancelDownload(voice)
        }
    }

    /** Recomputes the Inflect row from the current engine, selected voice and download states. */
    private fun VoiceUiState.withInflectAvailability(): VoiceUiState {
        val frontend = sherpaVoicePackDownloadManager.downloadStates.value[selectedSherpaVoice]
            ?: VoicePackDownloadState.NotDownloaded
        val availability = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = inflectMicroStates,
            frontendState = frontend,
            frontendApproxDownloadBytes = selectedSherpaVoice.approxDownloadBytes,
            piperSelected = selectedOutputEngine == VoiceOutputEngine.SherpaExperimental,
        )
        return copy(
            inflectMicroAvailability = availability,
            isInflectMicroReady = availability is ModelAvailabilityState.Ready,
        )
    }

    fun deleteInflectMicro() {
        // Inflect owns only graph files; the Sherpa pack is shared with Sherpa Piper and stays installed.
        viewModelScope.launch(Dispatchers.IO) {
            inflectModels.forEach { model ->
                if (modelDownloadManager.downloadStates.value[model] is DownloadState.Downloading) {
                    modelDownloadManager.cancelDownload(model)
                }
                val file = model.localFile(context)
                file.delete()
                val tmp = java.io.File(file.absolutePath + ".tmp")
                if (tmp.exists()) tmp.delete()
                modelDownloadManager.pruneCompletedWork(model)
                modelDownloadManager.refreshState(model)
            }
        }
    }


    fun cancelSherpaSttDownload(engine: VoiceInputEngine) {
        if (engine == VoiceInputEngine.Asr4all) asr4allSizeSwitcher.cancel()
        val currentStates = modelDownloadManager.downloadStates.value
        modelsFor(engine)
            .filter { currentStates[it] is DownloadState.Downloading }
            .forEach { modelDownloadManager.cancelDownload(it) }
    }

    fun deleteSherpaStt(engine: VoiceInputEngine) {
        viewModelScope.launch(Dispatchers.IO) {
            if (engine == VoiceInputEngine.Asr4all) {
                // The switcher deletes the active size under an engine release. False means the capture
                // could not be released: nothing was deleted, so the engine stays selected.
                if (!asr4allSizeSwitcher.deleteActive()) return@launch
            } else {
                deleteModelFiles(modelsFor(engine))
            }
            if (_uiState.value.selectedInputEngine == engine) {
                withContext(Dispatchers.Main) {
                    setVoiceInputEngine(VoiceInputEngine.Auto)
                }
            }
        }
    }

    private fun deleteModelFiles(models: List<KernelModel>) {
        models.forEach { model ->
            val file = model.localFile(context)
            file.delete()
            val tmp = java.io.File(file.absolutePath + ".tmp")
            if (tmp.exists()) tmp.delete()
            modelDownloadManager.refreshState(model)
        }
    }


    fun setSpokenResponsesEnabled(enabled: Boolean) {
        _uiState.update { it.copy(spokenResponsesEnabled = enabled) }
        viewModelScope.launch {
            voiceOutputPreferences.setSpokenResponsesEnabled(enabled)
        }
    }

    fun setVoiceOutputEngine(engine: VoiceOutputEngine) {
        if (engine !in VoiceOutputEngine.entriesForBuild(
                isRelease = isReleaseBuild,
                inflectEligible = inflectReleaseEligible,
            )
        ) return
        val previous = _uiState.value.selectedOutputEngine
        if (engine == VoiceOutputEngine.KokoroExperimental && engine != previous && kokoroPackReady()) {
            selectKokoroIfItKeepsUp(previous)
            return
        }
        _uiState.update { it.copy(selectedOutputEngine = engine).withInflectAvailability() }
        viewModelScope.launch {
            voiceOutputPreferences.setSelectedEngine(engine)
            // Re-tapping the selected engine must not re-run the Piper start logic.
            if (engine != previous) piperVoiceSetup.onEngineChosen(engine)
        }
    }

    /** Settings "Test voice speed" button (#2117): measures without changing the selected engine. */
    fun testKokoroSpeed() {
        if (_uiState.value.kokoroSpeed == KokoroSpeedUiState.Running) return
        _uiState.update { it.copy(kokoroSpeed = KokoroSpeedUiState.Running) }
        viewModelScope.launch {
            val result = kokoroVoiceOutputController.measureSpeed()
            _uiState.update { it.copy(kokoroSpeed = KokoroSpeedUiState.Done(result.summaryLines())) }
        }
    }

    private fun kokoroPackReady(): Boolean {
        val states = _uiState.value.kokoroStates.values
        return states.isNotEmpty() && states.all { it is DownloadState.Downloaded }
    }

    /**
     * Selecting Kokoro with the pack on disk runs the speed test first. Kokoro is chosen only when the phone keeps up;
     * otherwise the previous engine stays selected and the card says why (#2117).
     */
    private fun selectKokoroIfItKeepsUp(previous: VoiceOutputEngine) {
        _uiState.update { it.copy(kokoroSpeed = KokoroSpeedUiState.Running) }
        viewModelScope.launch {
            val result = kokoroVoiceOutputController.measureSpeed()
            val lines = result.summaryLines()
            if (result is KokoroSpeedResult.Measured && !result.stutters) {
                _uiState.update {
                    it.copy(
                        selectedOutputEngine = VoiceOutputEngine.KokoroExperimental,
                        kokoroSpeed = KokoroSpeedUiState.Done(lines),
                    ).withInflectAvailability()
                }
                voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.KokoroExperimental)
                piperVoiceSetup.onEngineChosen(VoiceOutputEngine.KokoroExperimental)
            } else {
                val reason = if (result is KokoroSpeedResult.Measured) "speech would stutter on this phone" else "Kokoro is not ready"
                _uiState.update {
                    it.copy(
                        kokoroSpeed = KokoroSpeedUiState.Done(
                            lines + "Kokoro not selected: $reason. Staying on ${VoiceOutputCopy.title(previous)}.",
                        ),
                    )
                }
            }
        }
    }

    /** Lets this voice pack download over mobile data. Other downloads keep the global network choice (#1868). */
    fun useMobileDataForVoice(voice: SherpaPiperVoice) {
        sherpaVoicePackDownloadManager.startDownload(voice, restartRunning = true, allowMobileData = true)
    }

    /** Speaks a fixed sample through the selected output engine. Only for a downloaded voice; never automatic. */
    fun playSherpaSample(voice: SherpaPiperVoice) {
        val row = _uiState.value.sherpaVoices.firstOrNull { it.voice == voice }
        if (row?.downloadState !is VoicePackDownloadState.Downloaded) return
        viewModelScope.launch {
            val result = sherpaVoiceOutputController.speak(VoiceSpeakRequest(text = SHERPA_SAMPLE_TEXT))
            _uiState.update {
                it.copy(
                    sherpaSampleError = if (result is VoiceOutputResult.Unavailable) SAMPLE_FAILED_MESSAGE else null,
                )
            }
        }
    }

    fun setSherpaVoice(voice: SherpaPiperVoice) {
        if (isReleaseBuild && !voice.releaseVisible) {
            return
        }
        val row = _uiState.value.sherpaVoices.firstOrNull { it.voice == voice }
        if (row?.downloadState !is VoicePackDownloadState.Downloaded) {
            return
        }
        _uiState.update { it.copy(selectedSherpaVoice = voice) }
        viewModelScope.launch {
            voiceOutputPreferences.setSelectedSherpaVoice(voice)
        }
    }

    fun downloadSherpaVoice(voice: SherpaPiperVoice) {
        if (isReleaseBuild && !voice.releaseVisible) {
            return
        }
        sherpaVoicePackDownloadManager.startDownload(voice)
    }

    fun cancelSherpaVoiceDownload(voice: SherpaPiperVoice) {
        sherpaVoicePackDownloadManager.cancelDownload(voice)
    }

    fun deleteSherpaVoice(voice: SherpaPiperVoice) {
        sherpaVoicePackDownloadManager.deleteVoice(voice)
    }

    fun setSherpaSpeed(speed: Float) {
        _uiState.update { it.copy(sherpaSpeed = speed) }
        viewModelScope.launch {
            voiceOutputPreferences.setSherpaSpeed(speed)
        }
    }

    fun setSherpaPitch(pitch: Float) {
        _uiState.update { it.copy(sherpaPitch = pitch) }
        viewModelScope.launch {
            voiceOutputPreferences.setVoicePitch(pitch)
        }
    }

    fun setSherpaGain(gain: Float) {
        _uiState.update { it.copy(sherpaGain = gain) }
        viewModelScope.launch {
            voiceOutputPreferences.setVoiceGain(gain)
        }
    }

    fun setAutoSpeak(enabled: Boolean) {
        _uiState.update { it.copy(autoSpeak = enabled) }
        viewModelScope.launch {
            voiceOutputPreferences.setAutoSpeak(enabled)
        }
    }

    fun setMaxSpokenSentences(count: Int) {
        _uiState.update { it.copy(maxSpokenSentences = count) }
        viewModelScope.launch {
            voiceOutputPreferences.setMaxSpokenSentences(count)
        }
    }

    fun setActiveSpeakerId(sid: Int) {
        _uiState.update { it.copy(activeSpeakerId = sid) }
        viewModelScope.launch {
            voiceOutputPreferences.setActiveSpeakerId(sid)
        }
    }

    fun setAllowOnlineSpeechFallback(allowed: Boolean) {
        _uiState.update { it.copy(allowOnlineSpeechFallback = allowed) }
        viewModelScope.launch {
            voiceInputPreferences.setAllowOnlineSpeechFallback(allowed)
        }
    }

    /**
     * Chooses the asr4all size. [Asr4allSizeSwitcher] downloads it while the active size stays selected,
     * and stores it once every file is present.
     */
    fun setAsr4allSize(size: Asr4AllSize) = asr4allSizeSwitcher.select(size)

    /** Ticks or unticks the Terms of Use on the asr4all speech card. Stored at once (#1998). */
    fun onTermsAcceptedChanged(accepted: Boolean) {
        viewModelScope.launch { termsAcceptancePreferences.setAccepted(accepted, source = TermsAcceptanceSource.VOICE_SETTINGS) }
    }

    fun setAutoStartAlertVoiceCommandsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoStartAlertVoiceCommandsEnabled = enabled) }
        viewModelScope.launch {
            voiceInputPreferences.setAutoStartAlertVoiceCommandsEnabled(enabled)
        }
    }

    fun setKokoroVoice(voice: KokoroVoice) {
        _uiState.update { it.copy(selectedKokoroVoice = voice) }
        viewModelScope.launch { voiceOutputPreferences.setSelectedKokoroVoice(voice) }
    }

    fun downloadKokoroPack() = KokoroSetup.queue(modelDownloadManager)

    fun cancelKokoroPackDownload() {
        val states = modelDownloadManager.downloadStates.value
        KokoroSetup.kernelModels().filter { states[it] is DownloadState.Downloading }
            .forEach(modelDownloadManager::cancelDownload)
    }

    fun deleteKokoroPack() = viewModelScope.launch(Dispatchers.IO) {
        deleteModelFiles(KokoroSetup.kernelModels())
    }
    // ── Hey Jandal ────────────────────────────────────────────────────────────

    fun setHeyJandalEnabled(enabled: Boolean) {
        _uiState.update { it.copy(heyJandalEnabled = enabled) }
        viewModelScope.launch {
            wakeWordPreferences.setHeyJandalEnabled(enabled)
            if (enabled) {
                wakeVerifierKernelModels().forEach(modelDownloadManager::startDownload)
            }
        }
    }

    /**
     * Enforce Hey Jandal microphone grant state.
     * If Hey Jandal is currently enabled and [readiness] is not [MicrophoneReadiness.Granted],
     * disable it. Call from VoiceScreen's ON_RESUME handler and after grant callbacks.
     */
    fun enforceHeyJandalMicReadiness(readiness: MicrophoneReadiness): Boolean {
        if (!_uiState.value.heyJandalEnabled) return false
        when (readiness) {
            MicrophoneReadiness.Granted -> return false
            else -> {
                setHeyJandalEnabled(false)
                return true
            }
        }
    }

    fun setWakeWordThreshold(threshold: Float) {
        _uiState.update { it.copy(wakeWordThreshold = threshold) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                wakeWordPreferences.setConfidenceThreshold(threshold)
            }
        }
    }

    /**
     * Called from VoiceScreen on every resume to keep the assistant-role badge in sync.
     */
    fun refreshAssistantStatus(isRoleHeld: Boolean) {
        _uiState.update { it.copy(isDefaultAssistant = isRoleHeld) }
    }
}

private const val SHERPA_SAMPLE_TEXT = "Hi, this is Jandal. This is how this voice sounds."

internal const val SAMPLE_FAILED_MESSAGE =
    "Couldn't play the sample. Delete the voice and download it again, then retry."

/** Settings "Test voice speed" state (#2117). */
sealed interface KokoroSpeedUiState {
    data object Idle : KokoroSpeedUiState
    data object Running : KokoroSpeedUiState
    data class Done(val lines: List<String>) : KokoroSpeedUiState
}

/** Human-readable result for the speed panel. Kept here, next to the state that holds it. */
internal fun KokoroSpeedResult.summaryLines(): List<String> = when (this) {
    is KokoroSpeedResult.Measured -> listOf(
        "Speed: %.2f× real time (%.1f s of audio made in %.1f s).".format(Locale.US, realTimeFactor, audioSeconds, synthesisSeconds),
        "First chunk ready in %.1f s.".format(Locale.US, firstChunkSeconds),
        peakMemoryMb?.let { "Peak memory: $it MB." } ?: "Peak memory: not reported.",
        if (stutters) "Verdict: slower than playback, so speech would stutter." else "Verdict: keeps up with playback.",
    )
    is KokoroSpeedResult.Unavailable -> listOf(message)
}