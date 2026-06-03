package com.kernel.ai.feature.settings

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.prefs.ModelPreferences
import com.kernel.ai.core.model.availability.AvailabilitySummary
import com.kernel.ai.core.model.availability.GatedModelStatus
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.model.availability.computeAvailabilitySummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

data class ModelRowState(
    val model: KernelModel,
    val downloadState: DownloadState,
    val downloadSource: DownloadSource = DownloadSource.USER_INITIATED,
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
)

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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _storageMetrics = MutableStateFlow(StorageMetrics())

    /** Per-model gated status map, collected from DataStore. */
    private val _gatedStatuses = MutableStateFlow<Map<KernelModel, GatedModelStatus>>(emptyMap())

    init {
        viewModelScope.launch {
            val gatedModels = KernelModel.entries.filter {
                it.showInModelManagement && !it.isDeprecated && it.isGated
            }
            gatedModels.forEach { model ->
                gatedModelStatusRepository.get(model).collect { status ->
                    _gatedStatuses.update { it.toMutableMap().apply { put(model, status) } }
                }
            }
        }
        // Compute storage metrics on IO dispatcher, driven by download-state changes
        viewModelScope.launch(Dispatchers.IO) {
            modelDownloadManager.downloadStates.collect {
                val used = calculateStorageUsed()
                val free = calculateFreeSpace()
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

        val filteredModels = KernelModel.entries.filter {
            it.showInModelManagement && !it.isDeprecated
        }
        val models = filteredModels.map { model ->
            ModelRowState(
                model = model,
                downloadState = downloadStates[model] ?: DownloadState.NotDownloaded,
                downloadSource = downloadSources[model] ?: DownloadSource.USER_INITIATED,
            )
        }
        val summary = computeAvailabilitySummary(
            models = filteredModels,
            downloadStates = downloadStates,
            hfAuth = hfAuthenticated,
            downloadSources = downloadSources,
            gatedStatuses = gatedStatuses,
        )
        ModelManagementUiState(
            models = models,
            totalStorageUsedBytes = storage.used,
            freeSpaceBytes = storage.free,
            hfAuthenticated = hfAuthenticated,
            hfUsername = hfUsername,
            preferredModel = preferredModel,
            personaMode = personaMode,
            availabilitySummary = summary,
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
        if (model.isRequired || model.isBundled) return
        viewModelScope.launch(Dispatchers.IO) {
            model.localFile(context).delete()
            val tmpFile = java.io.File(model.localFile(context).absolutePath + ".tmp")
            if (tmpFile.exists()) tmpFile.delete()
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
