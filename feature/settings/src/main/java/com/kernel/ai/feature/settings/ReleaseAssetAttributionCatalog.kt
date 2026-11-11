package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaSttModelSpec

data class ReleaseAssetAttribution(
    val id: String,
    val name: String,
    val sourceDisplay: String?,
    val licenceDisplay: String?,
    val lineageDisplay: String?,
    val releaseStatus: String,
) {
    val sourceText: String get() = sourceDisplay ?: ReleaseAssetAttributionCatalog.UNDER_REVIEW
    val licenceText: String get() = licenceDisplay ?: ReleaseAssetAttributionCatalog.UNDER_REVIEW
    val lineageText: String get() = lineageDisplay ?: ReleaseAssetAttributionCatalog.UNDER_REVIEW
}

/** Offline attribution rows sourced from the same catalogues used by model and voice downloads. */
object ReleaseAssetAttributionCatalog {
    const val UNDER_REVIEW = "Under review"

    private val modelRows = KernelModel.entries.map { model ->
        val details = when (model) {
            KernelModel.GEMMA_4_E2B, KernelModel.GEMMA_4_E4B -> Triple(
                "Hugging Face · " + model.downloadUrl.removePrefix("https://huggingface.co/").substringBefore("/resolve/"),
                "Apache-2.0 (recorded upstream repository metadata)",
                "Gemma 4 instruction-tuned model packaged as LiteRT-LM; exact source revision and conversion details under review",
            )
            KernelModel.ARCTIC_EMBED_M_V1_5, KernelModel.ARCTIC_EMBED_V1_5_VOCAB -> Triple(
                "Snowflake/snowflake-arctic-embed-m-v1.5; packaged by Jandal",
                "Apache-2.0",
                "Arctic Embed M v1.5; source revision e58a8f756156a1293d763f17e3aae643474e9b8a",
            )
            KernelModel.MINI_LM -> Triple(
                "sentence-transformers/all-MiniLM-L6-v2; bundled by Jandal",
                "Apache-2.0 (upstream model); converted-file licence review pending",
                "Quantized TFLite intent classifier derived from MiniLM-L6-v2; exact conversion revision under review",
            )
            KernelModel.SHERPA_STT_ENCODER, KernelModel.SHERPA_STT_DECODER,
            KernelModel.SHERPA_STT_JOINER, KernelModel.SHERPA_STT_TOKENS -> Triple(
                "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21",
                "Apache-2.0 recorded for export; model provenance review pending",
                "Zipformer English streaming STT ONNX export and token vocabulary",
            )
            KernelModel.SHERPA_SENSEVOICE_MODEL, KernelModel.SHERPA_SENSEVOICE_TOKENS -> Triple(
                "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                "FunASR model-weight terms; legal review pending (not MIT-only)",
                "ONNX export of FunAudioLLM/SenseVoice; FunASR code and model-weight terms are distinct",
            )
            KernelModel.SHERPA_WHISPER_TINY_EN_ENCODER, KernelModel.SHERPA_WHISPER_TINY_EN_DECODER,
            KernelModel.SHERPA_WHISPER_TINY_EN_TOKENS -> Triple(
                "csukuangfj/sherpa-onnx-whisper-tiny.en",
                "Apache-2.0 recorded for openai/whisper-tiny.en; export terms under review",
                "ONNX export lineage: openai/whisper-tiny.en (English-only)",
            )
            KernelModel.SHERPA_PARAFORMER_ENCODER, KernelModel.SHERPA_PARAFORMER_DECODER,
            KernelModel.SHERPA_PARAFORMER_TOKENS -> Triple(
                "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
                "Apache-2.0 on export page; upstream model-weight terms require review",
                "ONNX export of DAMO speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-online; FunASR MODEL_LICENSE applies where relevant",
            )
            KernelModel.INFLECT_MICRO_DURATION, KernelModel.INFLECT_MICRO_DECODE -> Triple(
                "owensong/Inflect-Micro-v2-ONNX",
                "Apache-2.0 (upstream model repository)",
                "Inflect Micro v2 ONNX duration/decode graph; exact artifact revision under review",
            )
        }
        ReleaseAssetAttribution(
            id = "model:${model.name}",
            name = model.displayName,
            sourceDisplay = details.first,
            licenceDisplay = details.second,
            lineageDisplay = details.third,
            releaseStatus = if (model.isBundled) "Bundled in release" else "Download on demand",
        )
    }

    private val sttRows = SherpaSttModelSpec.ALL.values.flatMap { spec ->
        val model = spec.engine
        spec.requiredFileNames.map { fileName ->
            val kernel = KernelModel.entries.firstOrNull { it.fileName == fileName }
            val row = kernel?.let { modelRows.firstOrNull { entry -> entry.id == "model:${it.name}" } }
            ReleaseAssetAttribution(
                id = "stt:$fileName",
                name = "${spec.displayLabel} · $fileName",
                sourceDisplay = row?.sourceDisplay,
                licenceDisplay = row?.licenceDisplay,
                lineageDisplay = row?.lineageDisplay,
                releaseStatus = "Download on demand · ${model.displayName}",
            )
        }
    }

