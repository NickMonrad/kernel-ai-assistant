package com.kernel.ai.core.voice.kokoro

import java.io.File

/** phonemis (MIT) through the `kokoro_phonemis` JNI library, one handle per English locale. */
internal class NativeKokoroPhonemizer(
    locale: String,
    lexiconFile: File,
    taggerFile: File,
) : KokoroPhonemizer {
    private var nativeHandle: Long

    init {
        require(locale == LOCALE_EN_US || locale == LOCALE_EN_GB) { "Supported locales are en-us and en-gb" }
        nativeHandle = nativeCreate(locale, lexiconFile.absolutePath, taggerFile.absolutePath)
        check(nativeHandle != 0L) { "Native phonemis initialization failed" }
    }

    @Synchronized
    override fun phonemize(text: String): PhonemizedText {
        check(nativeHandle != 0L) { "Phonemizer is closed" }
        return nativePhonemize(nativeHandle, text)
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
    private external fun nativePhonemize(handle: Long, text: String): PhonemizedText
    private external fun nativeClose(handle: Long)

    companion object {
        const val LOCALE_EN_US = "en-us"
        const val LOCALE_EN_GB = "en-gb"

        init {
            System.loadLibrary("kokoro_phonemis")
        }
    }
}
