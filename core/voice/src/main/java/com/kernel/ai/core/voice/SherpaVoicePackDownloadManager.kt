package com.kernel.ai.core.voice

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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "KernelAI"

/**
 * Hilt singleton that manages the download lifecycle of all [SherpaPiperVoice] packs.
 *
 * - Persists download state in [WorkManager] so downloads survive process death.
 * - Exposes [downloadStates] as a [StateFlow] for Compose UI observation.
 * - Checks internal storage on startup to detect already-extracted voice packs.
 * - Supports resume: the [VoicePackDownloadWorker] appends to the `.tmp` byte offset.
 * - Calls [SherpaOnnxVoiceOutputController.markVoiceAvailable] after a successful download
 *   so a voice becomes usable without restarting the app or re-selecting the voice.
 */
@Singleton
class SherpaVoicePackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sherpaController: SherpaOnnxVoiceOutputController,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observerJobs = ConcurrentHashMap<SherpaPiperVoice, Job>()

    private val _downloadStates: MutableStateFlow<Map<SherpaPiperVoice, VoicePackDownloadState>> =
        MutableStateFlow(
            SherpaPiperVoice.entries.associateWith { voice ->
                if (voice.isDownloaded(context)) {
                    VoicePackDownloadState.Downloaded(voice.voiceDir(context).absolutePath)
                } else {
                    VoicePackDownloadState.NotDownloaded
                }
            }
        )

    val downloadStates: StateFlow<Map<SherpaPiperVoice, VoicePackDownloadState>> =
        _downloadStates.asStateFlow()

    init {
        // Resume observing any in-progress workers from a previous process lifecycle
        SherpaPiperVoice.entries.forEach { voice -> ensureObserving(voice) }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue a download for [voice]. No-op if already downloaded.
     * Pass [force] = true to re-download.
     */
    fun startDownload(voice: SherpaPiperVoice, force: Boolean = false) {
        if (!force && voice.isDownloaded(context)) {
            updateState(voice, VoicePackDownloadState.Downloaded(voice.voiceDir(context).absolutePath))
            return
        }

        Log.i(TAG, "Enqueuing voice pack download: ${voice.displayName}")

        scope.launch {
            withContext(Dispatchers.IO) {
                val existingInfos = workManager.getWorkInfosForUniqueWork(voice.workerTag).get()
                val policy = when {
                    force -> ExistingWorkPolicy.REPLACE
                    existingInfos.any { it.state == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.KEEP
                    else -> ExistingWorkPolicy.REPLACE
                }

                if (policy == ExistingWorkPolicy.REPLACE) {
                    updateState(voice, VoicePackDownloadState.Downloading())
                }

                val request = OneTimeWorkRequestBuilder<VoicePackDownloadWorker>()
                    .setInputData(
                        Data.Builder()
                            .putString(KEY_VOICE_NAME, voice.name)
                            .putString(KEY_VOICE_DISPLAY_NAME, voice.displayName)
                            .putString(KEY_VOICE_DOWNLOAD_URL, voice.downloadUrl)
                            .putLong(KEY_VOICE_TOTAL_BYTES, voice.approxDownloadBytes)
                            .build()
                    )
                    .addTag(voice.workerTag)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                workManager.enqueueUniqueWork(voice.workerTag, policy, request)
            }
        }

        ensureObserving(voice)
    }

    /** Cancel an in-progress download. The partial `.tmp` file is preserved for resumption. */
    fun cancelDownload(voice: SherpaPiperVoice) {
        workManager.cancelUniqueWork(voice.workerTag)
        updateState(voice, VoicePackDownloadState.NotDownloaded)
        Log.i(TAG, "Cancelled voice pack download: ${voice.displayName}")
    }

    /**
     * Delete the extracted voice pack from internal storage.
     * Safe to call even if no pack is present (no-op).
     */
    fun deleteVoice(voice: SherpaPiperVoice) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val dir = voice.voiceDir(context)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    Log.i(TAG, "Deleted voice pack: ${dir.absolutePath}")
                }
            }
            updateState(voice, VoicePackDownloadState.NotDownloaded)
        }
    }

    /**
     * Re-checks the filesystem for [voice] and updates [downloadStates].
     * Call this after manual installation via ADB to reflect the new state in the UI.
     */
    fun refreshState(voice: SherpaPiperVoice) {
        val newState = if (voice.isDownloaded(context)) {
            VoicePackDownloadState.Downloaded(voice.voiceDir(context).absolutePath)
        } else {
            VoicePackDownloadState.NotDownloaded
        }
        updateState(voice, newState)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun updateState(voice: SherpaPiperVoice, state: VoicePackDownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply { put(voice, state) }
    }

    private fun ensureObserving(voice: SherpaPiperVoice) {
        observerJobs.compute(voice) { _, existing ->
            if (existing?.isActive == true) existing
            else scope.launch { observeWorkInfo(voice) }
        }
    }

    private suspend fun observeWorkInfo(voice: SherpaPiperVoice) {
        workManager
            .getWorkInfosByTagFlow(voice.workerTag)
            .collect { infoList ->
                val info = infoList.firstOrNull() ?: return@collect
                val newState: VoicePackDownloadState = when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        // Guard: don't show Downloading if the pack is already present
                        if (voice.isDownloaded(context)) {
                            workManager.cancelUniqueWork(voice.workerTag)
                            return@collect
                        }
                        val progress = info.progress
                        val totalBytes = voice.approxDownloadBytes
                        val downloadedBytes = progress.getLong(KEY_VOICE_PROGRESS_BYTES, 0L)
                        val bps = progress.getLong(KEY_VOICE_DOWNLOAD_RATE, 0L)
                        val remainingMs = progress.getLong(KEY_VOICE_REMAINING_MS, 0L)
                        val fraction = if (totalBytes > 0) {
                            // Map download progress to 0–0.9 (extraction is 0.9–1.0)
                            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) * 0.9f
                        } else 0f
                        VoicePackDownloadState.Downloading(
                            progress = fraction,
                            downloadedBytes = downloadedBytes,
                            bytesPerSecond = bps,
                            remainingMs = remainingMs,
                        )
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        val dir = withContext(Dispatchers.IO) { voice.voiceDir(context) }
                        Log.i(TAG, "Voice pack download succeeded: ${dir.absolutePath}")
                        // Notify the TTS controller so it can re-init without a voice change
                        sherpaController.markVoiceAvailable(voice)
                        VoicePackDownloadState.Downloaded(dir.absolutePath)
                    }

                    WorkInfo.State.FAILED -> {
                        // Trust filesystem over worker state — pack may have been pushed via ADB
                        val isPresent = withContext(Dispatchers.IO) { voice.isDownloaded(context) }
                        if (isPresent) {
                            Log.i(TAG, "Worker failed but pack present — treating as Downloaded: ${voice.displayName}")
                            sherpaController.markVoiceAvailable(voice)
                            VoicePackDownloadState.Downloaded(voice.voiceDir(context).absolutePath)
                        } else {
                            val msg = info.outputData.getString(KEY_VOICE_ERROR_MESSAGE) ?: "Download failed"
                            Log.w(TAG, "Voice pack download failed for ${voice.displayName}: $msg")
                            VoicePackDownloadState.Error(msg)
                        }
                    }

                    WorkInfo.State.CANCELLED -> {
                        val isPresent = withContext(Dispatchers.IO) { voice.isDownloaded(context) }
                        if (isPresent) {
                            VoicePackDownloadState.Downloaded(voice.voiceDir(context).absolutePath)
                        } else {
                            VoicePackDownloadState.NotDownloaded
                        }
                    }

                    else -> return@collect
                }
                updateState(voice, newState)
            }
    }
}

/** Unique WorkManager work name for this voice. */
private val SherpaPiperVoice.workerTag: String get() = "voice_pack_${name.lowercase()}"

