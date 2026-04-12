package com.kernel.ai.core.inference.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloadWorker"
private const val NOTIFICATION_CHANNEL_ID = "kernel_model_download"
private const val TMP_SUFFIX = ".tmp"

// WorkManager Data keys (used by ModelDownloadManager as well)
const val KEY_DOWNLOAD_URL = "download_url"
const val KEY_FILE_NAME = "file_name"
const val KEY_MODEL_DISPLAY_NAME = "model_display_name"
const val KEY_TOTAL_BYTES = "total_bytes"
const val KEY_PROGRESS_BYTES = "progress_bytes"
const val KEY_DOWNLOAD_RATE = "download_rate_bps"
const val KEY_REMAINING_MS = "remaining_ms"
const val KEY_ERROR_MESSAGE = "error_message"
/** Optional HuggingFace Bearer token — present when downloading a gated model. */
const val KEY_HF_ACCESS_TOKEN = "hf_access_token"

/**
 * WorkManager [CoroutineWorker] that downloads a single `.litertlm` model file.
 *
 * Features:
 * - Foreground service with a progress notification (required for long-running downloads)
 * - HTTP Range requests to resume interrupted downloads from a `.tmp` file
 * - Progress reporting every 200ms via [setProgress] for UI consumption
 *
 * Input data keys: [KEY_DOWNLOAD_URL], [KEY_FILE_NAME], [KEY_MODEL_DISPLAY_NAME], [KEY_TOTAL_BYTES]
 * Output on failure: [KEY_ERROR_MESSAGE]
 * Progress keys: [KEY_PROGRESS_BYTES], [KEY_DOWNLOAD_RATE], [KEY_REMAINING_MS]
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notificationId = params.id.hashCode()

    init {
        ensureNotificationChannel()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
            ?: return@withContext Result.failure(errorData("Missing download URL"))
        val fileName = inputData.getString(KEY_FILE_NAME)
            ?: return@withContext Result.failure(errorData("Missing file name"))
        val displayName = inputData.getString(KEY_MODEL_DISPLAY_NAME) ?: fileName
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val hfAccessToken = inputData.getString(KEY_HF_ACCESS_TOKEN)

        val modelsDir = (applicationContext.getExternalFilesDir("models")
            ?: File(applicationContext.filesDir, "models")).also { it.mkdirs() }
        val outputFile = File(modelsDir, fileName)
        val tmpFile = File(modelsDir, "$fileName$TMP_SUFFIX")

        // Show initial foreground notification (best-effort — may fail if app is backgrounded
        // before notification permission is granted; download continues regardless)
        trySetForeground(buildForegroundInfo(displayName, 0))

        try {
            downloadFile(
                url = downloadUrl,
                tmpFile = tmpFile,
                outputFile = outputFile,
                totalBytes = totalBytes,
                displayName = displayName,
                hfAccessToken = hfAccessToken,
            )
            Log.i(TAG, "Download complete: ${outputFile.absolutePath}")
            Result.success()
        } catch (e: IOException) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            Result.failure(errorData(e.message ?: "Unknown I/O error"))
        }
    }

    private suspend fun downloadFile(
        url: String,
        tmpFile: File,
        outputFile: File,
        totalBytes: Long,
        displayName: String,
        hfAccessToken: String? = null,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection

        // Attach Bearer token for gated HuggingFace models
        if (!hfAccessToken.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $hfAccessToken")
        }

        // Resume from partial download if tmp file exists
        val resumeFrom = tmpFile.length()
        if (resumeFrom > 0) {
            Log.i(TAG, "Resuming from byte $resumeFrom for $displayName")
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            // Disable compression so Range requests work correctly
            connection.setRequestProperty("Accept-Encoding", "identity")
        }
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
            throw IOException("HTTP $responseCode for $url")
        }

        val startedAt = resumeFrom
        var downloadedBytes = startedAt

        // Sliding window for rate calculation (last 5 intervals of ~200ms each)
        val sizeWindow = ArrayDeque<Long>(5)
        val latencyWindow = ArrayDeque<Long>(5)

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var lastProgressTs = 0L
        var deltaBytes = 0L

        connection.inputStream.use { input ->
            FileOutputStream(tmpFile, /* append */ true).use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    deltaBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (lastProgressTs == 0L || now - lastProgressTs >= 200) {
                        // Update sliding window
                        var bps = 0L
                        if (lastProgressTs != 0L) {
                            if (sizeWindow.size == 5) sizeWindow.removeFirst()
                            if (latencyWindow.size == 5) latencyWindow.removeFirst()
                            sizeWindow.addLast(deltaBytes)
                            latencyWindow.addLast(now - lastProgressTs)
                            deltaBytes = 0L
                            val totalMs = latencyWindow.sum()
                            if (totalMs > 0) bps = sizeWindow.sum() * 1000L / totalMs
                        }

                        val remainingMs = if (bps > 0 && totalBytes > 0) {
                            (totalBytes - downloadedBytes) * 1000L / bps
                        } else 0L

                        val progressFraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        val progressPct = (progressFraction * 100).toInt()

                        setProgress(
                            Data.Builder()
                                .putLong(KEY_PROGRESS_BYTES, downloadedBytes)
                                .putLong(KEY_DOWNLOAD_RATE, bps)
                                .putLong(KEY_REMAINING_MS, remainingMs)
                                .build()
                        )
                        trySetForeground(buildForegroundInfo(displayName, progressPct))
                        lastProgressTs = now
                    }
                }
            }
        }

        // Rename tmp → final file atomically
        if (outputFile.exists()) outputFile.delete()
        if (!tmpFile.renameTo(outputFile)) {
            throw IOException("Failed to rename temp file to ${outputFile.name}")
        }
    }

    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote to foreground service (notification permission may be missing): ${e.message}")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo("Model", 0)

    private fun buildForegroundInfo(displayName: String, progressPct: Int): ForegroundInfo {
        val title = "Downloading $displayName"
        val text = if (progressPct > 0) "$progressPct% complete" else "Starting download…"

        // Deep-link back to app when tapping the notification
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat
            .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progressPct, progressPct == 0)
            .setContentIntent(pendingIntent)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureNotificationChannel() {
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress notifications for on-device model downloads" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_ERROR_MESSAGE, message).build()
}
