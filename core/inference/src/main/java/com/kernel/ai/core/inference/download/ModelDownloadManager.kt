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
import com.kernel.ai.core.inference.hardware.HardwareTier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
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

    // Issue 3 fix: track active observer jobs to avoid accumulation on retries
    private val observerJobs = ConcurrentHashMap<KernelModel, Job>()

    private val _downloadStates: MutableStateFlow<Map<KernelModel, DownloadState>> =
        MutableStateFlow(
            KernelModel.entries.associateWith { model ->
                when {
                    model.isBundled -> DownloadState.Downloaded("bundled")
                    model.isDownloaded(context) -> DownloadState.Downloaded(model.localFile(context).absolutePath)
                    else -> DownloadState.NotDownloaded
                }
            }
        )

    val downloadStates: StateFlow<Map<KernelModel, DownloadState>> = _downloadStates.asStateFlow()

    val deviceTier: HardwareTier get() = hardwareProfileDetector.profile.tier

    init {
        // Resume observing any in-progress workers that survived a process restart
        KernelModel.entries.forEach { model ->
            ensureObserving(model)
        }
        val tier = hardwareProfileDetector.profile.tier  // hoist BEFORE the required-model loop
        // Auto-queue all required models that aren't yet downloaded
        KernelModel.entries
            .filter {
                it.isRequired && !it.isDownloaded(context) &&
                (!it.isGated || authRepository.getAccessToken() != null) &&
                // On FLAGSHIP, skip E2B — E4B is auto-queued below as the tier-preferred model
                !(it == KernelModel.GEMMA_4_E2B && tier == HardwareTier.FLAGSHIP)
            }
            .forEach { model ->
                Log.i(TAG, "Auto-queuing required model: ${model.displayName}")
                startDownload(model)
            }
        // Auto-queue tier-specific optional models (e.g. E-4B on FLAGSHIP)
        // NOTE: tier is already declared above
        KernelModel.entries
            .filter {
                !it.isRequired && it.preferredForTier == tier && !it.isDownloaded(context) &&
                (!it.isGated || authRepository.getAccessToken() != null)
            }
            .forEach { model ->
                Log.i(TAG, "Auto-queuing ${model.displayName} for tier ${tier.name}")
                startDownload(model)
            }
        // Auto-trigger gated required models when user signs in
        scope.launch {
            authRepository.isAuthenticated
                .filter { it }
                .collect {
                    KernelModel.entries
                        .filter { m -> m.isGated && m.isRequired }
                        .filter { m -> _downloadStates.value[m] is DownloadState.NotDownloaded }
                        .forEach { m -> startDownload(m) }
                }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enqueue a download for [model]. No-op if the model is already downloaded.
     * Set [force] = true to re-download a corrupt file.
     *
     * Policy logic to fix Samsung battery-optimisation stuck-ENQUEUED issue (#206):
     * - If a worker is genuinely RUNNING (has real progress) → [ExistingWorkPolicy.KEEP]
     *   so we don't interrupt an active download.
     * - Otherwise → [ExistingWorkPolicy.REPLACE] to unstick any stale ENQUEUED job that
     *   Samsung's battery manager prevented from dispatching, and to restart FAILED jobs.
     */
    fun startDownload(model: KernelModel, force: Boolean = false) {
        if (model.isBundled) return  // bundled assets are always available; nothing to download
        if (!force && model.isDownloaded(context)) {
            updateState(model, DownloadState.Downloaded(model.localFile(context).absolutePath))
            return
        }

        Log.i(TAG, "Enqueuing download for ${model.displayName}")
        // updateState moved inside coroutine — don't reset progress to 0 if KEEP is chosen

        scope.launch {
            withContext(Dispatchers.IO) {
                val existingInfos = workManager.getWorkInfosForUniqueWork(model.workerTag).get()
                val policy = when {
                    force -> ExistingWorkPolicy.REPLACE
                    existingInfos.any { it.state == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.KEEP
                    else -> ExistingWorkPolicy.REPLACE // unstick stuck ENQUEUED or restart failed
                }

                // Only reset state to Downloading(0) when actually starting fresh
                if (policy == ExistingWorkPolicy.REPLACE) {
                    updateState(model, DownloadState.Downloading())
                }
                Log.i(
                    TAG,
                    "Enqueuing ${model.displayName} with policy=$policy " +
                        "(existingStates=${existingInfos.map { it.state }})"
                )

                // Build and enqueue inside same withContext block to shrink TOCTOU window
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

                val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setInputData(dataBuilder.build())
                    .addTag(model.workerTag)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                workManager.enqueueUniqueWork(model.workerTag, policy, request)
            }
        }

        ensureObserving(model)
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

    /**
     * Re-checks the filesystem for [model] and updates [downloadStates] accordingly.
     * Call this after manually deleting a model file so the UI reflects [DownloadState.NotDownloaded].
     */
    fun refreshState(model: KernelModel) {
        val newState = if (model.isDownloaded(context)) {
            DownloadState.Downloaded(model.localFile(context).absolutePath)
        } else {
            DownloadState.NotDownloaded
        }
        updateState(model, newState)
        Log.i(TAG, "Refreshed state for ${model.displayName}: $newState")
    }

    /** True when all models required for this device tier are present on disk. */
    fun areRequiredModelsDownloaded(): Boolean {
        val tier = hardwareProfileDetector.profile.tier
        // On FLAGSHIP, either E4B or E2B satisfies the conversation model requirement
        val conversationModelReady = when (tier) {
            HardwareTier.FLAGSHIP ->
                KernelModel.GEMMA_4_E4B.isDownloaded(context) ||
                KernelModel.GEMMA_4_E2B.isDownloaded(context)
            else -> KernelModel.GEMMA_4_E2B.isDownloaded(context)
        }
        // All other required models must be present
        val otherRequiredReady = KernelModel.entries
            .filter { it.isRequired && it != KernelModel.GEMMA_4_E2B }
            .all { it.isDownloaded(context) }
        return conversationModelReady && otherRequiredReady
    }

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

    // Issue 3 fix: guard against launching duplicate observeWorkInfo coroutines
    private fun ensureObserving(model: KernelModel) {
        observerJobs.compute(model) { _, existing ->
            if (existing?.isActive == true) existing
            else scope.launch { observeWorkInfo(model) }
        }
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
                            val errorKey = info.outputData.getString(KEY_ERROR)
                            if (errorKey == "LICENCE_REQUIRED") {
                                Log.w(TAG, "Licence required for ${model.displayName}")
                                DownloadState.Error(
                                    message = "Accept the model licence on HuggingFace before downloading.",
                                    licenceRequired = true,
                                )
                            } else {
                                val errorMsg = info.outputData.getString(KEY_ERROR_MESSAGE)
                                    ?: "Download failed"
                                Log.w(TAG, "Download failed for ${model.displayName}: $errorMsg")
                                DownloadState.Error(message = errorMsg)
                            }
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
