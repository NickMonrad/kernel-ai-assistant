package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaSttModelSpec

data class ReleaseAssetAttribution(
    val id: String,
    val name: String,
    val purposeCategory: String,
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
    private const val STT_DEBUG_ONLY = "Debug-only · Sherpa STT input excluded from v0.1"
    private const val WHISPER_VERIFIER = "Downloaded with Hey Jandal · release-visible"

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
            KernelModel.ASR4ALL_M_CONFIG, KernelModel.ASR4ALL_M_RELEASE_GEOM,
            KernelModel.ASR4ALL_M_PREPROCESSOR, KernelModel.ASR4ALL_M_METADATA,
            KernelModel.ASR4ALL_M_ENCODER, KernelModel.ASR4ALL_L_CONFIG,
            KernelModel.ASR4ALL_L_RELEASE_GEOM, KernelModel.ASR4ALL_L_PREPROCESSOR,
            KernelModel.ASR4ALL_L_METADATA, KernelModel.ASR4ALL_L_ENCODER -> Triple(
                "Hugging Face · asr4all-m and asr4all-l export repositories (revisions pinned in NOTICE)",
                "FUTO Model Weights License (Restricted Use) 1.0",
                "Unmodified ExecuTorch INT8 (XNNPACK) export of the asr4all speech-recognition model; M and L sizes",
            )
            KernelModel.INFLECT_MICRO_DURATION, KernelModel.INFLECT_MICRO_DECODE -> Triple(
                "owensong/Inflect-Micro-v2-ONNX",
                "Apache-2.0 (upstream model repository)",
                "Inflect Micro v2 ONNX duration/decode graph; exact artifact revision under review",
            )
        }
        val purposeCategory = when (model) {
            KernelModel.GEMMA_4_E2B, KernelModel.GEMMA_4_E4B -> "Conversation model"
            KernelModel.ARCTIC_EMBED_M_V1_5 -> "Memory search embedding model"
            KernelModel.ARCTIC_EMBED_V1_5_VOCAB -> "Memory search tokenizer"
            KernelModel.MINI_LM -> "Intent classification model"
            KernelModel.SHERPA_STT_ENCODER, KernelModel.SHERPA_STT_DECODER,
            KernelModel.SHERPA_STT_JOINER, KernelModel.SHERPA_STT_TOKENS,
            KernelModel.SHERPA_SENSEVOICE_MODEL, KernelModel.SHERPA_SENSEVOICE_TOKENS,
            KernelModel.SHERPA_WHISPER_TINY_EN_ENCODER, KernelModel.SHERPA_WHISPER_TINY_EN_DECODER,
            KernelModel.SHERPA_WHISPER_TINY_EN_TOKENS,
            KernelModel.SHERPA_PARAFORMER_ENCODER, KernelModel.SHERPA_PARAFORMER_DECODER,
            KernelModel.SHERPA_PARAFORMER_TOKENS -> "Speech recognition (STT) model"
            KernelModel.ASR4ALL_M_CONFIG, KernelModel.ASR4ALL_M_RELEASE_GEOM,
            KernelModel.ASR4ALL_M_PREPROCESSOR, KernelModel.ASR4ALL_M_METADATA,
            KernelModel.ASR4ALL_M_ENCODER, KernelModel.ASR4ALL_L_CONFIG,
            KernelModel.ASR4ALL_L_RELEASE_GEOM, KernelModel.ASR4ALL_L_PREPROCESSOR,
            KernelModel.ASR4ALL_L_METADATA, KernelModel.ASR4ALL_L_ENCODER -> "Speech recognition (STT) model"
            KernelModel.INFLECT_MICRO_DURATION, KernelModel.INFLECT_MICRO_DECODE ->
                "Speech synthesis (TTS) model"
        }
        ReleaseAssetAttribution(
            id = "model:${model.name}",
            name = model.displayName,
            purposeCategory = purposeCategory,
            sourceDisplay = details.first,
            licenceDisplay = details.second,
            lineageDisplay = details.third,
            releaseStatus = when {
                model.name.startsWith("SHERPA_WHISPER_") ->
                    WHISPER_VERIFIER
                model.name.startsWith("SHERPA_") ->
                    STT_DEBUG_ONLY
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
                purposeCategory = row?.purposeCategory ?: "Speech recognition (STT) model",
                sourceDisplay = row?.sourceDisplay,
                licenceDisplay = row?.licenceDisplay,
                lineageDisplay = row?.lineageDisplay,
                releaseStatus = if (spec == SherpaSttModelSpec.WHISPER) WHISPER_VERIFIER else STT_DEBUG_ONLY,
            )
        }
    }

    private val piperRows = SherpaPiperVoice.entries.map { voice ->
        ReleaseAssetAttribution(
            id = "piper:${voice.name}",
            name = "${voice.displayName} Piper voice pack",
            purposeCategory = "Speech synthesis (TTS) voice",
            sourceDisplay = voice.downloadUrl,
            licenceDisplay = if (voice == SherpaPiperVoice.CoriHigh) {
                "Public domain per the trainer's statement (brycebeattie.com/files/tts) on the LibriVox-derived dataset; the repository MIT tag is not a grant (docs/legal/piper-voice-review.md)"
            } else null,
            lineageDisplay = if (voice == SherpaPiperVoice.CoriHigh) {
                "rhasspy/piper-voices at 5041a7a49ae5b3a0f30e6837769eb03fdbb231bd (pre-retrain card); trained from scratch on LibriVox; packaged as Sherpa archive vits-piper-en_GB-cori-high"
            } else null,
            releaseStatus = if (voice == SherpaPiperVoice.CoriHigh) "Download on demand" else EXCLUDED,
        )
    }

    private val kokoroRows = SherpaKokoroVoice.entries.map { voice ->
        ReleaseAssetAttribution(
            id = "kokoro:${voice.name}",
            name = "${voice.displayName} Kokoro voice pack",
            purposeCategory = "Speech synthesis (TTS) voice",
            sourceDisplay = voice.downloadUrl,
            licenceDisplay = "Apache-2.0 recorded for Kokoro upstream; Sherpa pack terms under review",
            lineageDisplay = "Sherpa-ONNX ${voice.assetDirectoryName}; upstream hexgrad/Kokoro-82M and nvidia/kokoro-82M-onnx-opt",
            releaseStatus = "Debug-only/post-launch · not exposed in v0.1 release",
        )
    }

    private val engineRows = listOf(
        ReleaseAssetAttribution("stt:AndroidNative", "Android native speech recognizer", "Speech recognition (STT) service", "Android platform speech recognition service", "Platform terms; device/provider-specific", "Runs on the device platform; offline availability varies", "Release-exposed platform service"),
        ReleaseAssetAttribution("tts:AndroidTts", "Android text-to-speech", "Speech synthesis (TTS) service", "Android platform text-to-speech service", "Platform terms; installed engine-specific", "Runs on the device platform", "Release fallback"),
        ReleaseAssetAttribution("tts:SherpaExperimental", "Sherpa-ONNX Piper runtime", "Speech synthesis (TTS) engine", "Jandal's pinned Inflect-enabled Sherpa-ONNX AAR (third_party/sherpa-onnx/sherpa-onnx-1.13.0-noort-inflect.aar, SHA-256 46a8e806833066bc657d8508e66e35dae8b1a04dff207ebd8538517d58b6567d) and Sherpa-ONNX v1.13.0 source", "Apache-2.0 (Sherpa-ONNX); GPL-3.0-or-later (eSpeak-ng, statically linked in the packaged JNI library); MIT (piper-phonemize); public domain (uni-algo)", "Packaged libsherpa-onnx-jni.so for four ABIs; corresponding source and build manifest under third_party/sherpa-onnx (#1474 owner-accepted GPL posture)", "Release-visible · Piper voices download on demand"),
        ReleaseAssetAttribution("tts:InflectMicroExperimental", "Inflect Micro v2 TTS", "Speech synthesis (TTS) engine", "owensong/Inflect-Micro-v2-ONNX", "Apache-2.0 (upstream repository)", "Two ONNX graphs require the Inflect-enabled Sherpa-ONNX JNI extension", "Release-visible · flagship arm64 devices only"),
        ReleaseAssetAttribution("tts:KokoroExperimental", "Kokoro-82M TTS", "Speech synthesis (TTS) engine", "Sherpa-ONNX Kokoro voice-pack release", "Apache-2.0 recorded upstream; pack terms under review", "hexgrad/Kokoro-82M; nvidia/kokoro-82M-onnx-opt", "Debug-only/post-launch · excluded from v0.1"),
        ReleaseAssetAttribution("wakeword:openwakeword-melspectrogram", "openWakeWord melspectrogram stage", "Wake-word audio preprocessing", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx", "CC BY-NC-SA 4.0 as published upstream (owner decision, #2017); file-level terms unresolved (PR #2020)", "Unmodified upstream v0.5.1 asset; SHA-256 ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f", "Bundled in release · CC BY-NC-SA 4.0"),
        ReleaseAssetAttribution("wakeword:openwakeword-embedding", "openWakeWord embedding stage", "Wake-word audio embedding", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx", "CC BY-NC-SA 4.0 as published upstream (owner decision, #2017); file-level terms unresolved (PR #2020)", "Unmodified upstream v0.5.1 asset; SHA-256 70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f; upstream attributes the backbone to Google speech_embedding (Apache-2.0)", "Bundled in release · CC BY-NC-SA 4.0"),
        ReleaseAssetAttribution("wakeword:hey-jandal", "Hey Jandal wake-word classifier", "Wake-word detection", "Jandal training/wakeword pipeline · app/src/main/assets/models/wakeword/hey_jandal.onnx", "CC BY-NC-SA 4.0 (owner decision, #2017; ShareAlike)", "Jandal-specific Stage 3 classifier, SHA-256 11bcdb0d800b3a93449197122bd9fb484c4b8db887364c629f6c975e3e38c206; training used consented maintainer/spouse recordings plus Piper-generated synthetic clips as recorded in training/wakeword/README.md", "Bundled in release · CC BY-NC-SA 4.0"),
        ReleaseAssetAttribution("asset:vocab", "Bundled tokenizer vocabulary", "Memory search tokenizer", "Snowflake/snowflake-arctic-embed-m-v1.5 · revision e58a8f756156a1293d763f17e3aae643474e9b8a", "Apache-2.0", "WordPiece vocabulary, SHA-256 07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3", "Bundled in release"),
        ReleaseAssetAttribution("asset:intent-vectors", "Bundled MiniLM intent phrase vectors", "Intent matching vectors", "Jandal-generated from the bundled MiniLM model, vocab.txt and intent_phrases.json", "Apache-2.0 (Jandal project)", "Hash-bound generated vector asset; generation and freshness checks are documented in docs/minilm-intent-phrase-vectors.md", "Bundled in release"),
    )

    val entries: List<ReleaseAssetAttribution> = modelRows + sttRows + piperRows + kokoroRows + engineRows
}
