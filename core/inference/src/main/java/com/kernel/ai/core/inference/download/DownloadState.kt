package com.kernel.ai.core.inference.download

/** Represents the current download state of a [KernelModel]. */
sealed class DownloadState {

    /** Model file does not exist locally. */
    data object NotDownloaded : DownloadState()

    /**
     * Download is queued or running.
     *
     * @param progress 0.0–1.0 completion fraction.
     * @param downloadedBytes Bytes received so far.
     * @param bytesPerSecond Current download rate; 0 if not yet measured.
     * @param remainingMs Estimated milliseconds to completion; 0 if unknown.
     */
    data class Downloading(
        val progress: Float = 0f,
        val downloadedBytes: Long = 0L,
        val bytesPerSecond: Long = 0L,
        val remainingMs: Long = 0L,
    ) : DownloadState()

    /**
     * Model is fully downloaded and ready to use.
     *
     * @param localPath Absolute path to the `.litertlm` file.
     */
    data class Downloaded(val localPath: String) : DownloadState()

    /**
     * Download failed.
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : DownloadState()
}
