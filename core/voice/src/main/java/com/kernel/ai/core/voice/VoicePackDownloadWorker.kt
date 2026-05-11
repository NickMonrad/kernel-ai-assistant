package com.kernel.ai.core.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "KernelAI"
private const val NOTIFICATION_CHANNEL_ID = "kernel_voice_download"
private const val TMP_SUFFIX = ".tmp"

// WorkManager Data keys
const val KEY_VOICE_NAME = "voice_name"
const val KEY_VOICE_DISPLAY_NAME = "voice_display_name"
const val KEY_VOICE_DOWNLOAD_URL = "voice_download_url"
const val KEY_VOICE_TOTAL_BYTES = "voice_total_bytes"
const val KEY_VOICE_PROGRESS_BYTES = "voice_progress_bytes"
const val KEY_VOICE_DOWNLOAD_RATE = "voice_download_rate_bps"
const val KEY_VOICE_REMAINING_MS = "voice_remaining_ms"
const val KEY_VOICE_ERROR_MESSAGE = "voice_error_message"

/**
 * WorkManager [CoroutineWorker] that downloads and extracts a Sherpa-ONNX Piper voice pack.
 *
 * The pack is a `.tar.bz2` archive hosted on the sherpa-onnx GitHub releases page.  After
 * extraction the voice directory is available at [Context.getFilesDir]/sherpa-tts/<name>/.
 *
 * Progress is reported in two phases:
 *  - Download: 0 → 90% of the total progress bar.
 *  - Extraction: 90 → 100%.
 *
 * Input keys: [KEY_VOICE_NAME], [KEY_VOICE_DISPLAY_NAME], [KEY_VOICE_DOWNLOAD_URL],
 *   [KEY_VOICE_TOTAL_BYTES].
 * Progress keys: [KEY_VOICE_PROGRESS_BYTES], [KEY_VOICE_DOWNLOAD_RATE], [KEY_VOICE_REMAINING_MS].
 * Output on failure: [KEY_VOICE_ERROR_MESSAGE].
 */
