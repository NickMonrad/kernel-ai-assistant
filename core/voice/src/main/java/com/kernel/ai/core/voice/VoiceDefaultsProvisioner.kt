package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.isDownloaded
import com.kernel.ai.core.inference.download.isUpdatedInstall
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "VoiceDefaults"

/** Directory the removed Vosk engine unpacked its bundled model into (#1008). */
private const val LEGACY_VOSK_DIR = "voice"

/**
 * Prepares the default voice stack on app start, alongside the required model downloads:
 *
 * - queues the offline Zipformer STT model when the selected input engine needs it
 *   ([VoiceInputEngine.Auto] uses it as the offline fallback and for alert commands);
 * - queues the selected Piper voice (Cori by default) when Piper is the active output engine;
 * - deletes the unpacked model left behind by the removed Vosk engine.
 *
 * Downloads go through the existing managers, so they are no-ops when the files exist and they
 * reuse the managers' network constraints and progress reporting. Each default is queued once
 * only, so a model the user deletes in Settings is not downloaded again. Installs updated from
 * a version before this change, with no stored output engine, keep Android TTS instead of
 * switching to Piper and downloading a voice.
 */
@Singleton
class VoiceDefaultsProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val modelDownloadManager: ModelDownloadManager,
    private val voicePackDownloadManager: SherpaVoicePackDownloadManager,
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun start() {
        scope.launch {
            runCatching { deleteLegacyVoskModel() }
                .onFailure { Log.w(TAG, "Failed to delete legacy Vosk model", it) }
            runCatching { keepAndroidTtsForUpdatedInstalls() }
                .onFailure { Log.w(TAG, "Failed to preserve output engine for updated install", it) }
            // Nothing downloads before the user has chosen a download network in the setup wizard (#1855).
            downloadNetworkPreferences.choice.first { it != null }
            runCatching { queueSpeechInputModel() }
                .onFailure { Log.w(TAG, "Failed to queue speech input model", it) }
            runCatching { queueSpeechOutputVoice() }
                .onFailure { Log.w(TAG, "Failed to queue speech output voice", it) }
        }
    }

    private suspend fun keepAndroidTtsForUpdatedInstalls() {
        if (prefs.getBoolean(KEY_OUTPUT_DEFAULT_RESOLVED, false)) return
        if (context.isUpdatedInstall() && !voiceOutputPreferences.hasStoredEngine()) {
            Log.i(TAG, "Updated install with no stored output engine: keeping Android TTS")
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts)
        }
        prefs.edit().putBoolean(KEY_OUTPUT_DEFAULT_RESOLVED, true).apply()
    }


    private suspend fun queueSpeechInputModel() {
        if (prefs.getBoolean(KEY_ZIPFORMER_QUEUED, false)) return
        val engine = voiceInputPreferences.selectedEngine.first()
        if (!needsZipformer(engine)) return
        val missing = zipformerModels().filterNot { it.isDownloaded(context) }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "Queuing Zipformer STT model for engine=$engine: ${missing.map { it.name }}")
            missing.forEach { modelDownloadManager.startDownload(it, source = DownloadSource.AUTO_QUEUED) }
        }
        prefs.edit().putBoolean(KEY_ZIPFORMER_QUEUED, true).apply()
    }

    private suspend fun queueSpeechOutputVoice() {
        if (prefs.getBoolean(KEY_VOICE_QUEUED, false)) return
        if (voiceOutputPreferences.selectedEngine.first() != VoiceOutputEngine.SherpaExperimental) return
        val voice = voiceOutputPreferences.selectedSherpaVoice.first()
        if (!voice.isDownloaded(context)) {
            Log.i(TAG, "Queuing Piper voice ${voice.name}")
            voicePackDownloadManager.startDownload(voice)
        }
        prefs.edit().putBoolean(KEY_VOICE_QUEUED, true).apply()
    }

    private fun deleteLegacyVoskModel() {
        legacyVoskDirsToDelete(listOfNotNull(context.getExternalFilesDir(null), context.filesDir))
            .forEach { dir ->
                Log.i(TAG, "Deleting legacy Vosk model at ${dir.absolutePath}")
                dir.deleteRecursively()
            }
    }

    companion object {
        private const val PREFS_NAME = "voice_defaults_provisioner"
        private const val KEY_OUTPUT_DEFAULT_RESOLVED = "output_default_resolved_v1"
        private const val KEY_ZIPFORMER_QUEUED = "zipformer_queued_v1"
        private const val KEY_VOICE_QUEUED = "piper_voice_queued_v1"

        /** `<root>/voice` folders that still hold the unpacked Vosk model; nothing else. */
        internal fun legacyVoskDirsToDelete(roots: List<File>): List<File> =
            roots.map { File(it, LEGACY_VOSK_DIR) }
                .filter { it.isDirectory && File(it, "vosk-model").exists() }

        internal fun needsZipformer(engine: VoiceInputEngine): Boolean =
            engine == VoiceInputEngine.Auto || engine == VoiceInputEngine.SherpaZipformer

        internal fun zipformerModels(): List<KernelModel> =
            SherpaSttModelSpec.ZIPFORMER.requiredFileNames.mapNotNull { name ->
                KernelModel.entries.firstOrNull { it.fileName == name }
            }
    }
}
