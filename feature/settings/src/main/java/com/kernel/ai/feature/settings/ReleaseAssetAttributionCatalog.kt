package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.KernelModel
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

    /** Rows for assets that are debug-only or excluded from the v0.1 release (#2084). */
    val isDebugOnly: Boolean
        get() = releaseStatus.contains("debug", ignoreCase = true) || releaseStatus.contains("excluded", ignoreCase = true)
}

/**
 * Offline attribution rows sourced from the same catalogues used by model and voice downloads. One row per asset
 * family, not one per downloaded file (#2084). Display text carries no issue, PR, path or hash references.
 */
object ReleaseAssetAttributionCatalog {
    const val UNDER_REVIEW = "Under review"
    private const val EXCLUDED = "Debug/internal only · excluded from v0.1"
    private const val STT_DEBUG_ONLY = "Debug-only · Sherpa STT input excluded from v0.1"
    private const val WHISPER_VERIFIER = "Downloaded with Hey Jandal · release-visible"

    private data class Details(val source: String, val licence: String, val lineage: String)

    private fun details(model: KernelModel): Details = when (model) {
        KernelModel.GEMMA_4_E2B, KernelModel.GEMMA_4_E4B -> Details(
            "Hugging Face · " + model.downloadUrl.removePrefix("https://huggingface.co/").substringBefore("/resolve/"),
            "Apache-2.0 (recorded upstream repository metadata)",
            "Gemma 4 instruction-tuned model packaged as LiteRT-LM and downloaded from the named upstream repository",
        )
        KernelModel.ARCTIC_EMBED_M_V1_5, KernelModel.ARCTIC_EMBED_V1_5_VOCAB -> Details(
            "Snowflake/snowflake-arctic-embed-m-v1.5; packaged by Jandal",
            "Apache-2.0",
            "Arctic Embed M v1.5; source revision e58a8f756156a1293d763f17e3aae643474e9b8a",
        )
        KernelModel.MINI_LM -> Details(
            "Nihal2000/all-MiniLM-L6-v2-quant.tflite · commit ad6c27c81b2c156925c1fb038d533f52f94ccd77",
            "Apache-2.0",
            "Pre-converted INT8 TFLite derivative of sentence-transformers/all-MiniLM-L6-v2",
        )
        KernelModel.SHERPA_SENSEVOICE_MODEL, KernelModel.SHERPA_SENSEVOICE_TOKENS -> Details(
            "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            "FunASR model-weight terms; legal review pending (not MIT-only)",
            "ONNX export of FunAudioLLM/SenseVoice; FunASR code and model-weight terms are distinct",
        )
        KernelModel.SHERPA_WHISPER_TINY_EN_ENCODER, KernelModel.SHERPA_WHISPER_TINY_EN_DECODER,
        KernelModel.SHERPA_WHISPER_TINY_EN_TOKENS -> Details(
            "csukuangfj/sherpa-onnx-whisper-tiny.en",
            "OpenAI Whisper code MIT; whisper-tiny.en weights Apache-2.0 (OpenAI model card). The export repository has no licence file; attributed to the upstream weights",
            "ONNX export lineage: openai/whisper-tiny.en (English-only); export pinned at d026532c022fa99fd789d6b32446a1df7b6bfc43",
        )
        KernelModel.SHERPA_PARAFORMER_ENCODER, KernelModel.SHERPA_PARAFORMER_DECODER,
        KernelModel.SHERPA_PARAFORMER_TOKENS -> Details(
            "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            "Apache-2.0 on export page; upstream model-weight terms require review",
            "ONNX export of DAMO speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-online; FunASR MODEL_LICENSE applies where relevant",
        )
        KernelModel.ASR4ALL_M_CONFIG, KernelModel.ASR4ALL_M_RELEASE_GEOM,
        KernelModel.ASR4ALL_M_PREPROCESSOR, KernelModel.ASR4ALL_M_METADATA,
        KernelModel.ASR4ALL_M_ENCODER, KernelModel.ASR4ALL_L_CONFIG,
        KernelModel.ASR4ALL_L_RELEASE_GEOM, KernelModel.ASR4ALL_L_PREPROCESSOR,
        KernelModel.ASR4ALL_L_METADATA, KernelModel.ASR4ALL_L_ENCODER -> Details(
            "Hugging Face · asr4all-m and asr4all-l export repositories",
            "FUTO Model Weights License (Restricted Use) 1.0",
            "Unmodified ExecuTorch INT8 (XNNPACK) export of the asr4all speech-recognition model; M and L sizes",
        )
        KernelModel.INFLECT_MICRO_DURATION, KernelModel.INFLECT_MICRO_DECODE -> Details(
            "owensong/Inflect-Micro-v2-ONNX",
            "Apache License 2.0 (upstream model repository)",
            "Inflect Micro v2 ONNX duration and decode graphs, downloaded on demand from the upstream repository",
        )
        KernelModel.KOKORO_DURATION, KernelModel.KOKORO_SYNTHESIZER,
        KernelModel.KOKORO_LEXICON_EN_US, KernelModel.KOKORO_LEXICON_EN_GB,
        KernelModel.KOKORO_TAGS, KernelModel.KOKORO_VOICE_BF_EMMA,
        KernelModel.KOKORO_VOICE_BM_DANIEL, KernelModel.KOKORO_VOICE_AF_HEART -> Details(
            "Hugging Face · software-mansion/react-native-executorch-kokoro at v0.11.0",
            "Apache-2.0 model; MIT ExecuTorch export; Apache-2.0 misaki lexicon data (verify before release)",
            "ExecuTorch FP32 export of hexgrad/Kokoro-82M with misaki phonemiser lexicons; downloaded on demand",
        )
    }

