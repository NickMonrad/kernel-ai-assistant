package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val sherpaVoicePackDownloadManager: SherpaVoicePackDownloadManager,
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
    }

    fun setVoiceInputEngine(engine: VoiceInputEngine) {
        _uiState.update { it.copy(selectedInputEngine = engine) }
        viewModelScope.launch {
            voiceInputPreferences.setSelectedEngine(engine)
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
}
