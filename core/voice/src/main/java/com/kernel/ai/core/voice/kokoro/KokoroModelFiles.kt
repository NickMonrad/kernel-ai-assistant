package com.kernel.ai.core.voice.kokoro

import android.content.Context
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.voice.kokoro.NativeKokoroPhonemizer.Companion.LOCALE_EN_GB
import com.kernel.ai.core.voice.kokoro.NativeKokoroPhonemizer.Companion.LOCALE_EN_US
import java.io.File

/** Which downloaded Kokoro files a voice needs, and where they live. Downloads are managed in Settings › Voice. */
internal object KokoroModelFiles {
    /** Every file [voice] needs, in load order. */
    fun requiredFor(voice: KokoroVoice): List<KernelModel> = listOf(
        KernelModel.KOKORO_DURATION,
        KernelModel.KOKORO_SYNTHESIZER,
        lexiconFor(voice.locale),
        KernelModel.KOKORO_TAGS,
        voiceFor(voice),
    )

    /** Every file the shipped voices need, each once: the on-demand Kokoro pack. */
    fun allFiles(): List<KernelModel> = KokoroVoice.entries.flatMap(::requiredFor).distinct()

    /** The first required file that is missing or empty, or null when [voice] is ready. */
    fun firstMissing(context: Context, voice: KokoroVoice): KernelModel? =
        requiredFor(voice).firstOrNull { model -> !model.localFile(context).let { it.isFile && it.length() > 0L } }

    fun lexiconFile(context: Context, voice: KokoroVoice): File = lexiconFor(voice.locale).localFile(context)

    fun tagsFile(context: Context): File = KernelModel.KOKORO_TAGS.localFile(context)

    fun durationFile(context: Context): File = KernelModel.KOKORO_DURATION.localFile(context)

    fun synthesizerFile(context: Context): File = KernelModel.KOKORO_SYNTHESIZER.localFile(context)

    fun voiceFile(context: Context, voice: KokoroVoice): File = voiceFor(voice).localFile(context)

    private fun lexiconFor(locale: String): KernelModel = when (locale) {
        LOCALE_EN_US -> KernelModel.KOKORO_LEXICON_EN_US
        LOCALE_EN_GB -> KernelModel.KOKORO_LEXICON_EN_GB
        else -> error("No Kokoro lexicon for $locale")
    }

    private fun voiceFor(voice: KokoroVoice): KernelModel = when (voice) {
        KokoroVoice.EMMA -> KernelModel.KOKORO_VOICE_BF_EMMA
        KokoroVoice.DANIEL -> KernelModel.KOKORO_VOICE_BM_DANIEL
        KokoroVoice.HEART -> KernelModel.KOKORO_VOICE_AF_HEART
    }
}
