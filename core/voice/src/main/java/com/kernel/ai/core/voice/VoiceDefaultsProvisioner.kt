package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.isDownloaded
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
 * reuse the managers' network constraints and progress reporting.
 */
@Singleton
class VoiceDefaultsProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val modelDownloadManager: ModelDownloadManager,
    private val voicePackDownloadManager: SherpaVoicePackDownloadManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            runCatching { deleteLegacyVoskModel() }
                .onFailure { Log.w(TAG, "Failed to delete legacy Vosk model", it) }
            runCatching { queueSpeechInputModel() }
                .onFailure { Log.w(TAG, "Failed to queue speech input model", it) }
            runCatching { queueSpeechOutputVoice() }
                .onFailure { Log.w(TAG, "Failed to queue speech output voice", it) }
        }
    }

    private suspend fun queueSpeechInputModel() {
        val engine = voiceInputPreferences.selectedEngine.first()
        if (!needsZipformer(engine)) return
        val missing = zipformerModels().filterNot { it.isDownloaded(context) }
        if (missing.isEmpty()) return
        Log.i(TAG, "Queuing Zipformer STT model for engine=$engine: ${missing.map { it.name }}")
        missing.forEach { modelDownloadManager.startDownload(it, source = DownloadSource.AUTO_QUEUED) }
    }

    private suspend fun queueSpeechOutputVoice() {
        if (voiceOutputPreferences.selectedEngine.first() != VoiceOutputEngine.SherpaExperimental) return
        val voice = voiceOutputPreferences.selectedSherpaVoice.first()
        if (voice.isDownloaded(context)) return
        Log.i(TAG, "Queuing Piper voice ${voice.name}")
        voicePackDownloadManager.startDownload(voice)
    }

    private fun deleteLegacyVoskModel() {
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, LEGACY_VOSK_DIR) }
            .filter { it.isDirectory && File(it, "vosk-model").exists() }
            .forEach { dir ->
                Log.i(TAG, "Deleting legacy Vosk model at ${dir.absolutePath}")
                dir.deleteRecursively()
            }
    }

    companion object {
        internal fun needsZipformer(engine: VoiceInputEngine): Boolean =
            engine == VoiceInputEngine.Auto || engine == VoiceInputEngine.SherpaZipformer

        internal fun zipformerModels(): List<KernelModel> =
            SherpaSttModelSpec.ZIPFORMER.requiredFileNames.mapNotNull { name ->
                KernelModel.entries.firstOrNull { it.fileName == name }
            }
    }
}
