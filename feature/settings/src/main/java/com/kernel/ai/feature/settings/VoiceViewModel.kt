package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.AndroidNativeRecognitionAvailability
import com.kernel.ai.core.voice.AndroidNativeRecognitionLocaleStatus
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.readVoiceCapabilities
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.InflectMicroModelSpec
import com.kernel.ai.core.voice.heyJandalDownloadModels
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
import com.kernel.ai.core.voice.Asr4allVoiceInputController
import com.kernel.ai.core.voice.kernelModels
import com.kernel.ai.core.voice.queueHeyJandalSttModel
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
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
)

data class SherpaVoiceRowUiState(
    val voice: SherpaPiperVoice,
    val downloadState: VoicePackDownloadState = VoicePackDownloadState.NotDownloaded,
)

data class KokoroVoiceRowUiState(
    val voice: SherpaKokoroVoice,
    val downloadState: VoicePackDownloadState = VoicePackDownloadState.NotDownloaded,
)

data class VoiceUiState(
    val spokenResponsesEnabled: Boolean = true,
    val selectedInputEngine: VoiceInputEngine = VoiceInputEngine.Auto,
    val availableInputEngines: List<VoiceInputEngine> = VoiceInputEngine.entries,
    val asr4allSize: Asr4AllSize = Asr4AllInstall.defaultSize,
    val asr4allSizes: List<Asr4AllSize> = listOf(Asr4AllInstall.defaultSize),
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
    val kokoroVoices: List<KokoroVoiceRowUiState> = SherpaKokoroVoice.entries.map { voice ->
        KokoroVoiceRowUiState(voice = voice)
    },
    val selectedKokoroVoice: SherpaKokoroVoice = SherpaKokoroVoice.KokoroMultiLangInt8,
    val kokoroActiveSpeakerId: Int = 0,
    val isSelectedKokoroVoiceDownloaded: Boolean = false,
    // ── Hey Jandal / Default Assistant ───────────────────────────────────────
    /** True when Jandal is the system's Default Digital Assistant (RoleManager.ROLE_ASSISTANT). */
    val isDefaultAssistant: Boolean = false,
    /** True when "Listen for Hey Jandal" is enabled in preferences. */
    val heyJandalEnabled: Boolean = false,
    /** Bytes turning Hey Jandal on still downloads for the selected input ([heyJandalDownloadModels]); 0 when on disk (#1931). */
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
    /** Per-voice availability state for Kokoro voices. */
    val kokoroVoiceAvailability: Map<SherpaKokoroVoice, ModelAvailabilityState> = emptyMap(),
)
internal fun resolveAndroidNativeAvailabilityMessage(
    availability: AndroidNativeRecognitionAvailability,
): String? = availability.warningMessage

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
    private val piperVoiceSetup: PiperVoiceSetup,
    private val asr4allVoiceInputController: Asr4allVoiceInputController,
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
            val availability = androidNativeRecognitionSupport.getAvailability()
            val nativeWarning = resolveAndroidNativeAvailabilityMessage(availability)
            _uiState.update {
                it.copy(
                    androidNativeAvailabilityMessage = nativeWarning,
                    androidNativeLanguageName = availability.languageDisplayName,
                    androidNativeLocaleReady = availability.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready,
                    androidNativeCapabilities = runCatching { readVoiceCapabilities(context) }.getOrNull(),
                )
            }
        }
        viewModelScope.launch {
            voiceInputPreferences.selectedEngine.collect { engine ->
                _uiState.update {
                    it.copy(
                        selectedInputEngine = engine,
                        heyJandalDownloadBytes = heyJandalDownloadBytes(engine, modelDownloadManager.downloadStates.value),
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
                _uiState.update { state ->
                    state.copy(
                        selectedKokoroVoice = voice,
                        isSelectedKokoroVoiceDownloaded =
                            state.kokoroVoices.firstOrNull { it.voice == voice }
                                ?.downloadState is VoicePackDownloadState.Downloaded,
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.kokoroActiveSpeakerId.collect { sid ->
                _uiState.update { it.copy(kokoroActiveSpeakerId = sid) }
            }
        }
        viewModelScope.launch {
            sherpaVoicePackDownloadManager.kokoroDownloadStates.collect { states ->
                _uiState.update { state ->
                    val rows = SherpaKokoroVoice.entries.map { voice ->
                        KokoroVoiceRowUiState(
                            voice = voice,
                            downloadState = states[voice] ?: VoicePackDownloadState.NotDownloaded,
                        )
                    }
                    state.copy(
                        kokoroVoices = rows,
                        isSelectedKokoroVoiceDownloaded =
                            states[state.selectedKokoroVoice] is VoicePackDownloadState.Downloaded,
                        kokoroVoiceAvailability = SherpaKokoroVoice.entries.associateWith { voice ->
                            (states[voice] ?: VoicePackDownloadState.NotDownloaded).toModelAvailability()
                        },
                    )
                }
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
                    queueHeyJandalSttModel(voiceInputPreferences, modelDownloadManager, isReleaseBuild)
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
            combine(modelDownloadManager.downloadStates, voiceInputPreferences.asr4allSize) { states, stored ->
                states to Asr4AllInstall.effectiveSize(stored, hardwareProfileDetector.profile.tier)
            }.collect { (states, asr4allSize) ->
                val perFamilyStates = (SherpaSttModelSpec.ALL.mapValues { (_, spec) ->
                    computeDownloadState(
                        spec.requiredFileNames.mapNotNull { fileName -> KernelModel.entries.firstOrNull { it.fileName == fileName } },
                        states,
                    )
                }) + (VoiceInputEngine.Asr4all to computeDownloadState(Asr4AllInstall.models(asr4allSize), states))
                val inflectStates = inflectModels.associateWith { model ->
                    states[model] ?: DownloadState.NotDownloaded
                }
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
                        heyJandalDownloadBytes = heyJandalDownloadBytes(it.selectedInputEngine, states),
                        inflectMicroStates = inflectStates,
                        inflectMicroAvailability = inflectAvailability,
                        isInflectMicroReady = inflectAvailability is ModelAvailabilityState.Ready,
                        sherpaSttStates = perFamilyStates,
                    )
                }
            }
        }
    }

    /**
     * Computes a [SherpaSttDownloadState] from the download states of the required models.
     */
    private fun computeDownloadState(
        requiredModels: List<KernelModel>,
        states: Map<KernelModel, DownloadState>,
    ): SherpaSttDownloadState {
        val allDownloaded = requiredModels.all { states[it] is DownloadState.Downloaded }
        val anyDownloading = requiredModels.any { states[it] is DownloadState.Downloading }
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
        )
    }

    private fun heyJandalDownloadBytes(input: VoiceInputEngine, states: Map<KernelModel, DownloadState>): Long =
        heyJandalDownloadModels(input, isReleaseBuild)
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
        if (engine == VoiceInputEngine.Asr4all) Asr4AllInstall.models(_uiState.value.asr4allSize)
        else SherpaSttModelSpec.forEngine(engine)?.kernelModels().orEmpty()

    fun downloadSherpaStt(engine: VoiceInputEngine) {
        if (isReleaseBuild && engine.isSherpaFamily) return
        modelsFor(engine).forEach { modelDownloadManager.startDownload(it) }
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
        val currentStates = modelDownloadManager.downloadStates.value
        modelsFor(engine)
            .filter { currentStates[it] is DownloadState.Downloading }
            .forEach { modelDownloadManager.cancelDownload(it) }
    }

    fun deleteSherpaStt(engine: VoiceInputEngine) {
        viewModelScope.launch(Dispatchers.IO) {
            val models = modelsFor(engine)
            val deleteFiles = {
                models.forEach { model ->
                    val file = model.localFile(context)
                    file.delete()
                    val tmp = java.io.File(file.absolutePath + ".tmp")
                    if (tmp.exists()) tmp.delete()
                    modelDownloadManager.refreshState(model)
                }
            }
            // asr4all's engine reads these files: discard its capture and close it under its start
            // lock first, so no start can reopen them while they are being deleted.
            if (engine == VoiceInputEngine.Asr4all) {
                // A null result means the capture could not be released: nothing was deleted, so the engine stays selected.
                if (asr4allVoiceInputController.withEngineReleased(deleteFiles) == null) return@launch
            } else {
                deleteFiles()
            }
            if (_uiState.value.selectedInputEngine == engine) {
                withContext(Dispatchers.Main) {
                    setVoiceInputEngine(VoiceInputEngine.Auto)
                }
            }
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
        _uiState.update { it.copy(selectedOutputEngine = engine).withInflectAvailability() }
        viewModelScope.launch {
            voiceOutputPreferences.setSelectedEngine(engine)
            // Re-tapping the selected engine must not re-run the Piper start logic.
            if (engine != previous) piperVoiceSetup.onEngineChosen(engine)
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
     * Chooses the asr4all size. Only the selected size stays installed: the previous size's files
     * are deleted, and the new size is not downloaded automatically.
     */
    fun setAsr4allSize(size: Asr4AllSize) {
        if (size !in Asr4AllInstall.selectableSizes(hardwareProfileDetector.profile.tier)) return
        val previous = _uiState.value.asr4allSize
        if (previous == size) return
        _uiState.update { it.copy(asr4allSize = size) }
        viewModelScope.launch(Dispatchers.IO) {
            Asr4AllInstall.models(previous)
                .filter { modelDownloadManager.downloadStates.value[it] is DownloadState.Downloading }
                .forEach { modelDownloadManager.cancelDownload(it) }
            // The engine reads the files being deleted; release it under its start lock first. Null means the
            // capture could not be released, so the old files stay and the selection reverts.
            val deleted = asr4allVoiceInputController.withEngineReleased {
                Asr4AllInstall.deleteFiles(context, previous)
            }
            if (deleted == null) {
                _uiState.update { it.copy(asr4allSize = previous) }
                return@launch
            }
            voiceInputPreferences.setAsr4allSize(size)
            (Asr4AllInstall.models(previous) + Asr4AllInstall.models(size))
                .forEach { modelDownloadManager.refreshState(it) }
        }
    }

    fun setAutoStartAlertVoiceCommandsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoStartAlertVoiceCommandsEnabled = enabled) }
        viewModelScope.launch {
            voiceInputPreferences.setAutoStartAlertVoiceCommandsEnabled(enabled)
        }
    }

    // ── Kokoro actions ────────────────────────────────────────────────────────

    fun setKokoroVoice(voice: SherpaKokoroVoice) {
        val row = _uiState.value.kokoroVoices.firstOrNull { it.voice == voice }
        if (row?.downloadState !is VoicePackDownloadState.Downloaded) return
        _uiState.update { it.copy(selectedKokoroVoice = voice) }
        viewModelScope.launch {
            voiceOutputPreferences.setSelectedKokoroVoice(voice)
        }
    }

    fun downloadKokoroVoice(voice: SherpaKokoroVoice) {
        if (isReleaseBuild) return
        sherpaVoicePackDownloadManager.startKokoroDownload(voice)
    }

    fun cancelKokoroVoiceDownload(voice: SherpaKokoroVoice) {
        sherpaVoicePackDownloadManager.cancelKokoroDownload(voice)
    }

    fun deleteKokoroVoice(voice: SherpaKokoroVoice) {
        sherpaVoicePackDownloadManager.deleteKokoroVoice(voice)
    }
    fun setKokoroActiveSpeakerId(sid: Int) {
        _uiState.update { it.copy(kokoroActiveSpeakerId = sid) }
        viewModelScope.launch {
            voiceOutputPreferences.setKokoroActiveSpeakerId(sid)
        }
    }
    // ── Hey Jandal ────────────────────────────────────────────────────────────

    fun setHeyJandalEnabled(enabled: Boolean) {
        _uiState.update { it.copy(heyJandalEnabled = enabled) }
        viewModelScope.launch {
            wakeWordPreferences.setHeyJandalEnabled(enabled)
            if (enabled) {
                queueHeyJandalSttModel(voiceInputPreferences, modelDownloadManager, isReleaseBuild)
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