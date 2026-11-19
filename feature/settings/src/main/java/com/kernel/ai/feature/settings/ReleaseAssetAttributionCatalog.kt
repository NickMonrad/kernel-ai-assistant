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
    private const val EXCLUDED = "Debug/internal only · excluded from v0.1"

    private val modelRows = KernelModel.entries.map { model ->
        val details = when (model) {
            KernelModel.GEMMA_4_E2B, KernelModel.GEMMA_4_E4B -> Triple(
                "Hugging Face · " + model.downloadUrl.removePrefix("https://huggingface.co/").substringBefore("/resolve/"),
                "Apache-2.0 (recorded upstream repository metadata)",
                "Gemma 4 instruction-tuned model packaged as LiteRT-LM and downloaded from the named upstream repository",
            )
            KernelModel.ARCTIC_EMBED_M_V1_5, KernelModel.ARCTIC_EMBED_V1_5_VOCAB -> Triple(
                "Snowflake/snowflake-arctic-embed-m-v1.5; packaged by Jandal",
                "Apache-2.0",
                "Arctic Embed M v1.5; source revision e58a8f756156a1293d763f17e3aae643474e9b8a",
            )
            KernelModel.MINI_LM -> Triple(
                "Nihal2000/all-MiniLM-L6-v2-quant.tflite · commit ad6c27c81b2c156925c1fb038d533f52f94ccd77",
                "Apache-2.0",
                "Pre-converted INT8 TFLite derivative of sentence-transformers/all-MiniLM-L6-v2; bundled SHA-256 0aac5b0b76be23ab94f065a7fab6e0daead5e57f6ff7d55e19a2641d6a81f276",
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
            releaseStatus = when {
                model.name.startsWith("SHERPA_") || model.name.startsWith("INFLECT_MICRO_") ->
                    EXCLUDED
                model.isBundled -> "Bundled in release"
                else -> "Download on demand"
            },
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
                releaseStatus = EXCLUDED,
            )
        }
    }

    private val piperRows = SherpaPiperVoice.entries.map { voice ->
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
            releaseStatus = EXCLUDED,
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
        ReleaseAssetAttribution("stt:AndroidNative", "Android native speech recognizer", "Android platform speech recognition service", "Platform terms; device/provider-specific", "Runs on the device platform; offline availability varies", "Release-exposed platform service"),
        ReleaseAssetAttribution("tts:AndroidTts", "Android text-to-speech", "Android platform text-to-speech service", "Platform terms; installed engine-specific", "Runs on the device platform", "Release fallback"),
        ReleaseAssetAttribution("tts:SherpaExperimental", "Sherpa-ONNX Piper runtime", "Jandal's pinned Inflect-enabled Sherpa-ONNX AAR and Sherpa-ONNX project", null, "Sherpa-ONNX JNI runtime; exact native build source/notice mapping tracked by #1474", EXCLUDED),
        ReleaseAssetAttribution("tts:InflectMicroExperimental", "Inflect Micro v2 TTS", "owensong/Inflect-Micro-v2-ONNX", "Apache-2.0 (upstream repository)", "Two ONNX graphs require the Inflect-enabled Sherpa-ONNX JNI extension", EXCLUDED),
        ReleaseAssetAttribution("tts:KokoroExperimental", "Kokoro-82M TTS", "Sherpa-ONNX Kokoro voice-pack release", "Apache-2.0 recorded upstream; pack terms under review", "hexgrad/Kokoro-82M; nvidia/kokoro-82M-onnx-opt", "Debug-only/post-launch · excluded from v0.1"),
        ReleaseAssetAttribution("wakeword:openwakeword-melspectrogram", "openWakeWord melspectrogram stage", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx", "Unresolved for this artifact: upstream distinguishes Apache-2.0 code from CC BY-NC-SA 4.0 pretrained models", "Pinned bundled SHA-256 ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f", "Bundled in candidate · release blocked pending licence review"),
        ReleaseAssetAttribution("wakeword:openwakeword-embedding", "openWakeWord embedding stage", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx", "Unresolved for this export: Google speech_embedding is Apache-2.0; openWakeWord declares CC BY-NC-SA 4.0 for its included pretrained models", "Pinned bundled SHA-256 70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f", "Bundled in candidate · release blocked pending licence review"),
        ReleaseAssetAttribution("wakeword:hey-jandal", "Hey Jandal wake-word classifier", "Jandal training/wakeword pipeline · app/src/main/assets/models/wakeword/hey_jandal.onnx", "Apache-2.0 (Jandal project)", "Jandal-specific Stage 3 classifier, SHA-256 11bcdb0d800b3a93449197122bd9fb484c4b8db887364c629f6c975e3e38c206; training used consented maintainer/spouse recordings plus Piper-generated synthetic clips as recorded in training/wakeword/README.md", "Bundled in release"),
        ReleaseAssetAttribution("asset:vocab", "Bundled tokenizer vocabulary", "Snowflake/snowflake-arctic-embed-m-v1.5 · revision e58a8f756156a1293d763f17e3aae643474e9b8a", "Apache-2.0", "WordPiece vocabulary, SHA-256 07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3", "Bundled in release"),
        ReleaseAssetAttribution("asset:intent-vectors", "Bundled MiniLM intent phrase vectors", "Jandal-generated from the bundled MiniLM model, vocab.txt and intent_phrases.json", "Apache-2.0 (Jandal project)", "Hash-bound generated vector asset; generation and freshness checks are documented in docs/minilm-intent-phrase-vectors.md", "Bundled in release"),
    )

    val entries: List<ReleaseAssetAttribution> = modelRows + sttRows + piperRows + kokoroRows + engineRows
}
