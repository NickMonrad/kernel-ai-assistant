package com.kernel.ai.feature.settings

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.ConversationModelRules
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.prefs.ModelPreferences
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.voice.SherpaSttModelSpec
import com.kernel.ai.core.voice.kernelModels
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.Asr4AllInstall
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import com.kernel.ai.core.model.availability.ModelRoleCopy
import com.kernel.ai.core.model.availability.ModelRoleSection
import com.kernel.ai.core.model.availability.ModelRoles
import com.kernel.ai.core.model.availability.StillNeededItem
import com.kernel.ai.core.model.availability.AvailabilitySummary
import com.kernel.ai.core.model.availability.GatedModelStatus
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.model.availability.computeAvailabilitySummary
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

data class ModelRowState(
    val model: KernelModel,
    val downloadState: DownloadState,
    val downloadSource: DownloadSource = DownloadSource.USER_INITIATED,
    val gatedStatus: GatedModelStatus = GatedModelStatus.NONE,
)

data class ModelManagementUiState(
    val models: List<ModelRowState> = emptyList(),
    val totalStorageUsedBytes: Long = 0,
    val freeSpaceBytes: Long = 0,
    val hfAuthenticated: Boolean = false,
    val hfUsername: String? = null,
    val preferredModel: KernelModel? = null,
    val personaMode: PersonaMode = PersonaMode.HALF,
    val availabilitySummary: AvailabilitySummary = AvailabilitySummary(total = 0),
    /** Role sections for the Models screen, in display order. */
    val roleSections: List<ModelRoleSection> = emptyList(),
    /** Required roles that are not ready. Empty means "All set". */
    val stillNeeded: List<StillNeededItem> = emptyList(),
    /** Current device hardware tier: drives recommendations and "Not needed on this phone". */
    val deviceTier: HardwareTier? = null,
    /** Selected speech-input engine status, read-only on the Models screen. */
    val speechRecognitionStatus: VoiceSectionStatus = VoiceSectionStatus.NOT_DOWNLOADED,
    /** The asr4all size under the speech row (e.g. "asr4all M · 60 MB"), for the engines that use it (#2048). */
    val speechRecognitionDetail: String? = null,
    /** Jandal's voice (Cori) status, read-only on the Models screen. */
    val voiceOutputStatus: VoiceSectionStatus = VoiceSectionStatus.NOT_DOWNLOADED,
    /** The Downloads section switch: true only when mobile data is allowed for model downloads (#1855). */
    val mobileDataAllowed: Boolean = false,
    /** Models queued on Wi-Fi only while the phone is on mobile data. Drives "Waiting for Wi-Fi" (#1855). */
    val waitingForWifi: Set<KernelModel> = emptySet(),
)

/** Read-only status for a voice section on the Models screen. Managed in Voice settings. */
enum class VoiceSectionStatus { READY, DOWNLOADING, NOT_DOWNLOADED, BUILT_IN, OTHER_ENGINE }

/**
 * The speech row follows the selected input engine (#1982): Android native is built in, Auto and asr4all
 * need the asr4all files of the effective size, and each Sherpa engine needs the files of its own spec.
 */
internal fun speechStatusOf(
    engine: VoiceInputEngine,
    states: Map<KernelModel, DownloadState>,
    asr4allSize: Asr4AllSize,
): VoiceSectionStatus {
    val files = when (engine) {
        VoiceInputEngine.AndroidNative -> return VoiceSectionStatus.BUILT_IN
        VoiceInputEngine.Auto, VoiceInputEngine.Asr4all -> Asr4AllInstall.models(asr4allSize)
        else -> SherpaSttModelSpec.forEngine(engine)?.kernelModels() ?: return VoiceSectionStatus.OTHER_ENGINE
    }
    val downloads = files.map { states[it] ?: DownloadState.NotDownloaded }
    return when {
        downloads.all { it is DownloadState.Downloaded } -> VoiceSectionStatus.READY
        downloads.any { it is DownloadState.Downloading } -> VoiceSectionStatus.DOWNLOADING
        else -> VoiceSectionStatus.NOT_DOWNLOADED
    }
}

/** The asr4all size under the speech row, for the engines that use it (#2048). Null for the other engines. */
internal fun speechDetailOf(engine: VoiceInputEngine, asr4allSize: Asr4AllSize): String? = when (engine) {
    VoiceInputEngine.Auto, VoiceInputEngine.Asr4all ->
        "asr4all ${asr4allSize.name} · ${ModelRoleCopy.sizeLabel(Asr4AllInstall.models(asr4allSize).sumOf { it.approxSizeBytes })}"
    else -> null
}

