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
    /**
     * Ordered list of speaker names, indexed by sid (0-based).
     * For `kokoro-int8-multi-lang-v1_1` this is the 103-entry v1.1 speaker map.
     */
    val speakerNames: List<String>,
) {
    KokoroMultiLangInt8(
        displayName = "Kokoro (Experimental)",
        description = "Studio-grade 82M parameter multilingual TTS. ~82MB model. Requires download.",
        assetDirectoryName = "kokoro-int8-multi-lang-v1_1",
        downloadKey = "kokoro-int8-multi-lang-v1_1",
        approxDownloadBytes = 130_000_000L,  // rough estimate incl voices.bin + lexicons
        speakerCount = 103,
        lang = "en",
        speakerNames = listOf(
            /* 0  */ "af_maple",
            /* 1  */ "af_sol",
            /* 2  */ "bf_vale",
            /* 3  */ "zf_001",
            /* 4  */ "zf_002",
            /* 5  */ "zf_003",
            /* 6  */ "zf_004",
            /* 7  */ "zf_005",
            /* 8  */ "zf_006",
            /* 9  */ "zf_007",
            /* 10 */ "zf_008",
            /* 11 */ "zf_017",
            /* 12 */ "zf_018",
            /* 13 */ "zf_019",
            /* 14 */ "zf_021",
            /* 15 */ "zf_022",
            /* 16 */ "zf_023",
            /* 17 */ "zf_024",
            /* 18 */ "zf_026",
            /* 19 */ "zf_027",
            /* 20 */ "zf_028",
            /* 21 */ "zf_032",
            /* 22 */ "zf_036",
            /* 23 */ "zf_038",
            /* 24 */ "zf_039",
            /* 25 */ "zf_040",
            /* 26 */ "zf_042",
            /* 27 */ "zf_043",
            /* 28 */ "zf_044",
            /* 29 */ "zf_046",
            /* 30 */ "zf_047",
            /* 31 */ "zf_048",
            /* 32 */ "zf_049",
            /* 33 */ "zf_051",
            /* 34 */ "zf_059",
            /* 35 */ "zf_060",
            /* 36 */ "zf_067",
            /* 37 */ "zf_070",
            /* 38 */ "zf_071",
            /* 39 */ "zf_072",
            /* 40 */ "zf_073",
            /* 41 */ "zf_074",
            /* 42 */ "zf_075",
            /* 43 */ "zf_076",
            /* 44 */ "zf_077",
            /* 45 */ "zf_078",
            /* 46 */ "zf_079",
            /* 47 */ "zf_083",
            /* 48 */ "zf_084",
            /* 49 */ "zf_085",
            /* 50 */ "zf_086",
            /* 51 */ "zf_087",
            /* 52 */ "zf_088",
            /* 53 */ "zf_090",
            /* 54 */ "zf_092",
            /* 55 */ "zf_093",
            /* 56 */ "zf_094",
            /* 57 */ "zf_099",
            /* 58 */ "zm_009",
            /* 59 */ "zm_010",
            /* 60 */ "zm_011",
            /* 61 */ "zm_012",
            /* 62 */ "zm_013",
            /* 63 */ "zm_014",
            /* 64 */ "zm_015",
            /* 65 */ "zm_016",
            /* 66 */ "zm_020",
            /* 67 */ "zm_025",
            /* 68 */ "zm_029",
            /* 69 */ "zm_030",
            /* 70 */ "zm_031",
            /* 71 */ "zm_033",
            /* 72 */ "zm_034",
            /* 73 */ "zm_035",
            /* 74 */ "zm_037",
            /* 75 */ "zm_041",
            /* 76 */ "zm_045",
            /* 77 */ "zm_050",
            /* 78 */ "zm_052",
            /* 79 */ "zm_053",
            /* 80 */ "zm_054",
            /* 81 */ "zm_055",
            /* 82 */ "zm_056",
            /* 83 */ "zm_057",
            /* 84 */ "zm_058",
            /* 85 */ "zm_061",
            /* 86 */ "zm_062",
            /* 87 */ "zm_063",
            /* 88 */ "zm_064",
            /* 89 */ "zm_065",
            /* 90 */ "zm_066",
            /* 91 */ "zm_068",
            /* 92 */ "zm_069",
            /* 93 */ "zm_080",
            /* 94 */ "zm_081",
            /* 95 */ "zm_082",
            /* 96 */ "zm_089",
            /* 97 */ "zm_091",
            /* 98 */ "zm_095",
            /* 99 */ "zm_096",
            /* 100 */ "zm_097",
            /* 101 */ "zm_098",
            /* 102 */ "zm_100",
        ),
    ),
    ;

    /** Returns the display name for [sid], or "Speaker N" if out of range. */
    fun speakerNameForSid(sid: Int): String = speakerNames.getOrElse(sid) { "Speaker $sid" }

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

/**
 * Logical groupings of Kokoro speakers for the `kokoro-int8-multi-lang-v1_1` voice pack.
 *
 * Each group maps to a contiguous sid range. The prefix convention is:
 * - `af` = American female, `bf` = British female, `zf` = Chinese female, `zm` = Chinese male.
 */
enum class KokoroSpeakerGroup(val label: String, val sidRange: IntRange) {
    AmericanFemale("American female", 0..1),
    BritishFemale("British female", 2..2),
    ChineseFemale("Chinese female", 3..57),
    ChineseMale("Chinese male", 58..102),
}

/** Returns the [KokoroSpeakerGroup] that contains [sid], defaulting to [KokoroSpeakerGroup.AmericanFemale]. */
fun SherpaKokoroVoice.speakerGroupForSid(sid: Int): KokoroSpeakerGroup =
    KokoroSpeakerGroup.entries.firstOrNull { sid in it.sidRange }
        ?: KokoroSpeakerGroup.AmericanFemale
