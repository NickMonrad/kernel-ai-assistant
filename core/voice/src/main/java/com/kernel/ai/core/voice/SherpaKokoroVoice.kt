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
        description = "Studio-grade 82M parameter multilingual TTS. 20 English + 33 multilingual voices. ~130MB. Requires download.",
        assetDirectoryName = "kokoro-int8-multi-lang-v1_0",
        downloadKey = "kokoro-int8-multi-lang-v1_0",
        approxDownloadBytes = 130_000_000L,  // rough estimate incl voices.bin + lexicons
        speakerCount = 53,
        lang = "en",
        speakerNames = listOf(
            // American Female (sids 0–10)
            /* 0  */ "af_alloy",
            /* 1  */ "af_aoede",
            /* 2  */ "af_bella",
            /* 3  */ "af_heart",
            /* 4  */ "af_jessica",
            /* 5  */ "af_kore",
            /* 6  */ "af_nicole",
            /* 7  */ "af_nova",
            /* 8  */ "af_river",
            /* 9  */ "af_sarah",
            /* 10 */ "af_sky",
            // American Male (sids 11–19)
            /* 11 */ "am_adam",
            /* 12 */ "am_echo",
            /* 13 */ "am_eric",
            /* 14 */ "am_fenrir",
            /* 15 */ "am_liam",
            /* 16 */ "am_michael",
            /* 17 */ "am_onyx",
            /* 18 */ "am_puck",
            /* 19 */ "am_santa",
            // British Female (sids 20–23)
            /* 20 */ "bf_alice",
            /* 21 */ "bf_emma",
            /* 22 */ "bf_isabella",
            /* 23 */ "bf_lily",
            // British Male (sids 24–27)
            /* 24 */ "bm_daniel",
            /* 25 */ "bm_fable",
            /* 26 */ "bm_george",
            /* 27 */ "bm_lewis",
            // Multilingual (sids 28–52)
            /* 28 */ "ef_dora",       // Spanish Female
            /* 29 */ "em_alex",       // Spanish Male
            /* 30 */ "ff_siwis",      // French Female
            /* 31 */ "hf_alpha",      // Hindi Female
            /* 32 */ "hf_beta",       // Hindi Female
            /* 33 */ "hm_omega",      // Hindi Male
            /* 34 */ "hm_psi",        // Hindi Male
            /* 35 */ "if_sara",       // Italian Female
            /* 36 */ "im_nicola",     // Italian Male
            /* 37 */ "jf_alpha",      // Japanese Female
            /* 38 */ "jf_gongitsune", // Japanese Female
            /* 39 */ "jf_nezumi",     // Japanese Female
            /* 40 */ "jf_tebukuro",   // Japanese Female
            /* 41 */ "jm_kumo",       // Japanese Male
            /* 42 */ "pf_dora",       // Portuguese Female
            /* 43 */ "pm_alex",       // Portuguese Male
            /* 44 */ "pm_santa",      // Portuguese Male
            /* 45 */ "zf_xiaobei",    // Chinese Female
            /* 46 */ "zf_xiaoni",     // Chinese Female
            /* 47 */ "zf_xiaoxiao",   // Chinese Female
            /* 48 */ "zf_xiaoyi",     // Chinese Female
            /* 49 */ "zm_yunjian",    // Chinese Male
            /* 50 */ "zm_yunxi",      // Chinese Male
            /* 51 */ "zm_yunxia",     // Chinese Male
            /* 52 */ "zm_yunyang",    // Chinese Male
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
 * Logical groupings of Kokoro speakers for the `kokoro-int8-multi-lang-v1_0` voice pack.
 *
 * Each group maps to a contiguous sid range. Prefix conventions:
 * - `af`/`am` = American female/male, `bf`/`bm` = British female/male
 * - `ef`/`em` = Spanish, `ff` = French, `hf`/`hm` = Hindi, `if`/`im` = Italian
 * - `jf`/`jm` = Japanese, `pf`/`pm` = Portuguese, `zf`/`zm` = Chinese
 */
enum class KokoroSpeakerGroup(val label: String, val sidRange: IntRange) {
    AmericanFemale("American female", 0..10),
    AmericanMale("American male", 11..19),
    BritishFemale("British female", 20..23),
    BritishMale("British male", 24..27),
    Multilingual("Multilingual", 28..52),
}

/** Returns the [KokoroSpeakerGroup] that contains [sid], defaulting to [KokoroSpeakerGroup.AmericanFemale]. */
fun SherpaKokoroVoice.speakerGroupForSid(sid: Int): KokoroSpeakerGroup =
    KokoroSpeakerGroup.entries.firstOrNull { sid in it.sidRange }
        ?: KokoroSpeakerGroup.AmericanFemale
