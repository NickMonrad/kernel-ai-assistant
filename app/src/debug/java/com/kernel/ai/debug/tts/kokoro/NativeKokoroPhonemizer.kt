package com.kernel.ai.debug.tts.kokoro

import java.io.File

/** Debug-only phonemis adapter. Data files are pushed to filesDir/kokoro-et/phonemizer/<locale>/. */
internal class NativePhonemizedText(
    val phonemes: String,
    val words: LongArray,
)
class NativeKokoroPhonemizer(
    filesDir: File,
    private val locale: String,
) : KokoroPhonemizer {
    private var nativeHandle: Long

    init {
        require(locale == "en-us" || locale == "en-gb") { "Supported locales are en-us and en-gb" }
        val localeDirectory = File(File(File(filesDir, "kokoro-et"), "phonemizer"), locale)
        nativeHandle = nativeCreate(
            locale,
            File(localeDirectory, "lexicon.json").absolutePath,
            File(localeDirectory, "tags.json").absolutePath,
        )
        check(nativeHandle != 0L) { "Native phonemis initialization failed" }
    }

    @Synchronized
    override fun phonemize(text: String): PhonemizedText {
        check(nativeHandle != 0L) { "Phonemizer is closed" }
        val nativeResult = nativePhonemize(nativeHandle, text)
        val phonemes = nativeResult.phonemes
        val words = ArrayList<PhonemizedWord>(nativeResult.words.size / 4)
        var index = 0
        while (index < nativeResult.words.size) {
            val inputStart = text.offsetByCodePoints(0, nativeResult.words[index].toInt())
            val inputEnd = text.offsetByCodePoints(
                inputStart,
                nativeResult.words[index + 1].toInt(),
            )
            val phonemeStart = phonemes.offsetByCodePoints(
                0,
                nativeResult.words[index + 2].toInt(),
            )
            val phonemeEnd = phonemes.offsetByCodePoints(
                phonemeStart,
                nativeResult.words[index + 3].toInt(),
            )
            words += PhonemizedWord(
                text = text.substring(inputStart, inputEnd),
                offset = inputStart,
                phonemeOffset = phonemeStart,
                phonemeLength = phonemeEnd - phonemeStart,
            )
            index += 4
        }
        return PhonemizedText(phonemes, words)
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeClose(handle)
        }
    }

    private external fun nativeCreate(locale: String, lexiconPath: String, taggerPath: String): Long
    private external fun nativePhonemize(handle: Long, text: String): NativePhonemizedText
    private external fun nativeClose(handle: Long)


    companion object {
        init {
            System.loadLibrary("kokoro_phonemis")
        }
    }
}
