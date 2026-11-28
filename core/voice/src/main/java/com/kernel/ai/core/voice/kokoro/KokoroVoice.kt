package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.kokoro.NativeKokoroPhonemizer.Companion.LOCALE_EN_GB
import com.kernel.ai.core.voice.kokoro.NativeKokoroPhonemizer.Companion.LOCALE_EN_US

/**
 * The Kokoro voices offered in Settings (#2117 shortlist from the #2116 renders). [voiceName] is the upstream voice
 * file stem; [locale] selects the phonemis lexicon, so a British voice never reads through the American lexicon.
 */
enum class KokoroVoice(
    val voiceName: String,
    val displayName: String,
    val locale: String,
    val description: String,
) {
    EMMA("bf_emma", "Emma", LOCALE_EN_GB, "British woman"),
    DANIEL("bm_daniel", "Daniel", LOCALE_EN_GB, "British man"),
    HEART("af_heart", "Heart", LOCALE_EN_US, "American woman"),
    ;

    companion object {
        val DEFAULT: KokoroVoice = EMMA

        /** Storage falls back to [DEFAULT] for unknown or missing values, so an old or corrupt pref never crashes. */
        fun fromStorage(name: String?): KokoroVoice = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
