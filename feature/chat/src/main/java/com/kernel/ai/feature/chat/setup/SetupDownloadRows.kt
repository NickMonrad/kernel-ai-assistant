package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ModelRoleCopy
import com.kernel.ai.core.voice.InflectMicroSetup
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceDefaultsProvisioner
import com.kernel.ai.core.voice.VoiceInputEngine

/** What a step 3 row is for; the screen maps each kind to its icon. */
internal enum class DownloadRowKind { Chat, Memory, SpeechInput, Voice, Other }

/** One step 3 summary row: a plain name, the technical name and the bytes still to download. */
internal data class DownloadRowItem(
    val kind: DownloadRowKind,
    val plainName: String,
    val technicalName: String,
    val bytes: Long,
)

private val CHAT_MODELS = setOf(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B)
private val MEMORY_MODELS = setOf(KernelModel.ARCTIC_EMBED_M_V1_5, KernelModel.ARCTIC_EMBED_V1_5_VOCAB)

/**
 * Step 3 rows for the plan (spec 4.3): one row per planned item, grouped so related files read as one thing.
 * [plannedModels] already holds only what is still to download for this phone's tier and the chosen speech input
 * and output (Inflect's graph files only when Inflect is chosen); [plannedVoices] holds the voice packs still to
 * download. Every planned byte lands in exactly one row, so the rows always add up to the step 3 total.
 */
internal fun downloadRows(
    plannedModels: List<KernelModel>,
    plannedVoices: List<SherpaPiperVoice>,
): List<DownloadRowItem> = buildList {
    plannedModels.filter { it in CHAT_MODELS }.forEach { model ->
        add(DownloadRowItem(DownloadRowKind.Chat, "Chat and answers", model.displayName, model.approxSizeBytes))
    }
    group(plannedModels, MEMORY_MODELS) { bytes ->
        DownloadRowItem(DownloadRowKind.Memory, "Memory and search", KernelModel.ARCTIC_EMBED_M_V1_5.displayName, bytes)
    }
    group(plannedModels, VoiceDefaultsProvisioner.zipformerModels().toSet()) { bytes ->
        DownloadRowItem(
            DownloadRowKind.SpeechInput,
            "Offline speech recognition",
            VoiceInputEngine.SherpaZipformer.displayName,
            bytes,
        )
    }
    group(plannedModels, VoiceDefaultsProvisioner.asr4allSetupModels().toSet()) { bytes ->
        DownloadRowItem(
            DownloadRowKind.SpeechInput,
            "Offline speech recognition",
            VoiceInputEngine.Asr4all.displayName,
            bytes,
        )
    }
    plannedVoices.forEach { voice ->
        add(DownloadRowItem(DownloadRowKind.Voice, "${voice.displayName} natural voice", "Piper voice", voice.approxDownloadBytes))
    }
    group(plannedModels, InflectMicroSetup.kernelModels().toSet()) { bytes ->
        DownloadRowItem(DownloadRowKind.Voice, "Inflect Micro", "Inflect Micro v2 voice", bytes)
    }
    val grouped = CHAT_MODELS + MEMORY_MODELS + VoiceDefaultsProvisioner.zipformerModels() +
        VoiceDefaultsProvisioner.asr4allSetupModels() + InflectMicroSetup.kernelModels()
    plannedModels.filterNot { it in grouped }.forEach { model ->
        add(DownloadRowItem(DownloadRowKind.Other, ModelRoleCopy.purpose(model), model.displayName, model.approxSizeBytes))
    }
}

private fun MutableList<DownloadRowItem>.group(
    plannedModels: List<KernelModel>,
    members: Set<KernelModel>,
    row: (Long) -> DownloadRowItem,
) {
    val present = plannedModels.filter { it in members }
    if (present.isNotEmpty()) add(row(present.sumOf { it.approxSizeBytes }))
}
