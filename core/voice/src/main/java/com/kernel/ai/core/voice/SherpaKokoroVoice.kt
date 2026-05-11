package com.kernel.ai.core.voice

import android.content.Context
import java.io.File

/**
 * Enum of available Kokoro-based Sherpa-ONNX TTS models.
 *
 * Kokoro uses [com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig] (accessed via reflection in
 * [SherpaOnnxVoiceOutputController]) rather than the Piper VITS config.  Sample rate is read
 * dynamically from [com.k2fsa.sherpa.onnx.GeneratedAudio] at runtime so 24 kHz just works.
 *
 * Voice packs are downloaded on-device from Settings → Voice and extracted to
 * [Context.filesDir]/sherpa-tts/<assetDirectoryName>/.
 */
enum class SherpaKokoroVoice(
    val displayName: String,
    val description: String,
    val assetDirectoryName: String,
    val downloadKey: String,
    /** Approximate total download size in bytes (model + voices.bin + lexicons). */
    val approxDownloadBytes: Long,
    val speakerCount: Int,
    /** BCP-47 language tag passed to Sherpa's Kokoro config. */
    val lang: String,
) {
    KokoroMultiLangInt8(
        displayName = "Kokoro (Experimental)",
        description = "Studio-grade 82M parameter multilingual TTS. ~82MB model. Requires download.",
        assetDirectoryName = "kokoro-int8-multi-lang-v1_1",
        downloadKey = "kokoro-int8-multi-lang-v1_1",
        approxDownloadBytes = 130_000_000L,  // rough estimate incl voices.bin + lexicons
        speakerCount = 103,
        lang = "en",
    ),
    ;

    /** URL to the Sherpa-ONNX Kokoro model tarball on GitHub releases. */
    val downloadUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$assetDirectoryName.tar.bz2"

    /** Returns the directory where this voice pack is extracted on internal storage. */
    fun voiceDir(context: Context): File =
        File(context.filesDir, "sherpa-tts/$assetDirectoryName")

    /** Returns true when the voice pack is fully extracted and validated. */
    fun isDownloaded(context: Context): Boolean =
        hasRequiredKokoroVoicePackFiles(voiceDir(context))

    companion object {
        fun fromStorage(value: String?): SherpaKokoroVoice =
            entries.firstOrNull { it.name == value } ?: KokoroMultiLangInt8
    }
}
