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
import androidx.annotation.VisibleForTesting
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

import kotlinx.coroutines.flow.update

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

    /**
     * Tracks the [DownloadSource] for each model. Populated when [startDownload] is called.
     * Used by the UI layer to determine whether cancel is allowed.
     */
    private val _downloadSources: MutableStateFlow<Map<KernelModel, DownloadSource>> =
        MutableStateFlow(emptyMap())
    val downloadSources: StateFlow<Map<KernelModel, DownloadSource>> = _downloadSources.asStateFlow()

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
                 !(it == KernelModel.GEMMA_4_E2B && tier == HardwareTier.FLAGSHIP)
             }
            .forEach { model ->
                Log.i(TAG, "Auto-queuing required model: ${model.displayName}")
                startDownload(model, source = DownloadSource.AUTO_QUEUED)
            }
        // Auto-queue tier-specific optional models (e.g. E-4B on FLAGSHIP),
        // but only if the user hasn't manually suppressed/deleted them.
        scope.launch {
            val suppressedIds = modelPreferences.suppressedOptionalModelIds.first()
            KernelModel.entries
                .filter {
                    !it.isRequired &&
                    it.preferredForTier == tier &&
                    !it.isDownloaded(context) &&
                    it.name !in suppressedIds &&
                    (!it.isGated || authRepository.getAccessToken() != null)
                }
                .forEach { model ->
                    Log.i(TAG, "Auto-queuing ${model.displayName} for tier ${tier.name}")
                    startDownload(model, source = DownloadSource.AUTO_QUEUED)
                }
        }
        // Auto-trigger gated required models when user signs in
        scope.launch {
            authRepository.isAuthenticated
                .filter { it }
                .collect {
                    KernelModel.entries
                        .filter { m -> m.isGated && m.isRequired }
                        .filter { m -> _downloadStates.value[m] is DownloadState.NotDownloaded }
                        .forEach { m -> startDownload(m, source = DownloadSource.AUTO_QUEUED) }
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
    fun startDownload(model: KernelModel, force: Boolean = false, source: DownloadSource = DownloadSource.USER_INITIATED) {
        if (model.isBundled) return  // bundled assets are always available; nothing to download
        if (!force && model.isDownloaded(context)) {
            updateState(model, DownloadState.Downloaded(model.localFile(context).absolutePath))
            return
        }


        // User-initiated download clears any prior suppression so the model can auto-queue
        // on future app restarts after a fresh download followed by another manual delete.
        if (!model.isRequired && source == DownloadSource.USER_INITIATED) {
            scope.launch { modelPreferences.unsuppressOptionalModel(model) }
        }

        // Track the download source for UI layer
        _downloadSources.update { it.toMutableMap().apply { put(model, source) } }
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
        // Only user-initiated downloads can be cancelled — auto-queued models are needed
        // for the app to function. Check the stored source rather than model.isRequired
        // because some required models may be user-initiated (e.g. E2B on FLAGSHIP).
        val source = _downloadSources.value[model] ?: DownloadSource.USER_INITIATED
        if (source == DownloadSource.AUTO_QUEUED) {
            Log.w(TAG, "Refusing to cancel auto-queued download: ${model.displayName}")
            return
        }
        workManager.cancelUniqueWork(model.workerTag)
        _downloadSources.update { it.toMutableMap().apply { remove(model) } }
        updateState(model, DownloadState.NotDownloaded)
        Log.i(TAG, "Cancelled download for ${model.displayName}")
    }

    /**
     * Returns the absolute path to [model]'s local file if it's downloaded, or null.
     */
    fun getModelPath(model: KernelModel): String? {
        return if (model.isDownloaded(context)) model.localFile(context).absolutePath else null
    }

    fun refreshState(model: KernelModel) {
        val newState = if (model.isDownloaded(context)) {
            DownloadState.Downloaded(model.localFile(context).absolutePath)
        } else {
            DownloadState.NotDownloaded
        }
        // Clear stale source tracking since the model is no longer actively downloading
        if (newState !is DownloadState.Downloading) {
            _downloadSources.update { it.toMutableMap().apply { remove(model) } }
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
        // All other required models must be present.
        // Exclude gated models when the user hasn't authenticated —
        // they are required for RAG/vector search but not for the
        // conversation engine to initialise and run.
        val isHfAuthenticated = authRepository.isAuthenticated.value
        val otherRequiredReady = KernelModel.entries
            .filter { it.isRequired && it != KernelModel.GEMMA_4_E2B }
            .filterNot { it.isGated && !isHfAuthenticated }
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

    /**
     * Permanently removes all WorkManager metadata (including completed records)
     * for this model's worker. Used after manual delete to prevent stale SUCCEEDED
     * records from reasserting downloaded state on app restart.
     *
     * Safe to call on any worker state — completed, cancelled, running, or absent.
     */
    fun pruneCompletedWork(model: KernelModel) {
        workManager.cancelUniqueWork(model.workerTag)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun updateState(model: KernelModel, state: DownloadState) {
        _downloadStates.update { it.toMutableMap().apply { put(model, state) } }
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
                        // Stale WorkManager SUCCEEDED records from a previous session
                        // can persist after the model file was manually deleted. Trust
                        // the filesystem: only emit Downloaded when the file exists and
                        // has content. Prune the stale work when the file is missing.
                        val (localFile, filePresent) = withContext(Dispatchers.IO) {
                            val f = model.localFile(context)
                            f to (f.exists() && f.length() > 0)
                        }
                        if (filePresent) {
                            val path = localFile.absolutePath
                            Log.i(TAG, "Download succeeded: $path")
                            DownloadState.Downloaded(localPath = path)
                        } else {
                            Log.w(TAG, "Stale succeeded worker but file missing — cancelling: ${model.displayName}")
                            workManager.cancelUniqueWork(model.workerTag)
                            DownloadState.NotDownloaded
                        }
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

/**
 * Determines the [DownloadState] for a SUCCEEDED WorkManager worker by
 * checking the filesystem. Returns [DownloadState.Downloaded] if the model
 * file exists and has content, [DownloadState.NotDownloaded] otherwise.
 *
 * Package-visible for testing — prefer using [ModelDownloadManager]'s
 * [observeWorkInfo] which also prunes stale completed workers.
 */
@VisibleForTesting
internal suspend fun succeededWorkerDownloadState(
    model: KernelModel,
    context: Context,
): DownloadState = withContext(Dispatchers.IO) {
    val f = model.localFile(context)
    if (f.exists() && f.length() > 0) {
        DownloadState.Downloaded(localPath = f.absolutePath)
    } else {
        DownloadState.NotDownloaded
    }
}

/** Unique WorkManager work name for this model. */
private val KernelModel.workerTag: String get() = "download_${name.lowercase()}"
