package com.kernel.ai.core.inference.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelDownloadManager"

/**
 * Hilt singleton that manages the download lifecycle of all [KernelModel]s.
 *
 * - Persists download state in [WorkManager] so downloads survive process death.
 * - Exposes [downloadStates] as a [StateFlow] for Compose UI observation.
 * - Checks internal storage on startup to detect already-downloaded models.
 * - Supports resume: interrupted downloads continue from their `.tmp` byte offset.
 *
 * Usage:
 * ```kotlin
 * val states = modelDownloadManager.downloadStates.collectAsState()
 * modelDownloadManager.startDownload(KernelModel.GEMMA_4_E2B)
 * ```
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardwareProfileDetector: com.kernel.ai.core.inference.hardware.HardwareProfileDetector,
    private val modelPreferences: com.kernel.ai.core.inference.prefs.ModelPreferences,
    private val authRepository: HuggingFaceAuthRepository,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _downloadStates: MutableStateFlow<Map<KernelModel, DownloadState>> =
        MutableStateFlow(
            KernelModel.entries.associateWith { model ->
                if (model.isDownloaded(context)) {
                    DownloadState.Downloaded(model.localFile(context).absolutePath)
                } else {
                    DownloadState.NotDownloaded
                }
            }
        )

    val downloadStates: StateFlow<Map<KernelModel, DownloadState>> = _downloadStates.asStateFlow()

    init {
        // Resume observing any in-progress workers that survived a process restart
        KernelModel.entries.forEach { model ->
            scope.launch { observeWorkInfo(model) }
        }
        // Auto-queue all required models that aren't yet downloaded
        KernelModel.entries
            .filter {
                it.isRequired && !it.isDownloaded(context) &&
                (!it.isGated || authRepository.getAccessToken() != null)
            }
            .forEach { model ->
                Log.i(TAG, "Auto-queuing required model: ${model.displayName}")
                startDownload(model)
            }
        // Auto-queue tier-specific optional models (e.g. E-4B on FLAGSHIP)
        val tier = hardwareProfileDetector.profile.tier
        KernelModel.entries
            .filter {
                !it.isRequired && it.preferredForTier == tier && !it.isDownloaded(context) &&
                (!it.isGated || authRepository.getAccessToken() != null)
            }
            .forEach { model ->
                Log.i(TAG, "Auto-queuing ${model.displayName} for tier ${tier.name}")
                startDownload(model)
            }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enqueue a download for [model]. No-op if the model is already downloaded or
     * a download is already in-flight. Set [force] = true to re-download a corrupt file.
     */
    fun startDownload(model: KernelModel, force: Boolean = false) {
        if (!force && model.isDownloaded(context)) {
            updateState(model, DownloadState.Downloaded(model.localFile(context).absolutePath))
            return
        }

        Log.i(TAG, "Enqueuing download for ${model.displayName}")
        updateState(model, DownloadState.Downloading())

        val dataBuilder = Data.Builder()
            .putString(KEY_DOWNLOAD_URL, model.downloadUrl)
            .putString(KEY_FILE_NAME, model.fileName)
            .putString(KEY_MODEL_DISPLAY_NAME, model.displayName)
            .putLong(KEY_TOTAL_BYTES, model.approxSizeBytes)

        // Attach HF access token for gated models so the worker can authenticate
        if (model.isGated) {
            val token = authRepository.getAccessToken()
            if (token != null) {
                dataBuilder.putString(KEY_HF_ACCESS_TOKEN, token)
            } else {
                Log.w(TAG, "Model ${model.displayName} is gated but no HF token available")
            }
        }

        val inputData = dataBuilder.build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag(model.workerTag)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            model.workerTag,
            ExistingWorkPolicy.KEEP,
            request,
        )

        scope.launch { observeWorkInfo(model) }
    }

    /** Cancel an in-progress download. The partial `.tmp` file is preserved for resumption. */
    fun cancelDownload(model: KernelModel) {
        workManager.cancelUniqueWork(model.workerTag)
        updateState(model, DownloadState.NotDownloaded)
        Log.i(TAG, "Cancelled download for ${model.displayName}")
    }

    /**
     * Returns the absolute path to [model]'s local file if it's downloaded, or null.
     */
    fun getModelPath(model: KernelModel): String? {
        return if (model.isDownloaded(context)) model.localFile(context).absolutePath else null
    }

    /** True when all [KernelModel.isRequired] models are present on disk. */
    fun areRequiredModelsDownloaded(): Boolean =
        KernelModel.entries
            .filter { it.isRequired }
            .all { it.isDownloaded(context) }

    /**
     * Returns the best available conversation model.
     *
     * Priority:
     * 1. User-set preference (DataStore) — if the model is downloaded.
     * 2. Tier-based auto-select (e.g. E-4B on FLAGSHIP) — if downloaded.
     * 3. E-2B fallback (always available as required model).
     *
     * If the user-preferred model is not downloaded, falls back to tier/auto logic
     * and logs a warning.
     */
    suspend fun preferredConversationModel(): KernelModel {
        val userPref = modelPreferences.preferredConversationModel.first()
        if (userPref != null) {
            if (userPref.isDownloaded(context)) {
                return userPref
            } else {
                Log.w(TAG, "User-preferred model ${userPref.displayName} not downloaded — falling back to auto")
            }
        }
        val tier = hardwareProfileDetector.profile.tier
        val tierModel = KernelModel.entries
            .firstOrNull { it.preferredForTier == tier && it.isDownloaded(context) }
        return tierModel ?: KernelModel.GEMMA_4_E2B
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun updateState(model: KernelModel, state: DownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply { put(model, state) }
    }

    /**
     * Observes WorkManager's [WorkInfo] for [model]'s worker and maps it to [DownloadState].
     * Uses LiveData → coroutine bridge (WorkManager 2.8+ API).
     */
    private suspend fun observeWorkInfo(model: KernelModel) {
        // One-shot flag: the stale-worker guard only needs to run once per observation
        // session (the first ENQUEUED/RUNNING emission). Subsequent progress ticks skip
        // the filesystem check entirely, avoiding redundant mkdirs() syscalls.
        var filesystemChecked = false
        workManager
            .getWorkInfosByTagFlow(model.workerTag)
            .collect { infoList ->
                val info = infoList.firstOrNull() ?: return@collect

                val newState: DownloadState = when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        // Guard: a stale ENQUEUED job from a previous session must not
                        // overwrite a file that is already present on disk. Only check
                        // once per observation session to avoid per-tick filesystem I/O.
                        if (!filesystemChecked) {
                            filesystemChecked = true
                            val (localFile, alreadyPresent) = withContext(Dispatchers.IO) {
                                val f = model.localFile(context)
                                f to (f.exists() && f.length() > 0)
                            }
                            if (alreadyPresent) {
                                Log.i(TAG, "Stale enqueued/running worker but file present — cancelling: ${model.displayName}")
                                workManager.cancelUniqueWork(model.workerTag)
                                return@collect
                            }
                        }
                        val progress = info.progress
                        val totalBytes = model.approxSizeBytes
                        val downloadedBytes = progress.getLong(KEY_PROGRESS_BYTES, 0L)
                        val bps = progress.getLong(KEY_DOWNLOAD_RATE, 0L)
                        val remainingMs = progress.getLong(KEY_REMAINING_MS, 0L)
                        val fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        DownloadState.Downloading(
                            progress = fraction,
                            downloadedBytes = downloadedBytes,
                            bytesPerSecond = bps,
                            remainingMs = remainingMs,
                        )
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        val path = withContext(Dispatchers.IO) { model.localFile(context).absolutePath }
                        Log.i(TAG, "Download succeeded: $path")
                        DownloadState.Downloaded(localPath = path)
                    }

                    WorkInfo.State.FAILED -> {
                        // Stale WorkManager jobs from a previous session can fire FAILED
                        // after the file was already pushed manually (e.g. via ADB). Trust
                        // the file system over the worker state. Single withContext block
                        // materialises the File and checks it atomically (no TOCTOU window).
                        val (localFile, isPresent) = withContext(Dispatchers.IO) {
                            val f = model.localFile(context)
                            f to (f.exists() && f.length() > 0)
                        }
                        if (isPresent) {
                            Log.i(TAG, "Worker failed but file present — treating as Downloaded: ${model.displayName}")
                            DownloadState.Downloaded(localPath = localFile.absolutePath)
                        } else {
                            val errorMsg = info.outputData.getString(KEY_ERROR_MESSAGE)
                                ?: "Download failed"
                            Log.w(TAG, "Download failed for ${model.displayName}: $errorMsg")
                            DownloadState.Error(message = errorMsg)
                        }
                    }

                    WorkInfo.State.CANCELLED -> {
                        val (localFile, isPresent) = withContext(Dispatchers.IO) {
                            val f = model.localFile(context)
                            f to (f.exists() && f.length() > 0)
                        }
                        if (isPresent) {
                            Log.i(TAG, "Worker cancelled but file present — treating as Downloaded: ${model.displayName}")
                            DownloadState.Downloaded(localPath = localFile.absolutePath)
                        } else {
                            Log.i(TAG, "Download cancelled for ${model.displayName}")
                            DownloadState.NotDownloaded
                        }
                    }

                    else -> return@collect
                }
                updateState(model, newState)
            }
    }
}

/** Unique WorkManager work name for this model. */
private val KernelModel.workerTag: String get() = "download_${name.lowercase()}"
