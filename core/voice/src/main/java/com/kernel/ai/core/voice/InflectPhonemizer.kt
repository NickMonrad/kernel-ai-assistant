package com.kernel.ai.core.voice

import java.io.File

/**
 * Narrow bridge to the custom Sherpa-ONNX v1.13.0 AAR frontend seam.
 *
 * The native implementation reuses Sherpa's initialized Piper/eSpeak payload and its existing
 * CallPhonemizeEspeak() serialization path. It returns punctuation-bearing IPA; Inflect owns
 * symbol validation and token interleaving on the Kotlin side.
 */
object InflectPhonemizer {
    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loadError: Throwable? = null

    fun phonemize(text: String, dataDirectory: File): String {
        require(text.isNotBlank()) { "Text must not be blank" }
        require(dataDirectory.isDirectory) {
            "Missing Sherpa eSpeak data directory: ${dataDirectory.path}"
        }
        ensureLoaded()
        return try {
            nativePhonemize(text, dataDirectory.absolutePath)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "Custom Sherpa Inflect phonemizer is unavailable; build the Inflect AAR",
                error,
            )
        }
    }

    fun availability(dataDirectory: File): String? {
        if (!dataDirectory.isDirectory) return "Sherpa eSpeak data is not downloaded."
        return try {
            phonemize("test", dataDirectory)
            null
        } catch (error: Exception) {
            error.message ?: "Inflect phonemizer unavailable."
        } catch (error: UnsatisfiedLinkError) {
            error.message ?: "Inflect phonemizer native symbol unavailable."
        }
    }

    private fun ensureLoaded() {
        if (loadAttempted) {
            loadError?.let { throw IllegalStateException("Sherpa JNI unavailable", it) }
            return
        }
        synchronized(this) {
            if (loadAttempted) {
                loadError?.let { throw IllegalStateException("Sherpa JNI unavailable", it) }
                return
            }
            try {
                // The no-ORT Sherpa AAR still links against the app's existing ORT shared library.
                // Load it first so a direct Inflect call has the same dependency order as the
                // reflected Sherpa TTS controller.
                System.loadLibrary("onnxruntime")
                System.loadLibrary("sherpa-onnx-jni")
            } catch (error: UnsatisfiedLinkError) {
                loadError = error
            } finally {
                loadAttempted = true
            }
            loadError?.let { throw IllegalStateException("Sherpa JNI unavailable", it) }
        }
    }

    @JvmStatic
    private external fun nativePhonemize(text: String, dataDirectory: String): String
}