/** The voice-output row follows the selected engine. Only the Sherpa engine uses the Cori pack. */
internal fun voiceOutputStatusOf(engine: VoiceOutputEngine, state: VoicePackDownloadState?): VoiceSectionStatus =
    when (engine) {
        VoiceOutputEngine.AndroidTts -> VoiceSectionStatus.BUILT_IN
        VoiceOutputEngine.SherpaExperimental -> when (state) {
            is VoicePackDownloadState.Downloaded -> VoiceSectionStatus.READY
            is VoicePackDownloadState.Downloading -> VoiceSectionStatus.DOWNLOADING
            else -> VoiceSectionStatus.NOT_DOWNLOADED
        }
        else -> VoiceSectionStatus.OTHER_ENGINE
    }

private data class StorageMetrics(
    val used: Long = 0,
    val free: Long = 0,
)

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val authRepository: HuggingFaceAuthRepository,
    private val jandalPersona: JandalPersona,
    private val gatedModelStatusRepository: GatedModelStatusRepository,
    private val sherpaVoicePackDownloadManager: SherpaVoicePackDownloadManager,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Dispatcher for model file IO; replaced by the test scheduler in unit tests. */
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _storageMetrics = MutableStateFlow(StorageMetrics())

    /** Per-model gated status map, collected from DataStore. */
    private val _gatedStatuses = MutableStateFlow<Map<KernelModel, GatedModelStatus>>(emptyMap())

    init {
        viewModelScope.launch {
            val gatedModels = KernelModel.entries.filter {
                it.showInModelManagement && !it.isDeprecated && it.isGated
            }
            gatedModels.forEach { model ->
                launch {
                    gatedModelStatusRepository.get(model).collect { status ->
                        _gatedStatuses.update { it.toMutableMap().apply { put(model, status) } }
                    }
                }
            }
        }
        // Recompute storage metrics on each download-state change; file IO runs on ioDispatcher.
        viewModelScope.launch {
            modelDownloadManager.downloadStates
                .debounce(500)
                .collect {
                    val used = withContext(ioDispatcher) { calculateStorageUsed() }
                    val free = withContext(ioDispatcher) { calculateFreeSpace() }
                    _storageMetrics.value = StorageMetrics(used = used, free = free)
                }
        }
    }

    val uiState: StateFlow<ModelManagementUiState> = combine(
        modelDownloadManager.downloadStates,
        modelDownloadManager.downloadSources,
        authRepository.isAuthenticated,
        authRepository.username,
        modelPreferences.preferredConversationModel,
        jandalPersona.personaMode,
        _storageMetrics,
        _gatedStatuses,
        sherpaVoicePackDownloadManager.downloadStates,
        voiceOutputPreferences.selectedEngine,
        modelDownloadManager.waitingForWifi,
        downloadNetworkPreferences.choice,
        voiceInputPreferences.selectedEngine,
        voiceInputPreferences.asr4allSize,
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        val downloadStates = array[0] as Map<KernelModel, DownloadState>
        @Suppress("UNCHECKED_CAST")
        val downloadSources = array[1] as Map<KernelModel, DownloadSource>
        val hfAuthenticated = array[2] as Boolean
        @Suppress("UNCHECKED_CAST")
        val hfUsername = array[3] as String?
        @Suppress("UNCHECKED_CAST")
        val preferredModel = array[4] as KernelModel?
        val personaMode = array[5] as PersonaMode
        val storage = array[6] as StorageMetrics
        val gatedStatuses = array[7] as Map<KernelModel, GatedModelStatus>
        @Suppress("UNCHECKED_CAST")
        val voicePacks = array[8] as Map<SherpaPiperVoice, VoicePackDownloadState>
        val engine = array[9] as VoiceOutputEngine
        @Suppress("UNCHECKED_CAST")
        val waitingForWifi = array[10] as Set<KernelModel>
        val mobileDataAllowed = array[11] as DownloadNetworkChoice? == DownloadNetworkChoice.MOBILE_DATA_ALLOWED
        val inputEngine = array[12] as VoiceInputEngine
        val asr4allStored = array[13] as Asr4AllSize

        val filteredModels = KernelModel.entries.filter {
            it.showInModelManagement && !it.isDeprecated
        }
        val models = filteredModels.map { model ->
            ModelRowState(
                model = model,
                downloadState = downloadStates[model] ?: DownloadState.NotDownloaded,
                downloadSource = downloadSources[model] ?: DownloadSource.USER_INITIATED,
                gatedStatus = gatedStatuses[model] ?: GatedModelStatus.NONE,
            )
        }
        val summary = computeAvailabilitySummary(
            models = filteredModels,
            downloadStates = downloadStates,
            hfAuth = hfAuthenticated,
            downloadSources = downloadSources,
            gatedStatuses = gatedStatuses,
        )
        val tier = modelDownloadManager.deviceTier
        ModelManagementUiState(
            models = models,
            totalStorageUsedBytes = storage.used,
            freeSpaceBytes = storage.free,
            hfAuthenticated = hfAuthenticated,
            hfUsername = hfUsername,
            preferredModel = preferredModel,
            personaMode = personaMode,
            availabilitySummary = summary,
            roleSections = ModelRoles.sections(preferred = preferredModel, tier = tier, states = downloadStates),
            stillNeeded = ModelRoles.stillNeeded(tier = tier, states = downloadStates),
            deviceTier = tier,
            speechRecognitionStatus = speechStatusOf(
                engine = inputEngine,
                states = downloadStates,
                asr4allSize = Asr4AllInstall.effectiveSize(asr4allStored, tier),
            ),
            speechRecognitionDetail = speechDetailOf(inputEngine, Asr4AllInstall.effectiveSize(asr4allStored, tier)),
            voiceOutputStatus = voiceOutputStatusOf(engine, voicePacks[SherpaPiperVoice.CoriHigh]),
            mobileDataAllowed = mobileDataAllowed,
            waitingForWifi = waitingForWifi,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ModelManagementUiState(),
    )

    fun downloadModel(model: KernelModel) {
        modelDownloadManager.startDownload(model)
    }

    fun updateModel(model: KernelModel) {
        modelDownloadManager.startDownload(model, force = true)
    }

    fun cancelDownload(model: KernelModel) {
        modelDownloadManager.cancelDownload(model)
    }

    fun deleteModel(model: KernelModel) {
        if (model.isBundled) return
        // The chat brain in use cannot be deleted. A saved pick this tier cannot select is ignored
        // by the engine, so reset it to automatic first; otherwise its file could never be removed.
        if (model == uiState.value.preferredModel) {
            if (model in ConversationModelRules.selectableConversationModels(modelDownloadManager.deviceTier)) return
            setPreferredModel(null)
        }
        // Cancel any in-progress download before deleting the file
        val currentState = modelDownloadManager.downloadStates.value[model]
        if (currentState is DownloadState.Downloading) {
            modelDownloadManager.cancelDownload(model)
        }
        viewModelScope.launch(ioDispatcher) {
            model.localFile(context).delete()
            val tmpFile = java.io.File(model.localFile(context).absolutePath + ".tmp")
            if (tmpFile.exists()) tmpFile.delete()
            // Remove stale WorkManager metadata so restart cannot reassert Downloaded
            modelDownloadManager.pruneCompletedWork(model)
            // Persist suppression for non-required models so they don't auto-queue on restart
            if (!model.isRequired) {
                modelPreferences.suppressOptionalModel(model)
            }
            withContext(Dispatchers.Main) {
                modelDownloadManager.refreshState(model)
            }
        }
    }

    fun setPreferredModel(model: KernelModel?) {
        viewModelScope.launch {
            try {
                modelPreferences.setPreferredModel(model)
            } catch (_: IOException) { /* best-effort */ }
        }
    }

    /** Same preference and setter the setup guide writes (#1855). */
    fun setMobileDataAllowed(allowed: Boolean) {
        viewModelScope.launch {
            modelDownloadManager.setDownloadNetworkChoice(
                if (allowed) DownloadNetworkChoice.MOBILE_DATA_ALLOWED else DownloadNetworkChoice.WIFI_ONLY,
            )
        }
    }

    fun setPersonaMode(mode: PersonaMode) {
        jandalPersona.setPersonaMode(mode)
    }

    fun startAuth() = authRepository.startAuthFlow()

    fun signOut() = authRepository.signOut()

    private fun calculateStorageUsed(): Long =
        KernelModel.entries.sumOf { model ->
            val file = model.localFile(context)
            if (file.exists()) file.length() else 0L
        }

    private fun calculateFreeSpace(): Long =
        try {
            val path = context.getExternalFilesDir(null)?.path ?: context.filesDir.path
            StatFs(path).availableBytes
        } catch (_: Exception) { 0L }
}