    private fun purposeOf(model: KernelModel): String = when (model) {
        KernelModel.GEMMA_4_E2B, KernelModel.GEMMA_4_E4B -> "Conversation model"
        KernelModel.ARCTIC_EMBED_M_V1_5 -> "Memory search embedding model"
        KernelModel.ARCTIC_EMBED_V1_5_VOCAB -> "Memory search tokenizer"
        KernelModel.MINI_LM -> "Intent classification model"
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
        KernelModel.INFLECT_MICRO_DURATION, KernelModel.INFLECT_MICRO_DECODE -> "Speech synthesis (TTS) model"
        KernelModel.KOKORO_DURATION, KernelModel.KOKORO_SYNTHESIZER,
        KernelModel.KOKORO_LEXICON_EN_US, KernelModel.KOKORO_LEXICON_EN_GB,
        KernelModel.KOKORO_TAGS, KernelModel.KOKORO_VOICE_BF_EMMA,
        KernelModel.KOKORO_VOICE_BM_DANIEL, KernelModel.KOKORO_VOICE_AF_HEART -> "Speech synthesis (TTS) model"
    }

    private fun modelRow(model: KernelModel) = details(model).let { d ->
        ReleaseAssetAttribution(
            id = "model:${model.name}",
            name = model.displayName,
            purposeCategory = purposeOf(model),
            sourceDisplay = d.source,
            licenceDisplay = d.licence,
            lineageDisplay = d.lineage,
            releaseStatus = if (model.isBundled) "Bundled in release" else "Download on demand",
        )
    }

    /** One row for a multi-file asset. Its details come from the first member; it is bundled only if every member is. */
    private fun familyRow(id: String, name: String, purpose: String, members: List<KernelModel>): ReleaseAssetAttribution {
        val d = details(members.first())
        return ReleaseAssetAttribution(
            id = id,
            name = name,
            purposeCategory = purpose,
            sourceDisplay = d.source,
            licenceDisplay = d.licence,
            lineageDisplay = d.lineage,
            releaseStatus = if (members.all { it.isBundled }) "Bundled in release" else "Download on demand",
        )
    }

    private val gemmaRows = listOf(KernelModel.GEMMA_4_E2B, KernelModel.GEMMA_4_E4B).map(::modelRow)

    private val arcticRow = familyRow(
        id = "model:ARCTIC_EMBED",
        name = "Arctic Embed M v1.5 embedding and tokenizer",
        purpose = "Memory search embedding model and tokenizer",
        members = KernelModel.entries.filter { it.name.startsWith("ARCTIC_EMBED") },
    )

