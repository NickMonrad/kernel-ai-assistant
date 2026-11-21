package com.kernel.ai.core.voice

import android.content.Context
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why the last size switch did not finish. The active size stays selected. */
data class Asr4allSwitchFailure(val size: Asr4AllSize, val message: String)

/** The size being downloaded (null when no switch is pending) and the last switch failure. */
data class Asr4allSwitchState(
    val pendingSize: Asr4AllSize? = null,
    val failure: Asr4allSwitchFailure? = null,
)

/**
 * Owns the asr4all size switch for the whole process. The switch runs on an app-lifetime scope, not
 * on a ViewModel's, so leaving the settings screen does not strand a half-downloaded size.
 *
 * Invariants:
 * - At most one switch body runs at a time ([mutex]). Every switch body ends with a sweep under that
 *   same mutex, so cleanup is never interleaved with another switch.
 * - The pending size is persisted before its download starts and cleared inside the same engine
 *   release that stores the new size. A killed process resumes the pending switch at [start], or drops
 *   it when the stored size already matches.
 * - Only the active size is ever opened by the engine. Every other shipped size's files are removed by
 *   [sweepInactiveLocked] (abandoned, cancelled, failed, superseded, and partial `.tmp` files alike).
 */
@Singleton
class Asr4allSizeSwitcher internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val hardwareProfileDetector: HardwareProfileDetector,
    private val modelDownloadManager: ModelDownloadManager,
    private val controller: Asr4allVoiceInputController,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        voiceInputPreferences: VoiceInputPreferences,
        hardwareProfileDetector: HardwareProfileDetector,
        modelDownloadManager: ModelDownloadManager,
        controller: Asr4allVoiceInputController,
    ) : this(
        context,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        voiceInputPreferences,
        hardwareProfileDetector,
        modelDownloadManager,
        controller,
    )

    private val mutex = Mutex()
    private val jobLock = Any()
    private var switchJob: Job? = null // guarded by jobLock

    private val _state = MutableStateFlow(Asr4allSwitchState())

    /** The pending size and the last failure, for the settings screen. */
    val state: StateFlow<Asr4allSwitchState> = _state.asStateFlow()

    /**
     * Call once at app start. Resumes a switch that was pending when the process ended, or drops it
     * when the stored size already matches it, then keeps non-active sizes swept while no switch runs.
     */
    fun start() {
        scope.launch {
            val pending = voiceInputPreferences.asr4allPendingSize.first()
            if (pending != null) {
                if (pending == activeSize()) {
                    voiceInputPreferences.setAsr4allPendingSize(null)
                } else {
                    synchronized(jobLock) { launchSwitch(pending) }
                }
            }
            combine(voiceInputPreferences.asr4allSize, modelDownloadManager.downloadStates) { _, _ -> Unit }
                .collect {
                    // A queued or running switch owns its partial files; sweep only when none is pending.
                    if (!switchRunning()) mutex.withLock { sweepInactiveLocked() }
                }
        }
    }

    /**
     * Chooses [size]. The active size stays selected while [size] downloads; once every file of [size]
     * is present it is stored as the active size. Choosing the active size abandons any pending switch.
     */
    fun select(size: Asr4AllSize) {
        if (size !in Asr4AllInstall.selectableSizes(hardwareProfileDetector.profile.tier)) return
        synchronized(jobLock) {
            if (size == _state.value.pendingSize) return
            launchSwitch(size)
        }
    }

    /**
     * Abandons a pending switch and clears its failure. A commit that has already begun runs to
     * completion, so the new size becomes active.
     */
    fun cancel() {
        synchronized(jobLock) {
            switchJob?.cancel()
            _state.update { it.copy(failure = null) }
        }
    }

    /**
     * Abandons any pending switch, then deletes the active size's files under an engine release.
     * Returns false, deleting nothing, when the release could not happen. The work runs on the app
     * scope, so it finishes even when the caller is cancelled.
     */
    suspend fun deleteActive(): Boolean = scope.async {
        synchronized(jobLock) { switchJob?.cancel() }
        mutex.withLock {
            val active = activeSize()
            val released = controller.withEngineReleased {
                Asr4AllInstall.deleteFiles(context, active)
                Asr4AllInstall.models(active).forEach { modelDownloadManager.refreshState(it) }
            }
            sweepInactiveLocked()
            released != null
        }
    }.await()

    /** Caller holds [jobLock]. A job queued behind the mutex is cancelled before its body runs. */
    private fun launchSwitch(size: Asr4AllSize) {
        switchJob?.cancel()
        _state.update { it.copy(failure = null) }
        switchJob = scope.launch {
            mutex.withLock { runSwitchLocked(size) }
        }
    }

    private fun switchRunning(): Boolean = synchronized(jobLock) { switchJob?.isActive == true }

    private suspend fun activeSize(): Asr4AllSize = Asr4AllInstall.effectiveSize(
        voiceInputPreferences.asr4allSize.first(),
        hardwareProfileDetector.profile.tier,
    )

    /** Caller holds [mutex]. */
    private suspend fun runSwitchLocked(size: Asr4AllSize) {
        val active = activeSize()
        if (size == active) return
        _state.update { it.copy(pendingSize = size) }
        voiceInputPreferences.setAsr4allPendingSize(size)
        try {
            val ready = when {
                // Nothing installed to keep: store the choice at once.
                !Asr4AllInstall.isDownloaded(context, active) -> true
                Asr4AllInstall.isDownloaded(context, size) -> true
                else -> awaitDownload(size)
            }
            when {
                !ready -> setFailure(size, "Download of ${size.name} did not finish. The current size stays selected.")
                !commit(size) -> setFailure(size, "A recording is still ending. Try ${size.name} again in a moment.")
            }
        } finally {
            withContext(NonCancellable) {
                if (_state.value.pendingSize == size) clearPending()
                sweepInactiveLocked()
            }
        }
    }

    /** Starts [size]'s downloads and waits until they settle. True only when every file is downloaded. */
    private suspend fun awaitDownload(size: Asr4AllSize): Boolean {
        val models = Asr4AllInstall.models(size)
        // An Error left by an earlier attempt is cleared before this attempt starts, so only this
        // attempt's outcome can settle the wait. refreshState sets the state synchronously.
        models.filter { modelDownloadManager.downloadStates.value[it] is DownloadState.Error }
            .forEach { modelDownloadManager.refreshState(it) }
        models.forEach { modelDownloadManager.startDownload(it) }
        val seenDownloading = mutableSetOf<KernelModel>()
        val settled = modelDownloadManager.downloadStates
            .onEach { states ->
                models.forEach { if (states[it] is DownloadState.Downloading) seenDownloading += it }
            }
            .first { states ->
                models.all { states[it] is DownloadState.Downloaded } || models.any { model ->
                    val state = states[model]
                    state is DownloadState.Error ||
                        (model in seenDownloading && state is DownloadState.NotDownloaded)
                }
            }
        return models.all { settled[it] is DownloadState.Downloaded }
    }

    /**
     * Stores [size] as active and clears the pending record, inside an engine release. The release
     * closes the engine before the write and holds the start lock through it, so no start can open
     * the old size after the write. False when the release could not happen; nothing is stored then.
     */
    private suspend fun commit(size: Asr4AllSize): Boolean = withContext(NonCancellable) {
        val released = controller.withEngineReleased {
            voiceInputPreferences.setAsr4allSize(size)
            voiceInputPreferences.setAsr4allPendingSize(null)
        }
        if (released == null) {
            false
        } else {
            _state.update { it.copy(pendingSize = null) }
            true
        }
    }

    private suspend fun clearPending() {
        _state.update { it.copy(pendingSize = null) }
        voiceInputPreferences.setAsr4allPendingSize(null)
    }

    private fun setFailure(size: Asr4AllSize, message: String) {
        _state.update { it.copy(failure = Asr4allSwitchFailure(size, message)) }
    }

    /**
     * Removes every shipped size other than the active one that has a running download or any
     * directory left on disk. Caller holds [mutex].
     */
    private suspend fun sweepInactiveLocked() {
        val active = activeSize()
        val states = modelDownloadManager.downloadStates.value
        for (size in Asr4AllInstall.shippedSizes) {
            if (size == active) continue
            val models = Asr4AllInstall.models(size)
            val downloading = models.filter { states[it] is DownloadState.Downloading }
            if (downloading.isEmpty() && !Asr4AllInstall.hasFiles(context, size)) continue
            downloading.forEach { modelDownloadManager.cancelDownload(it) }
            Asr4AllInstall.deleteFiles(context, size)
            models.forEach { modelDownloadManager.refreshState(it) }
        }
    }
}