class VoicePackDownloadWorker(
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
        val voiceName = inputData.getString(KEY_VOICE_NAME)
            ?: return@withContext Result.failure(errorData("Missing voice name"))
        val displayName = inputData.getString(KEY_VOICE_DISPLAY_NAME) ?: voiceName
        val downloadUrl = inputData.getString(KEY_VOICE_DOWNLOAD_URL)
            ?: return@withContext Result.failure(errorData("Missing download URL"))
        val totalBytes = inputData.getLong(KEY_VOICE_TOTAL_BYTES, 0L)

        val piperVoice = SherpaPiperVoice.entries.firstOrNull { it.name == voiceName }
        val kokoroVoice = if (piperVoice == null) SherpaKokoroVoice.entries.firstOrNull { it.name == voiceName } else null

        if (piperVoice == null && kokoroVoice == null) {
            return@withContext Result.failure(errorData("Unknown voice: $voiceName"))
        }

        val destDir: File
        val validationFn: (File) -> Boolean
        if (kokoroVoice != null) {
            destDir = kokoroVoice.voiceDir(applicationContext)
            validationFn = ::hasRequiredKokoroVoicePackFiles
        } else {
            destDir = piperVoice!!.voiceDir(applicationContext)
            validationFn = ::hasRequiredSherpaVoicePackFiles
        }
        val cacheDir = File(applicationContext.cacheDir, "sherpa-pack").also { it.mkdirs() }
        val tmpFile = File(cacheDir, "$voiceName.tar.bz2$TMP_SUFFIX")

        trySetForeground(buildForegroundInfo(displayName, 0))

        return@withContext try {
            // Phase 1: Download (0–90%)
            downloadFile(
                url = downloadUrl,
                tmpFile = tmpFile,
                totalBytes = totalBytes,
                displayName = displayName,
            )

            if (!coroutineContext.isActive) {
                tmpFile.delete()
                return@withContext Result.failure(errorData("Cancelled during download"))
            }

            // Phase 2: Extract (90–100%)
            trySetForeground(buildForegroundInfo(displayName, 90))
            extractTarBz2(tmpFile, destDir)
            tmpFile.delete()

            if (!validationFn(destDir)) {
                Log.e(TAG, "Extraction appeared to succeed but required files are missing for $voiceName")
                return@withContext Result.failure(errorData("Extraction incomplete — required files missing"))
            }

            Log.i(TAG, "Voice pack ready: ${destDir.absolutePath}")
            Result.success()
        } catch (e: IOException) {
            Log.e(TAG, "Voice pack download/extraction failed for $voiceName", e)
            tmpFile.delete()
            Result.failure(errorData(e.message ?: "Unknown I/O error"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure for voice $voiceName", e)
            tmpFile.delete()
            Result.failure(errorData(e.message ?: "Unexpected error"))
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    private suspend fun downloadFile(
        url: String,
        tmpFile: File,
        totalBytes: Long,
        displayName: String,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection

        // Resume from partial download if tmp file already has data
        val resumeFrom = tmpFile.length()
        if (resumeFrom > 0) {
            Log.i(TAG, "Resuming voice pack download from byte $resumeFrom for $displayName")
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            connection.setRequestProperty("Accept-Encoding", "identity")
        }
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
            throw IOException("HTTP $responseCode for $url")
        }

        var downloadedBytes = resumeFrom
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

                        // Map download progress to 0–90% of total
                        val dlFraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        val overallPct = (dlFraction * 90).toInt()
                        setProgress(
                            Data.Builder()
                                .putLong(KEY_VOICE_PROGRESS_BYTES, downloadedBytes)
                                .putLong(KEY_VOICE_DOWNLOAD_RATE, bps)
                                .putLong(KEY_VOICE_REMAINING_MS, remainingMs)
                                .build()
                        )
                        trySetForeground(buildForegroundInfo(displayName, overallPct))
                        lastProgressTs = now
                    }
                }
            }
        }
    }

    // ── Extraction ───────────────────────────────────────────────────────────

    /**
     * Extracts [tarBz2] into [destDir].
     *
     * The tarball typically contains a top-level directory matching [SherpaPiperVoice.assetDirectoryName].
     * We strip this single top-level component so the contents land directly in [destDir]:
     *   `vits-piper-en_GB-jenny_dioco-medium/model.onnx` → `destDir/model.onnx`
     */
    private fun extractTarBz2(tarBz2: File, destDir: File) {
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        destDir.mkdirs()
        tarBz2.inputStream().buffered().use { fis ->
            BZip2CompressorInputStream(fis).use { bzip2 ->
                TarArchiveInputStream(bzip2).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!tar.canReadEntryData(entry)) {
                            entry = tar.nextEntry
                            continue
                        }
                        // Strip leading top-level directory component
                        val entryPath = entry.name.removePrefix("./")
                        val pathParts = entryPath.split("/").filter { it.isNotEmpty() }
                        if (pathParts.isEmpty()) {
                            entry = tar.nextEntry
                            continue
                        }
                        // Drop the first component (e.g. "vits-piper-en_GB-jenny_dioco-medium")
                        val strippedParts = if (pathParts.size > 1) pathParts.drop(1) else pathParts
                        val relativePath = strippedParts.joinToString(File.separator)

                        val outFile = File(destDir, relativePath)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
        normalizeExtractedSherpaVoicePack(destDir)
    }

    // ── Foreground / notification ─────────────────────────────────────────────

    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground for voice download: ${e.message}")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo("Voice pack", 0)

    private fun buildForegroundInfo(displayName: String, progressPct: Int): ForegroundInfo {
        val title = "Downloading $displayName voice"
        val text = when {
            progressPct >= 90 -> "Extracting…"
            progressPct > 0 -> "$progressPct% complete"
            else -> "Starting download…"
        }

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
            "Voice Pack Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress notifications for Sherpa Piper voice pack downloads" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_VOICE_ERROR_MESSAGE, message).build()
}
