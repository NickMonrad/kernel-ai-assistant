package com.kernel.ai.core.voice

enum class SherpaPiperVoice(
    val displayName: String,
    val description: String,
    val assetDirectoryName: String,
    val downloadKey: String,
) {
    JennyDioco(
        displayName = "Jenny Dioco",
        description = "Balanced medium-quality British English voice and the default Sherpa option for this spike.",
        assetDirectoryName = "vits-piper-en_GB-jenny_dioco-medium",
        downloadKey = "en_GB-jenny_dioco-medium",
    ),
    SouthernEnglishFemale(
        displayName = "Southern English Female",
        description = "Closest publicly available southern-English Piper pack for this spike setup.",
        assetDirectoryName = "vits-piper-en_GB-southern_english_female-low",
        downloadKey = "en_GB-southern_english_female-low",
    ),
    NorthernEnglishMale(
        displayName = "Northern English Male",
        description = "Medium-quality northern British English voice.",
        assetDirectoryName = "vits-piper-en_GB-northern_english_male-medium",
        downloadKey = "en_GB-northern_english_male-medium",
    ),
    ;

    val assetsSubdirectory: String
        get() = "sherpa-tts/$assetDirectoryName"

    companion object {
        fun fromStorage(value: String?): SherpaPiperVoice =
            entries.firstOrNull { it.name == value } ?: JennyDioco
    }
}
