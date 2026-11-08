package com.kernel.ai.core.voice

/** Represents the current download state of a [SherpaPiperVoice] pack. */
sealed class VoicePackDownloadState {

    /** Voice pack is not present on-device. */
    data object NotDownloaded : VoicePackDownloadState()

    /**
     * Download or extraction is in progress.
     *
     * @param progress 0.0–1.0 completion fraction (download + extraction combined).
     * @param downloadedBytes Bytes received so far (download phase).
     * @param bytesPerSecond Current download rate; 0 if not yet measured.
     * @param remainingMs Estimated milliseconds to completion; 0 if unknown.
     */
    data class Downloading(
        val progress: Float = 0f,
        val downloadedBytes: Long = 0L,
        val bytesPerSecond: Long = 0L,
        val remainingMs: Long = 0L,
    ) : VoicePackDownloadState()

    /**
     * Voice pack is fully extracted and ready to use.
     *
     * @param localDir Absolute path to the extracted voice directory.
     */
    data class Downloaded(val localDir: String) : VoicePackDownloadState()

    /**
     * Download or extraction failed.
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : VoicePackDownloadState()
}
