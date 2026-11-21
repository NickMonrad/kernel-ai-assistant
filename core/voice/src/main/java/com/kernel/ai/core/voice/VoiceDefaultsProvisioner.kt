package com.kernel.ai.core.voice

import android.content.Context
import android.util.Log
import com.kernel.ai.core.inference.download.DownloadConsent
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.isDownloaded
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
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
 * - queues the speech input file the selected engine needs: asr4all for Auto and asr4all streaming (the size the tier
 *   policy picks);
 * - queues the selected Sherpa voice (Cori by default) when the effective engine is Sherpa Piper.
 *   That is the tier default on fresh installs, so it starts after the wizard's network choice.
 *   A stored Android TTS choice never queues it. Choosing Piper later goes through [PiperVoiceSetup];
 * - deletes the unpacked model left behind by the removed Vosk engine.
 *
 * Downloads go through the existing managers, so they are no-ops when the files exist and they
 * reuse the managers' network constraints and progress reporting. Each default is queued once
 * only, so a model the user deletes in Settings is not downloaded again.
 */
@Singleton
class VoiceDefaultsProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val modelDownloadManager: ModelDownloadManager,
    private val voicePackDownloadManager: SherpaVoicePackDownloadManager,
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
    private val hardwareProfileDetector: HardwareProfileDetector,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun start() {
        scope.launch {
            // Read before the consent wait: the wizard does not change whether this is an existing install (#1868).
            val isExistingInstall = modelDownloadManager.isExistingInstall()
            runCatching { deleteLegacyVoskModel() }
                .onFailure { Log.w(TAG, "Failed to delete legacy Vosk model", it) }
            // Nothing downloads before the user has chosen a download network in the setup wizard (#1855).
            downloadNetworkPreferences.choice.first { DownloadConsent.voiceDefaultsAllowed(it) }
            runCatching { queueSpeechInputModel() }
                .onFailure { Log.w(TAG, "Failed to queue speech input model", it) }
            runCatching { queueSpeechOutputVoice(isExistingInstall) }
                .onFailure { Log.w(TAG, "Failed to queue speech output voice", it) }
        }
    }


    private suspend fun queueSpeechInputModel() {
        val engine = voiceInputPreferences.selectedEngine.first()
        // Marked only once queued, so a launch while the Terms gate (#1998) is closed retries later.
        if (!prefs.getBoolean(KEY_ASR4ALL_QUEUED, false) && queueAsr4all(engine)) {
            prefs.edit().putBoolean(KEY_ASR4ALL_QUEUED, true).apply()
        }
    }

    /**
     * Queues the asr4all files [engine] needs that are not on disk yet: asr4all for Auto and asr4all streaming.
     * Used at launch and by the setup wizard's Finish, so the
     * size line and the queue agree, including a stored Automatic whose files were deleted.
     */
    suspend fun queueSpeechInput(engine: VoiceInputEngine) {
        queueAsr4all(engine)
    }

    /**
     * Queues the asr4all files of the size the user's choice resolves to on this tier. Returns true when the engine
     * needs asr4all and it is queued or already on disk; false when it does not need it or the Terms hook is closed.
     */
    private suspend fun queueAsr4all(engine: VoiceInputEngine): Boolean {
        if (!needsAsr4all(engine) || !Asr4AllInstall.termsAcceptanceGranted()) return false
        val size = Asr4AllInstall.effectiveSize(
            voiceInputPreferences.asr4allSize.first(),
            hardwareProfileDetector.profile.tier,
        )
        val missing = Asr4AllInstall.models(size).filterNot { it.isDownloaded(context) }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "Queuing asr4all ${size.name} model for engine=$engine: ${missing.map { it.name }}")
            missing.forEach { modelDownloadManager.startDownload(it, source = DownloadSource.AUTO_QUEUED) }
        }
        return true
    }

    private suspend fun queueSpeechOutputVoice(isExistingInstall: Boolean) {
        if (prefs.getBoolean(KEY_VOICE_QUEUED, false)) return
        val voice = voiceOutputPreferences.selectedSherpaVoice.first()
        // Read after the consent wait: an engine the user picked in Settings during the wait must not be overwritten.
        val plan = planSpeechOutputVoice(
            hasStoredEngine = voiceOutputPreferences.hasStoredEngine(),
            isExistingInstall = isExistingInstall,
            effectiveEngine = voiceOutputPreferences.selectedEngine.first(),
            voiceDownloaded = voice.isDownloaded(context),
        )
        if (plan.keepAndroidTts) {
            // An updated install keeps the engine it had. The default would otherwise switch it to Piper silently.
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.AndroidTts)
        }
        if (plan.startDownload) {
            Log.i(TAG, "Queuing Piper voice ${voice.name}")
            voicePackDownloadManager.startDownload(voice)
        }
        // Marked on every decided path, so a voice the user cancels or deletes is not queued again.
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
        /** What the first run does with the speech output engine and voice. */
        internal data class SpeechOutputVoicePlan(val keepAndroidTts: Boolean, val startDownload: Boolean)

        /**
         * - Updated install without a stored engine: keep Android TTS, so the new default does not switch to Piper.
         * - Otherwise: start the selected voice when Piper is effective and the voice is missing.
         */
        internal fun planSpeechOutputVoice(
            hasStoredEngine: Boolean,
            isExistingInstall: Boolean,
            effectiveEngine: VoiceOutputEngine,
            voiceDownloaded: Boolean,
        ): SpeechOutputVoicePlan =
            if (!hasStoredEngine && isExistingInstall) {
                SpeechOutputVoicePlan(keepAndroidTts = true, startDownload = false)
            } else {
                SpeechOutputVoicePlan(
                    keepAndroidTts = false,
                    startDownload = effectiveEngine == VoiceOutputEngine.SherpaExperimental && !voiceDownloaded,
                )
            }

        private const val PREFS_NAME = "voice_defaults_provisioner"
        private const val KEY_VOICE_QUEUED = "piper_voice_queued_v1"
        private const val KEY_ASR4ALL_QUEUED = "asr4all_queued_v1"

        /** `<root>/voice` folders that still hold the unpacked Vosk model; nothing else. */
        internal fun legacyVoskDirsToDelete(roots: List<File>): List<File> =
            roots.map { File(it, LEGACY_VOSK_DIR) }
                .filter { it.isDirectory && File(it, "vosk-model").exists() }

        /** Auto and asr4all streaming use the asr4all model. */
        fun needsAsr4all(engine: VoiceInputEngine): Boolean =
            engine == VoiceInputEngine.Auto || engine == VoiceInputEngine.Asr4all

        /** Every asr4all file of every shipped size: membership for the setup plan and its rows. The plan itself counts one size. */
        fun asr4allFilesOfAnySize(): List<KernelModel> = Asr4AllInstall.shippedSizes.flatMap(Asr4AllInstall::models)
    }
}