    private val asr4allRow = familyRow(
        id = "stt:ASR4ALL",
        name = "asr4all speech recognition (M and L sizes)",
        purpose = "Speech recognition (STT) model",
        members = KernelModel.entries.filter { it.name.startsWith("ASR4ALL_") },
    )

    private val inflectRow = familyRow(
        id = "model:INFLECT_MICRO",
        name = "Inflect Micro v2 TTS duration and decode",
        purpose = "Speech synthesis (TTS) model",
        members = KernelModel.entries.filter { it.name.startsWith("INFLECT_") },
    )

    /** One row per Sherpa STT engine family, not one per downloaded file (#2084). */
    private val sttRows = SherpaSttModelSpec.ALL.values.map { spec ->
        val kernel = spec.requiredFileNames.firstNotNullOf { file -> KernelModel.entries.firstOrNull { it.fileName == file } }
        val d = details(kernel)
        ReleaseAssetAttribution(
            id = "stt:${spec.engine.name}",
            name = spec.displayLabel,
            purposeCategory = purposeOf(kernel),
            sourceDisplay = d.source,
            licenceDisplay = d.licence,
            lineageDisplay = d.lineage,
            releaseStatus = if (spec == SherpaSttModelSpec.WHISPER) WHISPER_VERIFIER else STT_DEBUG_ONLY,
        )
    }

    private val piperRows = SherpaPiperVoice.entries.map { voice ->
        ReleaseAssetAttribution(
            id = "piper:${voice.name}",
            name = "${voice.displayName} Piper voice pack",
            purposeCategory = "Speech synthesis (TTS) voice",
            sourceDisplay = voice.downloadUrl,
            licenceDisplay = if (voice == SherpaPiperVoice.CoriHigh) {
                "Public domain per the trainer's statement (brycebeattie.com/files/tts) on the LibriVox-derived dataset; the repository MIT tag is not a grant"
            } else null,
            lineageDisplay = if (voice == SherpaPiperVoice.CoriHigh) {
                "rhasspy/piper-voices at 5041a7a49ae5b3a0f30e6837769eb03fdbb231bd (pre-retrain card); trained from scratch on LibriVox; packaged as Sherpa archive vits-piper-en_GB-cori-high"
            } else null,
            releaseStatus = if (voice == SherpaPiperVoice.CoriHigh) "Download on demand" else EXCLUDED,
        )
    }

    private val kokoroRows = listOf(
        ReleaseAssetAttribution(
            id = "kokoro:pack",
            name = "Kokoro-82M ExecuTorch voice pack (en-US and en-GB lexicons; bf_emma, bm_daniel, af_heart voices)",
            purposeCategory = "Speech synthesis (TTS) voice",
            sourceDisplay = "https://huggingface.co/software-mansion/react-native-executorch-kokoro/resolve/v0.11.0/",
            licenceDisplay = "Apache-2.0 (hexgrad/Kokoro-82M model weights); MIT (software-mansion ExecuTorch export v0.11.0); Apache-2.0 (misaki-derived lexicon data)",
            lineageDisplay = "hexgrad/Kokoro-82M, exported by software-mansion/react-native-executorch-kokoro v0.11.0 (XNNPACK fp32 standard graphs)",
            releaseStatus = "Download on demand (~351 MB)",
        ),
    )

