package com.kernel.ai.core.inference.download

import android.content.Context
import com.kernel.ai.core.inference.hardware.HardwareTier
import java.io.File

/**
 * Catalogue of all on-device models that Kernel AI can download and use.
 *
 * Download URLs may use upstream model hosting or versioned project release assets.
 * Entries with a pinned SHA-256 are verified before the downloaded file is installed.
 */
enum class KernelModel(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    /** Approximate file size in bytes — used for storage space checks before download. */
    val approxSizeBytes: Long,
    /** If true, the app requires this model to function at all. */
    val isRequired: Boolean,
    /**
     * If non-null, this model is the preferred conversation model for that tier.
     * Null means the model is suitable for any tier (or is not a conversation model).
     */
    val preferredForTier: HardwareTier?,
    /**
     * If `true`, this model is gated on HuggingFace and requires an authenticated
     * access token to download. The [ModelDownloadManager] will attach a Bearer token
     * to the download request when this is `true`.
     */
    val isGated: Boolean = false,
    /**
     * URL to the HuggingFace model licence page. Non-null for gated models — shown in the
     * Model Management UI so users can accept the licence before downloading.
     */
    val licenceUrl: String? = null,
    /**
     * If `true`, this model is bundled as an app asset and is always available without
     * downloading. Model Management shows it as "Built-in" — no download or delete controls.
     */
    val isBundled: Boolean = false,
    /**
     * If `false`, this model is managed by a feature-specific UI (e.g. Voice settings) and
     * should be hidden from the generic Model Management screen to prevent partial installs.
     * Defaults to `true` so existing entries are unaffected.
     */
    val showInModelManagement: Boolean = true,
    /**
     * If `true`, this model has been superseded and is hidden from the Model Management
     * screen and the preferred-model picker. The existing download is not deleted — the
     * user must manually delete it through the storage settings.
     * Defaults to `false` so existing entries are unaffected.
     */
    val isDeprecated: Boolean = false,
    /** Pinned SHA-256 of the downloaded file, lowercase hex. The download is rejected on mismatch. */
    val sha256: String? = null,
) {
    GEMMA_4_E2B(
        displayName = "Gemma 4 E-2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxSizeBytes = 2_583_085_056L,
        isRequired = true,
        preferredForTier = null,
        isGated = false,
        licenceUrl = null,
    ),

    GEMMA_4_E4B(
        displayName = "Gemma 4 E-4B",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        approxSizeBytes = 3_654_467_584L,
        isRequired = false,
        preferredForTier = HardwareTier.FLAGSHIP,
        isGated = false,
        licenceUrl = null,
    ),

    ARCTIC_EMBED_M_V1_5(
        displayName = "Arctic Embed M v1.5",
        fileName = "arctic-embed-m-v1.5-int8.tflite",
        downloadUrl = "https://github.com/NickMonrad/kernel-ai-assistant/releases/download/model-arctic-embed-m-v1.5-current/arctic-embed-m-v1.5-int8.tflite",
        approxSizeBytes = 113_850_784L,
        isRequired = true,
        preferredForTier = null,
        isGated = false,
    ),

    ARCTIC_EMBED_V1_5_VOCAB(
        displayName = "Arctic Embed v1.5 vocabulary",
        fileName = "arctic-embed-m-v1.5-vocab.txt",
        downloadUrl = "https://github.com/NickMonrad/kernel-ai-assistant/releases/download/model-arctic-embed-m-v1.5-current/arctic-embed-m-v1.5-vocab.txt",
        approxSizeBytes = 231_508L,
        isRequired = true,
        preferredForTier = null,
        isGated = false,
    ),

    MINI_LM(
        displayName = "MiniLM-L6 Intent Classifier",
        fileName = "minilm-l6-v2-int8.tflite",
        downloadUrl = "",
        approxSizeBytes = 23_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        isBundled = true,
    ),

    // ── Sherpa-ONNX SenseVoice int8 (Offline) ────────────────────────────────
    //
    // From csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
    // (HuggingFace, ONNX conversion of SenseVoice). Public repo with model.int8.onnx + tokens.txt.
    // Downloaded on demand when the user selects "Sherpa-ONNX SenseVoice"
    // in Settings → Voice. Licence details are shown via the repo page.

    SHERPA_SENSEVOICE_MODEL(
        displayName = "Sherpa SenseVoice Model",
        fileName = "sherpa-sensevoice-model.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        approxSizeBytes = 100_000_000L, // ~100 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        licenceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        showInModelManagement = false,
    ),

    SHERPA_SENSEVOICE_TOKENS(
        displayName = "Sherpa SenseVoice Tokens",
        fileName = "sherpa-sensevoice-tokens.txt",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
        approxSizeBytes = 100_000L, // ~100 KB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        licenceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        showInModelManagement = false,
    ),

    // ── Sherpa-ONNX Whisper tiny.en int8 (Offline) ───────────────────────────
    //
    // From csukuangfj/sherpa-onnx-whisper-tiny.en
    // (HuggingFace, Apache 2.0, public / ungated).  Encoder + decoder + tokens.
    // Downloaded on demand when the user selects "Sherpa-ONNX Whisper tiny.en"
    // in Settings → Voice.

    SHERPA_WHISPER_TINY_EN_ENCODER(
        displayName = "Sherpa Whisper tiny.en Encoder",
        fileName = "sherpa-whisper-tiny.en-encoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx",
        approxSizeBytes = 74_000_000L, // ~74 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_WHISPER_TINY_EN_DECODER(
        displayName = "Sherpa Whisper tiny.en Decoder",
        fileName = "sherpa-whisper-tiny.en-decoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx",
        approxSizeBytes = 43_000_000L, // ~43 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_WHISPER_TINY_EN_TOKENS(
        displayName = "Sherpa Whisper tiny.en Tokens",
        fileName = "sherpa-whisper-tiny.en-tokens.txt",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt",
        approxSizeBytes = 150_000L, // ~150 KB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    // ── Sherpa-ONNX Paraformer int8 (Streaming) ──────────────────────────────
    //
    // From csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en
    // (HuggingFace, Apache 2.0, public / ungated).  Encoder + decoder + tokens.
    // Downloaded on demand when the user selects "Sherpa-ONNX Paraformer"
    // in Settings → Voice.

    SHERPA_PARAFORMER_ENCODER(
        displayName = "Sherpa Paraformer Encoder",
        fileName = "sherpa-paraformer-encoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx",
        approxSizeBytes = 120_000_000L, // ~120 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_PARAFORMER_DECODER(
        displayName = "Sherpa Paraformer Decoder",
        fileName = "sherpa-paraformer-decoder.int8.onnx",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx",
        approxSizeBytes = 100_000_000L, // ~100 MB
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    SHERPA_PARAFORMER_TOKENS(
        displayName = "Sherpa Paraformer Tokens",
        fileName = "sherpa-paraformer-tokens.txt",
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt",
        approxSizeBytes = 100_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        showInModelManagement = false,
    ),

    // ── asr4all streaming STT, ExecuTorch INT8 (Offline) ──────────────────────
    //
    // FUTO asr4all exports, pinned to the revisions in docs/evidence/2004/CLEANROOM.md. Files
    // are stored under models/asr4all/<size>/ and mirror the export layout the engine reads.
    // Weights are under the FUTO Model Weights Licence 1.0 (see the Voice settings licence row).

    ASR4ALL_M_CONFIG(
        displayName = "asr4all M config",
        fileName = "asr4all/m/config.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-m/resolve/7d6e9145109a5bece4f05831d046e93d27e38a99/config.json",
        approxSizeBytes = 83_750L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "f20e34e046dc817b436ce4d2a5231307f51e7f048b5c04fda263cba53ca60e63",
    ),

    ASR4ALL_M_RELEASE_GEOM(
        displayName = "asr4all M release geometry",
        fileName = "asr4all/m/release_geom.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-m/resolve/7d6e9145109a5bece4f05831d046e93d27e38a99/release_geom.json",
        approxSizeBytes = 64_120L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "c1eeb3a2eee048f2061e0d4e63a8f65bcf3c00668582b2821847e42096ba3c5c",
    ),

    ASR4ALL_M_PREPROCESSOR(
        displayName = "asr4all M preprocessor",
        fileName = "asr4all/m/preprocessor_config.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-m/resolve/7d6e9145109a5bece4f05831d046e93d27e38a99/preprocessor_config.json",
        approxSizeBytes = 248L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "be01923661cc9c8149623163524d3ecde4552d879f79041f77b8db8be72d1caa",
    ),

    ASR4ALL_M_METADATA(
        displayName = "asr4all M ExecuTorch metadata",
        fileName = "asr4all/m/executorch/xnnpack_int8/metadata.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-m/resolve/7d6e9145109a5bece4f05831d046e93d27e38a99/executorch/xnnpack_int8/metadata.json",
        approxSizeBytes = 90_365L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "298e1c287b31892c9a04c340e2b1e266ba2ced83bdc08f76d0d68b62129ea606",
    ),

    ASR4ALL_M_ENCODER(
        displayName = "asr4all M encoder",
        fileName = "asr4all/m/executorch/xnnpack_int8/asr_encoder.pte",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-m/resolve/7d6e9145109a5bece4f05831d046e93d27e38a99/executorch/xnnpack_int8/asr_encoder.pte",
        approxSizeBytes = 60_035_136L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "d08648b914a90669986f943011099fb518783ca0bebb3fd9a4ded53ad5067763",
    ),

    ASR4ALL_L_CONFIG(
        displayName = "asr4all L config",
        fileName = "asr4all/l/config.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-l/resolve/79d1c23bc8f1174cee576b40bbaa0118bd95abed/config.json",
        approxSizeBytes = 83_751L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "6ec76f6203e2880d396203d2ece606d851a0c6ce334cf1e4546e49cb062ad13e",
    ),

    ASR4ALL_L_RELEASE_GEOM(
        displayName = "asr4all L release geometry",
        fileName = "asr4all/l/release_geom.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-l/resolve/79d1c23bc8f1174cee576b40bbaa0118bd95abed/release_geom.json",
        approxSizeBytes = 64_123L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "698091d2a3741195fcc9205518610da93a3c222169448687a395979b74cb0554",
    ),

    ASR4ALL_L_PREPROCESSOR(
        displayName = "asr4all L preprocessor",
        fileName = "asr4all/l/preprocessor_config.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-l/resolve/79d1c23bc8f1174cee576b40bbaa0118bd95abed/preprocessor_config.json",
        approxSizeBytes = 248L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "be01923661cc9c8149623163524d3ecde4552d879f79041f77b8db8be72d1caa",
    ),

    ASR4ALL_L_METADATA(
        displayName = "asr4all L ExecuTorch metadata",
        fileName = "asr4all/l/executorch/xnnpack_int8/metadata.json",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-l/resolve/79d1c23bc8f1174cee576b40bbaa0118bd95abed/executorch/xnnpack_int8/metadata.json",
        approxSizeBytes = 90_364L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "f528bafa98cdb47847699e2dfd15a693c9ac45f5b3311fc315dc5e2c2341a0e0",
    ),

    ASR4ALL_L_ENCODER(
        displayName = "asr4all L encoder",
        fileName = "asr4all/l/executorch/xnnpack_int8/asr_encoder.pte",
        downloadUrl = "https://huggingface.co/futo-org/asr4all-l/resolve/79d1c23bc8f1174cee576b40bbaa0118bd95abed/executorch/xnnpack_int8/asr_encoder.pte",
        approxSizeBytes = 100_973_248L,
        isRequired = false,
        preferredForTier = null,
        showInModelManagement = false,
        sha256 = "2670068ab08a2a40d76e6cb79d492ce6879d7165c5f6a8defbf2f4994a49f760",
    ),

    // ── Inflect Micro v2 ONNX voice output ────────────────────────────────────
    //
    // Official Apache-2.0 export from owensong/Inflect-Micro-v2-ONNX. The
    // frontend and runner require both graphs; Voice settings owns this pair.
    INFLECT_MICRO_DURATION(
        displayName = "Inflect Micro duration graph",
        fileName = "duration.onnx",
        downloadUrl = "https://huggingface.co/owensong/Inflect-Micro-v2-ONNX/resolve/main/onnx/duration.onnx",
        approxSizeBytes = 7_700_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        licenceUrl = "https://huggingface.co/owensong/Inflect-Micro-v2-ONNX",
        showInModelManagement = false,
    ),

    INFLECT_MICRO_DECODE(
        displayName = "Inflect Micro decode graph",
        fileName = "decode.onnx",
        downloadUrl = "https://huggingface.co/owensong/Inflect-Micro-v2-ONNX/resolve/main/onnx/decode.onnx",
        approxSizeBytes = 32_000_000L,
        isRequired = false,
        preferredForTier = null,
        isGated = false,
        licenceUrl = "https://huggingface.co/owensong/Inflect-Micro-v2-ONNX",
        showInModelManagement = false,
    );
    /**
     * Stable, single-sourced identifier for this model, used as the Room primary key in
     * `com.kernel.ai.core.memory.entity.ModelSettingsEntity`.
     *
     * Derived from the enum entry name in lowercase, e.g.:
     * - [GEMMA_4_E2B] → `"gemma_4_e2b"`
     * - [GEMMA_4_E4B] → `"gemma_4_e4b"`
     *
     * Never derive keys from [name] at call sites — always use this property.
     */
    val modelId: String get() = name.lowercase()

    /**
     * True for the asr4all speech model files. Their download needs the Terms of Use accepted first (#1998).
     */
    val requiresTermsAcceptance: Boolean get() = name.startsWith("ASR4ALL_")

    /**
     * True for downloads whose licence terms the app links to (#2049): the Gemma models (Gemma Terms of Use, listed in
     * TERMS.md) and the asr4all speech files. The Models screen shows the Terms link on their download rows.
     */
    val showsTermsOfUseLink: Boolean get() = requiresTermsAcceptance || name.startsWith("GEMMA_")
}

/** Absolute path to this model's file on external app storage (survives reinstall). */
fun KernelModel.localFile(context: Context): File {
    val modelsDir = context.getExternalFilesDir("models")
        ?: File(context.filesDir, "models") // fallback if external storage unavailable
    modelsDir.mkdirs()
    return File(modelsDir, fileName)
}

/** True if the model file exists and is non-empty. Bundled models are always considered downloaded. */
fun KernelModel.isDownloaded(context: Context): Boolean {
    if (isBundled) return true
    val file = localFile(context)
    return file.exists() && file.length() > 0
}
