package com.kernel.ai.core.voice

import android.content.Context
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import kotlinx.coroutines.flow.first

/**
 * Why Hey Jandal cannot listen on this phone right now. The setup wizard's Make Jandal yours row uses all four
 * checks. Settings uses the microphone and Usage Access checks only: it enables Hey Jandal first and queues the
 * wake-word model afterwards ([queueHeyJandalSttModel]), so requiring the model there would block the download.
 */
enum class HeyJandalBlock { NoMicrophone, MicrophoneNotGranted, ModelNotInstalled, UsageAccessNeeded }

object HeyJandalGate {
    /**
     * First missing prerequisite, in the same order Settings checks them: microphone, wake-word
     * model, then Honor Usage Access (#1502). The default assistant is recommended, not required,
     * so it is deliberately not a blocker.
     */
    fun block(
        hasMicrophone: Boolean,
        micGranted: Boolean,
        modelAvailable: Boolean,
        usageAccessRequired: Boolean,
    ): HeyJandalBlock? = when {
        !hasMicrophone -> HeyJandalBlock.NoMicrophone
        !micGranted -> HeyJandalBlock.MicrophoneNotGranted
        !modelAvailable -> HeyJandalBlock.ModelNotInstalled
        usageAccessRequired -> HeyJandalBlock.UsageAccessNeeded
        else -> null
    }

    /** True on the affected Honor device when Usage Access has not been granted (#1502). */
    fun usageAccessRequired(context: Context): Boolean =
        HonorCameraCoexistence.isAffectedDevice(context) && !HonorCameraCoexistence.hasUsageAccess(context)
}

/** Models a wake-word STT engine spec needs, as known to the download manager. */
fun SherpaSttModelSpec.kernelModels(): List<KernelModel> =
    requiredFileNames.mapNotNull { fileName -> KernelModel.entries.firstOrNull { it.fileName == fileName } }

/**
 * Queues the STT model Hey Jandal verifies with: the selected online recognizer if it has one,
 * otherwise Zipformer. Call when Hey Jandal is turned on.
 */
suspend fun queueHeyJandalSttModel(
    voiceInputPreferences: VoiceInputPreferences,
    modelDownloadManager: ModelDownloadManager,
) {
    val selected = voiceInputPreferences.selectedEngine.first()
    val engine = if (SherpaSttModelSpec.forEngine(selected)?.recognizerKind == SherpaSttModelSpec.RecognizerKind.Online) {
        selected
    } else {
        VoiceInputEngine.SherpaZipformer
    }
    val spec = SherpaSttModelSpec.forEngine(engine) ?: return
    spec.kernelModels().forEach { modelDownloadManager.startDownload(it) }
}