    private val engineRows = listOf(
        ReleaseAssetAttribution("stt:AndroidNative", "Android native speech recognizer", "Speech recognition (STT) service", "Android platform speech recognition service", "Platform terms; device/provider-specific", "Runs on the device platform; offline availability varies", "Release-exposed platform service"),
        ReleaseAssetAttribution("tts:AndroidTts", "Android text-to-speech", "Speech synthesis (TTS) service", "Android platform text-to-speech service", "Platform terms; installed engine-specific", "Runs on the device platform", "Release fallback"),
        ReleaseAssetAttribution("tts:SherpaExperimental", "Sherpa-ONNX Piper runtime", "Speech synthesis (TTS) engine", "Inflect-enabled Sherpa-ONNX Android library (1.13.0 build) and Sherpa-ONNX v1.13.0 source", "Apache-2.0 (Sherpa-ONNX); GPL-3.0-or-later (eSpeak-ng, statically linked in the packaged JNI library); MIT (piper-phonemize); public domain (uni-algo)", "Packaged libsherpa-onnx-jni.so for four ABIs; GPL-3.0 corresponding-source terms apply to the eSpeak-ng code in it", "Release-visible · Piper voices download on demand"),
        ReleaseAssetAttribution("tts:InflectMicroExperimental", "Inflect Micro v2 TTS", "Speech synthesis (TTS) engine", "owensong/Inflect-Micro-v2-ONNX", "Apache License 2.0 (upstream model repository)", "Two ONNX graphs require the Inflect-enabled Sherpa-ONNX JNI extension", "Release-visible · flagship arm64 devices only"),
        ReleaseAssetAttribution("tts:KokoroExperimental", "Kokoro-82M TTS", "Speech synthesis (TTS) engine", "On-demand Kokoro pack (see kokoro:pack)", "Apache-2.0 (Kokoro-82M model)", "hexgrad/Kokoro-82M; ExecuTorch export software-mansion/react-native-executorch-kokoro v0.11.0", "Release-visible · optional on-demand pack, experimental"),
        ReleaseAssetAttribution("tts:KokoroPhonemis", "Kokoro phonemiser native library", "Speech synthesis (TTS) text frontend", "software-mansion-labs/Phonemis 5ec260e33127de044bda7147f52cea27a7e2e491 and software-mansion-labs/Verbalis b14f1b3f96e759326935de12f81b35b87ec57f6a, fetched at build time", "MIT (Phonemis, Verbalis); Unicode-3.0 (Unicode data); xsimd BSD-3-Clause; nlohmann-json MIT", "Packaged in the core/voice JNI library; no runtime download", "Release-visible · Kokoro engine"),
        ReleaseAssetAttribution("wakeword:openwakeword-melspectrogram", "openWakeWord melspectrogram stage", "Wake-word audio preprocessing", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx", "CC BY-NC-SA 4.0, used as published", "Unmodified upstream v0.5.1 asset", "Bundled in release · CC BY-NC-SA 4.0"),
        ReleaseAssetAttribution("wakeword:openwakeword-embedding", "openWakeWord embedding stage", "Wake-word audio embedding", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx", "CC BY-NC-SA 4.0, used as published", "Unmodified upstream v0.5.1 asset; upstream attributes the backbone to Google speech_embedding (Apache-2.0)", "Bundled in release · CC BY-NC-SA 4.0"),
        ReleaseAssetAttribution("wakeword:hey-jandal", "Hey Jandal wake-word classifier", "Wake-word detection", "Jandal training pipeline (bundled classifier)", "CC BY-NC-SA 4.0 (ShareAlike)", "Jandal-specific Stage 3 classifier; training used consented recordings plus Piper-generated synthetic clips", "Bundled in release · CC BY-NC-SA 4.0"),
        ReleaseAssetAttribution("asset:vocab", "Bundled tokenizer vocabulary", "Memory search tokenizer", "Snowflake/snowflake-arctic-embed-m-v1.5 · revision e58a8f756156a1293d763f17e3aae643474e9b8a", "Apache-2.0", "WordPiece vocabulary", "Bundled in release"),
        ReleaseAssetAttribution("asset:intent-vectors", "Bundled MiniLM intent phrase vectors", "Intent matching vectors", "Jandal-generated from the bundled MiniLM model, vocabulary and intent phrase list", "Apache-2.0 (Jandal project)", "Generated vector asset, bound to its inputs by hash check", "Bundled in release"),
    )

    private val allRows: List<ReleaseAssetAttribution> =
        gemmaRows + modelRow(KernelModel.MINI_LM) + arcticRow + asr4allRow + inflectRow +
            sttRows + piperRows + kokoroRows + engineRows

    /**
     * Release builds hide debug-only and excluded rows. Debug and PR-preview builds show the full inventory,
     * so reviewers can check every asset that is installed (#2084).
     */
    fun entriesFor(isReleaseBuild: Boolean): List<ReleaseAssetAttribution> =
        if (isReleaseBuild) allRows.filterNot { it.isDebugOnly } else allRows
}
