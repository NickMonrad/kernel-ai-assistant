package com.kernel.ai.core.voice

import android.content.Context
import java.io.File

enum class SherpaPiperVoice(
    val displayName: String,
    val description: String,
    val assetDirectoryName: String,
    val downloadKey: String,
    /** Approximate compressed download size in bytes (used for progress UI). */
    val approxDownloadBytes: Long,
) {
    JennyDioco(
        displayName = "Jenny Dioco",
        description = "Balanced medium-quality British English voice and the default Sherpa option for this spike.",
        assetDirectoryName = "vits-piper-en_GB-jenny_dioco-medium",
        downloadKey = "en_GB-jenny_dioco-medium",
        approxDownloadBytes = 73_000_000L,
    ),
    SouthernEnglishFemale(
        displayName = "Southern English Female",
        description = "Closest publicly available southern-English Piper pack for this spike setup.",
        assetDirectoryName = "vits-piper-en_GB-southern_english_female-low",
        downloadKey = "en_GB-southern_english_female-low",
        approxDownloadBytes = 64_000_000L,
    ),
    NorthernEnglishMale(
        displayName = "Northern English Male",
        description = "Medium-quality northern British English voice.",
        assetDirectoryName = "vits-piper-en_GB-northern_english_male-medium",
        downloadKey = "en_GB-northern_english_male-medium",
        approxDownloadBytes = 74_000_000L,
    ),
    ;

    /** URL to the pre-converted Sherpa-ONNX voice pack tarball on GitHub releases. */
    val downloadUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$assetDirectoryName.tar.bz2"

    /** Legacy asset sub-path (used if the voice is still bundled as an APK asset). */
    val assetsSubdirectory: String
        get() = "sherpa-tts/$assetDirectoryName"

    /** Returns the directory where this voice pack is extracted on internal storage. */
    fun voiceDir(context: Context): File =
        File(context.filesDir, "sherpa-tts/$assetDirectoryName")

    /** Returns true when the voice pack is fully extracted to internal storage. */
    fun isDownloaded(context: Context): Boolean {
        val dir = voiceDir(context)
        return hasRequiredSherpaVoicePackFiles(dir)
    }

    companion object {
        fun fromStorage(value: String?): SherpaPiperVoice =
            entries.firstOrNull { it.name == value } ?: JennyDioco
    }
}
