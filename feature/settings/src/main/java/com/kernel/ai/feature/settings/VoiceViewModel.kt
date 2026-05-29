package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

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
    val selectedInputEngine: VoiceInputEngine = VoiceInputEngine.Vosk,
    val selectedOutputEngine: VoiceOutputEngine = VoiceOutputEngine.AndroidTts,
    val selectedSherpaVoice: SherpaPiperVoice = SherpaPiperVoice.JennyDioco,
    val sherpaSpeed: Float = 0.85f,
    val sherpaPitch: Float = 1.0f,
    val sherpaGain: Float = 1.5f,
    val autoSpeak: Boolean = true,
    val maxSpokenSentences: Int = 0,
    val sherpaVoices: List<SherpaVoiceRowUiState> = SherpaPiperVoice.entries.map { voice ->
        SherpaVoiceRowUiState(voice = voice)
    },
    val autoStartAlertVoiceCommandsEnabled: Boolean = true,
    val androidNativeAvailabilityMessage: String? = null,
    val androidNativeLanguageSummary: String? = null,
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
    /** True when the hey_jandal_int8.tflite model file is present on device. */
    val isWakeWordModelAvailable: Boolean = false,
    /** Wake word confidence threshold in [0, 1].  Reflects [WakeWordPreferences.confidenceThreshold]. */
    val wakeWordThreshold: Float = WAKE_WORD_DEFAULT_THRESHOLD,
    // ── Sherpa-ONNX STT model download state ─────────────────────────────────
    /** True when all four STT model files are present in external storage. */
    val isSherpaOnnxSttDownloaded: Boolean = false,
    /** True when at least one STT model file is actively downloading. */
    val isSherpaOnnxSttDownloading: Boolean = false,
    /** Combined download progress across all STT model files (0–1). */
    val sherpaOnnxSttProgress: Float = 0f,
    /** Non-null when a download attempt failed. */
    val sherpaOnnxSttError: String? = null,
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val availability = androidNativeRecognitionSupport.getAvailability()
            _uiState.update {
                it.copy(
                    androidNativeAvailabilityMessage = availability.warningMessage,
                    androidNativeLanguageSummary = availability.languageSummary,
                )
            }
        }
        viewModelScope.launch {
            voiceInputPreferences.selectedEngine.collect { engine ->
                _uiState.update { it.copy(selectedInputEngine = engine) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.spokenResponsesEnabled.collect { enabled ->
                _uiState.update { it.copy(spokenResponsesEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.selectedEngine.collect { engine ->
                _uiState.update { it.copy(selectedOutputEngine = engine) }
            }
        }
        viewModelScope.launch {
            voiceOutputPreferences.selectedSherpaVoice.collect { voice ->
                _uiState.update { it.copy(selectedSherpaVoice = voice) }
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
                    val sherpaRows = SherpaPiperVoice.entries.map { voice ->
                        SherpaVoiceRowUiState(
                            voice = voice,
                            downloadState = states[voice] ?: VoicePackDownloadState.NotDownloaded,
                        )
                    }
                    it.copy(
                        sherpaVoices = sherpaRows,
                        hasDownloadedSherpaVoice = sherpaRows.any { row ->
                            row.downloadState is VoicePackDownloadState.Downloaded
                        },
                        isSelectedSherpaVoiceDownloaded =
                            states[it.selectedSherpaVoice] is VoicePackDownloadState.Downloaded,
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
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceInputPreferences.autoStartAlertVoiceCommandsEnabled.collect { enabled ->
                _uiState.update { it.copy(autoStartAlertVoiceCommandsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            wakeWordPreferences.heyJandalEnabled.collect { enabled ->
                _uiState.update { it.copy(heyJandalEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            wakeWordPreferences.confidenceThreshold.collect { threshold ->
                _uiState.update { it.copy(wakeWordThreshold = threshold) }
            }
        }
        _uiState.update { it.copy(isWakeWordModelAvailable = wakeWordDetector.isAvailable) }
        viewModelScope.launch {
            modelDownloadManager.downloadStates.collect { states ->
                val sttModels = listOf(
                    KernelModel.SHERPA_STT_ENCODER,
                    KernelModel.SHERPA_STT_DECODER,
                    KernelModel.SHERPA_STT_JOINER,
                    KernelModel.SHERPA_STT_TOKENS,
                )
                val allDownloaded = sttModels.all { states[it] is DownloadState.Downloaded }
                val anyDownloading = sttModels.any { states[it] is DownloadState.Downloading }
                // Weighted completion: Downloaded=full weight, Downloading=partial, else=0.
                // Weights derived from approxSizeBytes so they stay in sync with KernelModel.
                val totalBytes = sttModels.sumOf { it.approxSizeBytes }.toFloat()
                val weightedProgress = sttModels.map { model ->
                    val weight = model.approxSizeBytes.toFloat()
                    when (val s = states[model]) {
                        is DownloadState.Downloaded -> weight
                        is DownloadState.Downloading -> s.progress * weight
                        else -> 0f
                    }
                }.sum() / totalBytes
                val error = sttModels
                    .mapNotNull { (states[it] as? DownloadState.Error)?.message }
                    .firstOrNull()
                _uiState.update {
                    it.copy(
                        isSherpaOnnxSttDownloaded = allDownloaded,
                        isSherpaOnnxSttDownloading = anyDownloading && !allDownloaded,
                        sherpaOnnxSttProgress = weightedProgress,
                        sherpaOnnxSttError = error,
                    )
                }
            }
        }
    }

    fun setVoiceInputEngine(engine: VoiceInputEngine) {
        _uiState.update { it.copy(selectedInputEngine = engine) }
        viewModelScope.launch {
            voiceInputPreferences.setSelectedEngine(engine)
        }
    }


    private val sttModels = listOf(
        KernelModel.SHERPA_STT_ENCODER,
        KernelModel.SHERPA_STT_DECODER,
        KernelModel.SHERPA_STT_JOINER,
        KernelModel.SHERPA_STT_TOKENS,
    )

    fun downloadSherpaOnnxStt() {
        sttModels.forEach { modelDownloadManager.startDownload(it) }
    }

    fun cancelSherpaOnnxSttDownload() {
        // Only cancel parts that are actively downloading — skipping already-Downloaded entries
        // prevents ModelDownloadManager from incorrectly resetting their state to NotDownloaded.
        val currentStates = modelDownloadManager.downloadStates.value
        sttModels
            .filter { currentStates[it] is DownloadState.Downloading }
            .forEach { modelDownloadManager.cancelDownload(it) }
    }


    fun deleteSherpaOnnxStt() {
        viewModelScope.launch(Dispatchers.IO) {
            sttModels.forEach { model ->
                val file = model.localFile(context)
                file.delete()
                val tmp = java.io.File(file.absolutePath + ".tmp")
                if (tmp.exists()) tmp.delete()
                modelDownloadManager.refreshState(model)
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
        _uiState.update { it.copy(selectedOutputEngine = engine) }
        viewModelScope.launch {
            voiceOutputPreferences.setSelectedEngine(engine)
        }
    }

    fun setSherpaVoice(voice: SherpaPiperVoice) {
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
            // Persisting the preference is sufficient — KernelAIApplication observes
            // WakeWordPreferences.heyJandalEnabled and starts/stops WakeWordService.
            wakeWordPreferences.setHeyJandalEnabled(enabled)
        }
    }

    fun setWakeWordThreshold(threshold: Float) {
        _uiState.update { it.copy(wakeWordThreshold = threshold) }
        viewModelScope.launch {
            wakeWordPreferences.setConfidenceThreshold(threshold)
        }
    }

    /**
     * Called from VoiceScreen on every resume to keep the assistant-role badge in sync.
     * [android.app.role.RoleManager.isRoleHeld] is synchronous — no coroutine needed.
     */
    fun refreshAssistantStatus(isRoleHeld: Boolean) {
        _uiState.update { it.copy(isDefaultAssistant = isRoleHeld) }
    }
}
