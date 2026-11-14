package com.kernel.ai.core.inference.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.annotation.VisibleForTesting
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.diagnostics.DownloadStateKind
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.WorkerPhase
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
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

    private val _waitingForWifi: MutableStateFlow<Set<KernelModel>> = MutableStateFlow(emptySet())

    /** Models queued on Wi-Fi only while the phone is on mobile data. Drives "Waiting for Wi-Fi" (#1855). */
    val waitingForWifi: StateFlow<Set<KernelModel>> = _waitingForWifi.asStateFlow()

    val deviceTier: HardwareTier get() = hardwareProfileDetector.profile.tier

    /** Job state per model, used to derive "Waiting for Wi-Fi" (#1855). */
    private val workStates = ConcurrentHashMap<KernelModel, WorkInfo.State>()

    /** Re-evaluates "Waiting for Wi-Fi" when the connection changes; WorkManager does not report it. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scope.launch { refreshWaitingForWifi() }
        }

        override fun onLost(network: Network) {
            scope.launch { refreshWaitingForWifi() }
        }
    }

    init {
        // Resume observing any in-progress workers that survived a process restart
        KernelModel.entries.forEach { model ->
            ensureObserving(model)
        }
        // Nothing is queued until the user has chosen a download network (#1855). The legacy choice for
        // updated installs is settled at the first-run decision (ConversationListViewModel).
        scope.launch {
            queueAutoDownloads()
        }

        // Any change to the download network choice re-queues every unfinished model job with the new
        // constraint, then auto-queues if the choice just became usable (#1855). The first value the collector
        // reads is the launch state and is skipped. Residual: a choice saved before this first read
        // (adoptLegacyChoice) is missed until next launch.
        scope.launch {
            var previous: DownloadNetworkChoice? = null
            var first = true
            downloadNetworkPreferences.choice.distinctUntilChanged().collect { choice ->
                // The first value is the launch state: nothing re-queues, as before.
                if (!first) {
                    if (DownloadConsent.shouldRequeueOnChoiceChange(previous, choice)) requeueUnfinishedForNetworkChange()
                    refreshWaitingForWifi()
                    if (DownloadConsent.autoQueueAllowed(choice)) queueAutoDownloads()
                }
                first = false
                previous = choice
            }
        }
        context.getSystemService(ConnectivityManager::class.java)
            ?.registerDefaultNetworkCallback(networkCallback)
        // Auto-trigger gated required models when user signs in
        scope.launch {
            authRepository.isAuthenticated
                .filter { it }
                .collect {
                    if (!DownloadConsent.autoQueueAllowed(downloadNetworkPreferences.choice.first())) return@collect
                    KernelModel.entries
                        .filter { m -> m.isGated && m.isRequired }
                        .filter { m -> _downloadStates.value[m] is DownloadState.NotDownloaded }
                        .forEach { m -> startDownload(m, source = DownloadSource.AUTO_QUEUED) }
                }
        }
    }

    /**
     * Records the user's download network choice (#1855). The choice observer in init re-queues every
     * unfinished model and voice-pack job with the new constraint, so the change applies to queued work too.
     */
    fun setDownloadNetworkChoice(choice: DownloadNetworkChoice) {
        scope.launch { downloadNetworkPreferences.setChoice(choice) }
    }

    /**
     * True only for an install updated from an earlier build that has local model data (#1855). An update
     * with no models and no `.tmp` file (installed but never used before auto-update) is treated as fresh, so
     * it is never granted mobile data and still sees the wizard.
     */
    fun isExistingInstall(): Boolean =
        DownloadConsent.existingInstallFromEvidence(context.isUpdatedInstall(), hasLocalModelData())

    private fun hasLocalModelData(): Boolean {
        val modelsDir = KernelModel.GEMMA_4_E2B.localFile(context).parentFile
        return KernelModel.entries.any { !it.isBundled && it.isDownloaded(context) } ||
            modelsDir?.listFiles()?.any { it.name.endsWith(".tmp") } == true
    }

    /**
     * Settles the legacy network choice for an updated install at the first-run decision (#1855). Runs only
     * when that decision was not yet recorded, so it happens at most once per install.
     */
    suspend fun adoptLegacyChoice(alreadyDecided: Boolean, existingInstall: Boolean) {
        val legacy = DownloadConsent.legacyChoiceOnFirstDecision(alreadyDecided, existingInstall) ?: return
        if (downloadNetworkPreferences.choice.first() != null) return
        downloadNetworkPreferences.setChoice(legacy)
        Log.i(TAG, "Updated install: keeping the current network behaviour without a prompt")
    }

    /** Unfinished means queued, running or blocked. */
    private suspend fun hasUnfinishedWork(model: KernelModel): Boolean = withContext(Dispatchers.IO) {
        DownloadConsent.hasUnfinishedWork(workManager.getWorkInfosForUniqueWork(model.workerTag).get().map { it.state })
    }

    private suspend fun requeueUnfinishedForNetworkChange() {
        KernelModel.entries
            .filter { !it.isBundled && hasUnfinishedWork(it) }
            .forEach { model ->
                Log.i(TAG, "Network choice changed: re-queuing ${model.displayName}")
                startDownload(
                    model,
                    source = _downloadSources.value[model] ?: DownloadSource.USER_INITIATED,
                    restartRunning = true,
                )
            }
    }

    /** Auto-queues required and tier models. Does nothing until the user has chosen a network. */
    private suspend fun queueAutoDownloads() {
        if (!DownloadConsent.autoQueueAllowed(downloadNetworkPreferences.choice.first())) {
            Log.i(TAG, "Auto-queue skipped: no download network chosen yet")
            return
        }
        autoQueueSet().forEach { model ->
            Log.i(TAG, "Auto-queuing ${model.displayName}")
            startDownload(model, source = DownloadSource.AUTO_QUEUED)
        }
    }

    private suspend fun autoQueueSet(): List<KernelModel> = DownloadConsent.autoQueueModels(
        tier = hardwareProfileDetector.profile.tier,
        isDownloaded = { it.isDownloaded(context) },
        suppressedIds = modelPreferences.suppressedOptionalModelIds.first(),
        hasToken = authRepository.getAccessToken() != null,
    )

    /** Models the first-run plan covers: the auto-queue set plus the speech input defaults (#1855). */
    suspend fun plannedFirstRunModels(): List<KernelModel> =
        autoQueueSet() + DownloadConsent.speechDefaultModels { it.isDownloaded(context) }

    /** Waiting means queued, Wi-Fi only, and the only connection is metered. Driven by work state and network callbacks (#1855). */
    private suspend fun updateWaitingForWifi(model: KernelModel, state: WorkInfo.State) {
        workStates[model] = state
        refreshWaitingForWifi()
    }

    private suspend fun refreshWaitingForWifi() {
        val wifiOnly = DownloadConsent.effectiveForManualDownload(downloadNetworkPreferences.choice.first()) ==
            DownloadNetworkChoice.WIFI_ONLY
        val metered = isOnlineOnMeteredNetwork()
        _waitingForWifi.value = KernelModel.entries
            .filter { workStates[it] == WorkInfo.State.ENQUEUED && wifiOnly && metered }
            .toSet()
    }

    private fun isOnlineOnMeteredNetwork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
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
    fun startDownload(
        model: KernelModel,
        force: Boolean = false,
        source: DownloadSource = DownloadSource.USER_INITIATED,
        restartRunning: Boolean = false,
    ) {
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
                    force || restartRunning -> ExistingWorkPolicy.REPLACE
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
                val network = DownloadConsent.effectiveForManualDownload(downloadNetworkPreferences.choice.first())
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
                            .setRequiredNetworkType(network.requiredNetworkType)
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
    fun areRequiredModelsDownloaded(): Boolean =
        ConversationModelRules.appRequiredReady(hardwareProfileDetector.profile.tier) { it.isDownloaded(context) }

    /**
     * Returns the conversation model that answers now. The user's pick wins when on disk;
     * otherwise the tier default, otherwise E-2B. Logs a warning when the pick is missing.
     */
    suspend fun preferredConversationModel(): KernelModel {
        val userPref = modelPreferences.preferredConversationModel.first()
        if (userPref != null && !userPref.isDownloaded(context)) {
            Log.w(TAG, "User-preferred model ${userPref.displayName} not downloaded — falling back to auto")
        }
        return ConversationModelRules.effectiveConversationModel(
            userPreference = userPref,
            tier = hardwareProfileDetector.profile.tier,
        ) { it.isDownloaded(context) }
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
        val previous = _downloadStates.value[model]
        _downloadStates.update { it.toMutableMap().apply { put(model, state) } }
        // Transitions only: progress ticks within one state are not recorded.
        if (previous?.javaClass != state.javaClass) {
            JandalEvents.record("download", "state", "model" to model, "state" to state.toJandalStateKind())
        }
    }

    /** Enum values only: `javaClass.simpleName` is obfuscated in release builds, and strings are redacted (#1870). */
    private fun DownloadState.toJandalStateKind(): DownloadStateKind = when (this) {
        DownloadState.NotDownloaded -> DownloadStateKind.NOT_DOWNLOADED
        is DownloadState.Downloaded -> DownloadStateKind.DOWNLOADED
        is DownloadState.Error -> DownloadStateKind.ERROR
        else -> DownloadStateKind.DOWNLOADING
    }

    private fun WorkInfo.State.toWorkerPhase(): WorkerPhase = when (this) {
        WorkInfo.State.ENQUEUED -> WorkerPhase.ENQUEUED
        WorkInfo.State.RUNNING -> WorkerPhase.RUNNING
        WorkInfo.State.SUCCEEDED -> WorkerPhase.SUCCEEDED
        WorkInfo.State.FAILED -> WorkerPhase.FAILED
        WorkInfo.State.BLOCKED -> WorkerPhase.BLOCKED
        WorkInfo.State.CANCELLED -> WorkerPhase.CANCELLED
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
        var lastWorkerState: WorkInfo.State? = null
        workManager
            .getWorkInfosByTagFlow(model.workerTag)
            .collect { infoList ->
                val info = infoList.firstOrNull() ?: return@collect
                if (info.state != lastWorkerState) {
                    // WorkManager replays the last finished run on the first emission of every app
                    // start. That is not a transition this session observed, so it is not exported (#1870).
                    val replayedFinished = lastWorkerState == null && info.state.isFinished
                    lastWorkerState = info.state
                    if (!replayedFinished) {
                        JandalEvents.record("download", "worker_phase", "model" to model, "phase" to info.state.toWorkerPhase())
                    }
                }

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
                updateWaitingForWifi(model, info.state)
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
