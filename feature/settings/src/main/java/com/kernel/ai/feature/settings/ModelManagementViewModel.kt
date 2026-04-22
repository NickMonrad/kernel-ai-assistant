package com.kernel.ai.feature.settings

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.prefs.ModelPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

data class ModelRowState(
    val model: KernelModel,
    val downloadState: DownloadState,
)

data class ModelManagementUiState(
    val models: List<ModelRowState> = emptyList(),
    val totalStorageUsedBytes: Long = 0,
    val freeSpaceBytes: Long = 0,
    val hfAuthenticated: Boolean = false,
    val hfUsername: String? = null,
    val preferredModel: KernelModel? = null,
    val personaMode: PersonaMode = PersonaMode.HALF,
)

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val authRepository: HuggingFaceAuthRepository,
    private val jandalPersona: JandalPersona,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val uiState = combine(
        modelDownloadManager.downloadStates,
        authRepository.isAuthenticated,
        authRepository.username,
        modelPreferences.preferredConversationModel,
        jandalPersona.personaMode,
    ) { downloadStates, hfAuthenticated, hfUsername, preferredModel, personaMode ->
        ModelManagementUiState(
            models = KernelModel.entries.map { model ->
                ModelRowState(
                    model = model,
                    downloadState = downloadStates[model] ?: DownloadState.NotDownloaded,
                )
            },
            totalStorageUsedBytes = calculateStorageUsed(),
            freeSpaceBytes = calculateFreeSpace(),
            hfAuthenticated = hfAuthenticated,
            hfUsername = hfUsername,
            preferredModel = preferredModel,
            personaMode = personaMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ModelManagementUiState(),
    )

    fun downloadModel(model: KernelModel) {
        modelDownloadManager.startDownload(model)
    }

    fun cancelDownload(model: KernelModel) {
        modelDownloadManager.cancelDownload(model)
    }

    fun deleteModel(model: KernelModel) {
        if (model.isRequired || model.isBundled) return
        viewModelScope.launch(Dispatchers.IO) {
            model.localFile(context).delete()
            // Also delete any stale .tmp resume file
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