    private val piperRows = SherpaPiperVoice.entries.map { voice ->
        val releaseVoice = voice.releaseVisible
        ReleaseAssetAttribution(
            id = "piper:${voice.name}",
            name = "${voice.displayName} Piper voice pack",
            sourceDisplay = voice.downloadUrl,
            licenceDisplay = if (voice == SherpaPiperVoice.CoriHigh) {
                "Piper repository MIT metadata; individual voice/data distribution terms under review"
            } else null,
            lineageDisplay = if (voice == SherpaPiperVoice.CoriHigh) {
                "rhasspy/piper-voices at 5041a7a49ae5b3a0f30e6837769eb03fdbb231bd; model card identifies LibriVox dataset"
            } else null,
            releaseStatus = if (releaseVoice) "Release v0.1 · download on demand" else "Debug/internal only · excluded from release voice catalogue",
        )
    }

    private val kokoroRows = SherpaKokoroVoice.entries.map { voice ->
        ReleaseAssetAttribution(
            id = "kokoro:${voice.name}",
            name = "${voice.displayName} Kokoro voice pack",
            sourceDisplay = voice.downloadUrl,
            licenceDisplay = "Apache-2.0 recorded for Kokoro upstream; Sherpa pack terms under review",
            lineageDisplay = "Sherpa-ONNX ${voice.assetDirectoryName}; upstream hexgrad/Kokoro-82M and nvidia/kokoro-82M-onnx-opt",
            releaseStatus = "Debug-only/post-launch · not exposed in v0.1 release",
        )
    }

    private val engineRows = listOf(
        ReleaseAssetAttribution("stt:Vosk", "Vosk STT runtime and bundled model", "Vosk Android package and app/src/main/assets/vosk-model", "Runtime Apache-2.0; exact bundled model terms under review", "Offline Vosk model; exact model revision/provenance under review", "Bundled in release"),
        ReleaseAssetAttribution("stt:AndroidNative", "Android native speech recognizer", "Android platform speech recognition service", "Platform terms; device/provider-specific", "Runs on the device platform; offline availability varies", "Release-exposed platform service"),
        ReleaseAssetAttribution("tts:AndroidTts", "Android text-to-speech", "Android platform text-to-speech service", "Platform terms; installed engine-specific", "Runs on the device platform", "Release fallback"),
        ReleaseAssetAttribution("tts:SherpaExperimental", "Sherpa-ONNX Piper runtime", "Jandal's pinned Inflect-enabled Sherpa-ONNX AAR and Sherpa-ONNX project", null, "Sherpa-ONNX JNI runtime; exact native build source/notice mapping tracked by #1474", "Bundled runtime · CoriHigh pack downloaded on demand"),
        ReleaseAssetAttribution("tts:InflectMicroExperimental", "Inflect Micro v2 TTS", "owensong/Inflect-Micro-v2-ONNX", "Apache-2.0 (upstream repository); exact release artifact terms under review", "Two ONNX graphs run with the Inflect-enabled Sherpa-ONNX JNI extension", "Release-visible on eligible arm64 flagship devices · model download on demand"),
        ReleaseAssetAttribution("tts:KokoroExperimental", "Kokoro-82M TTS", "Sherpa-ONNX Kokoro voice-pack release", "Apache-2.0 recorded upstream; pack terms under review", "hexgrad/Kokoro-82M; nvidia/kokoro-82M-onnx-opt", "Debug-only/post-launch · excluded from v0.1"),
        ReleaseAssetAttribution("wakeword:openwakeword-melspectrogram", "openWakeWord melspectrogram stage", "app/src/main/assets/models/wakeword/melspectrogram.onnx", "Apache-2.0 (openWakeWord project)", "openWakeWord Stage 1 backbone; exact source revision under review", "Bundled in release"),
        ReleaseAssetAttribution("wakeword:openwakeword-embedding", "openWakeWord embedding stage", "app/src/main/assets/models/wakeword/embedding_model.onnx", "Apache-2.0 (openWakeWord project)", "openWakeWord Stage 2 backbone; exact source revision under review", "Bundled in release"),
        ReleaseAssetAttribution("wakeword:hey-jandal", "Hey Jandal wake-word classifier", "app/src/main/assets/models/wakeword/hey_jandal.onnx", "Under review; maintainer confirms recording consent for this use", "Jandal-specific Stage 3 trained from 954 clips; 174 real-voice clips (including consented maintainer and spouse recordings) and 780 Piper-generated clips; see training/wakeword/README.md", "Bundled in release"),
        ReleaseAssetAttribution("asset:vocab", "Bundled tokenizer vocabulary", "app/src/main/assets/vocab.txt", null, null, "Bundled in release · exact tokenizer/model linkage under review"),
        ReleaseAssetAttribution("asset:intent-vectors", "Bundled MiniLM intent phrase vectors", "app/src/main/assets/intent_phrase_vectors.bin and intent_phrases.json", "Under review", "Generated from MiniLM intent embeddings; exact source corpus/conversion revision under review", "Bundled in release"),
    )

    val entries: List<ReleaseAssetAttribution> = modelRows + sttRows + piperRows + kokoroRows + engineRows
}
