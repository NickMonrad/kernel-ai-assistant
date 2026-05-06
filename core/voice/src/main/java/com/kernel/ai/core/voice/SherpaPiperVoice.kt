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
        description = "Lower-size southern British English female voice.",
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
    AlanMedium(
        displayName = "Alan",
        description = "Balanced British English male voice with a more neutral delivery.",
        assetDirectoryName = "vits-piper-en_GB-alan-medium",
        downloadKey = "en_GB-alan-medium",
        approxDownloadBytes = 67_220_121L,
    ),
    CoriHigh(
        displayName = "Cori",
        description = "Higher-quality British English female voice with smoother playback.",
        assetDirectoryName = "vits-piper-en_GB-cori-high",
        downloadKey = "en_GB-cori-high",
        approxDownloadBytes = 115_574_061L,
    ),
    AlbaMedium(
        displayName = "Alba",
        description = "Scottish-accented British English female voice with a distinct regional character.",
        assetDirectoryName = "vits-piper-en_GB-alba-medium",
        downloadKey = "en_GB-alba-medium",
        approxDownloadBytes = 67_200_000L,
    ),
    AruMedium(
        displayName = "Aru",
        description = "British English female voice with a clear and natural delivery.",
        assetDirectoryName = "vits-piper-en_GB-aru-medium",
        downloadKey = "en_GB-aru-medium",
        approxDownloadBytes = 67_200_000L,
    ),
    SemaineMedium(
        displayName = "Semaine",
        description = "Expressive British English voice with multiple emotional styles (neutral, happy, sad, angry, worried). Uses neutral style (sid=0) by default.",
        assetDirectoryName = "vits-piper-en_GB-semaine-medium",
        downloadKey = "en_GB-semaine-medium",
        approxDownloadBytes = 70_000_000L,
    ),
    VctkMedium(
        displayName = "VCTK",
        description = "British English multi-speaker voice trained on the VCTK dataset. Uses speaker 0 (sid=0) by default.",
        assetDirectoryName = "vits-piper-en_GB-vctk-medium",
        downloadKey = "en_GB-vctk-medium",
        approxDownloadBytes = 70_000_000L,
    ),
    AmyMedium(
        displayName = "Amy",
        description = "Balanced American English female voice for a less region-specific option.",
        assetDirectoryName = "vits-piper-en_US-amy-medium",
        downloadKey = "en_US-amy-medium",
        approxDownloadBytes = 67_223_746L,
    ),
    JoeMedium(
        displayName = "Joe",
        description = "Balanced American English male voice with a straightforward conversational tone.",
        assetDirectoryName = "vits-piper-en_US-joe-medium",
        downloadKey = "en_US-joe-medium",
        approxDownloadBytes = 67_169_394L,
    ),
    LessacHigh(
        displayName = "Lessac",
        description = "Higher-quality American English female voice and the most neutral-sounding Sherpa option here.",
        assetDirectoryName = "vits-piper-en_US-lessac-high",
        downloadKey = "en_US-lessac-high",
        approxDownloadBytes = 115_545_841L,
    ),
    RyanHigh(
        displayName = "Ryan",
        description = "Higher-quality American English male voice with fuller audio output.",
        assetDirectoryName = "vits-piper-en_US-ryan-high",
        downloadKey = "en_US-ryan-high",
        approxDownloadBytes = 115_630_708L,
    ),
    ;

    /** URL to the pre-converted Sherpa-ONNX voice pack tarball on GitHub releases. */
    val downloadUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$assetDirectoryName.tar.bz2"

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
