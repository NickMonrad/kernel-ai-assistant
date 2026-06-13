package com.kernel.ai.core.memory.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages importing, storing, and cleaning up chat wallpaper images in app-private storage.
 *
 * Wallpapers are stored under `files/chat_wallpapers/` to decouple wallpaper longevity from
 * external content-provider URI permissions. The active wallpaper is referenced as an absolute
 * file path in DataStore preferences.
 */
@Singleton
class WallpaperManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val wallpaperDir: File
        get() = File(context.filesDir, WALLPAPER_DIR).also { it.mkdirs() }

    /**
     * Import a wallpaper from an external [sourceUri] into app-private storage.
     *
     * Opens the URI via [ContentResolver.openInputStream], validates the content is a decodable
     * image, and writes a copy to `files/chat_wallpapers/` using bounded streaming copy.
     *
     * @return [Result.success] with the absolute file path, or [Result.failure] with the error.
     */
    suspend fun importWallpaper(sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate the source is a readable image (bounds-only decode — no pixel data loaded)
            val validateResult = validateImageUri(sourceUri)
            if (validateResult.isFailure) {
                return@withContext Result.failure(validateResult.exceptionOrNull()!!)
            }

            val timestamp = System.currentTimeMillis()
            val hash = sourceUri.hashCode()
            val tempFile = File(wallpaperDir, "wallpaper_${timestamp}_${hash}.tmp")

            try {
                // Stream-copy with bounded cap
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: return@withContext Result.failure(
                        FileNotFoundException("Cannot open $sourceUri"),
                    )

                input.use { stream ->
                    tempFile.outputStream().use { output ->
                        copyBounded(stream, output, MAX_WALLPAPER_IMPORT_BYTES)
                    }
                }

                // Validate the copied file is a fully delivered valid image
                val fileValidate = validateImageFile(tempFile)
                if (fileValidate.isFailure) {
                    tempFile.delete()
                    return@withContext Result.failure(fileValidate.exceptionOrNull()!!)
                }

                // Guess extension from magic bytes of the copied file
                val ext = guessExtensionFromFile(tempFile) ?: "jpg"
                val destFile = File(wallpaperDir, "wallpaper_${timestamp}_${hash}.$ext")
                if (!tempFile.renameTo(destFile)) {
                    // Fallback: temp path used as-is (rare on same filesystem)
                    return@withContext Result.success(tempFile.absolutePath)
                }

                Result.success(destFile.absolutePath)
            } catch (e: Exception) {
                // Delete any partial file on failure
                if (tempFile.exists()) tempFile.delete()
                throw e
            }
        } catch (e: WallpaperImportTooLargeException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: FileNotFoundException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Delete a single wallpaper file identified by its absolute path.
     * Silently ignores missing files or non-wallpaper-directory paths.
     */
    fun deleteWallpaper(filePath: String) {
        val file = File(filePath)
        if (file.isFile && file.parentFile?.absolutePath == wallpaperDir.absolutePath) {
            file.delete()
        }
    }

    /**
     * Delete all imported wallpaper files that are not in [activePaths].
     *
     * Only deletes files under the app-owned [wallpaperDir]. Never touches external files.
     */
    fun deleteUnusedWallpapers(activePaths: Set<String>) {
        wallpaperDir.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in activePaths) {
                file.delete()
            }
        }
    }

    /**
     * Returns all imported wallpaper files sorted by modification time (most recent first).
     */
    fun getImportedWallpapers(): List<File> {
        return wallpaperDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Count of wallpaper files currently in app-private storage.
     */
    fun getWallpaperCount(): Int = wallpaperDir.listFiles()?.count { it.isFile } ?: 0

    /**
     * Validate that [uri] points to a decodable image.
     * Uses bounds-only decoding — no pixel data is loaded into memory.
     */
    private fun validateImageUri(uri: Uri): Result<Unit> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    return Result.failure(
                        IllegalArgumentException("URI does not point to a valid image"),
                    )
                }
            } ?: return Result.failure(FileNotFoundException("Cannot open $uri"))
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: FileNotFoundException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Validate that [file] is a decodable image using bounds-only decoding.
     */
    private fun validateImageFile(file: File): Result<Unit> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return Result.failure(
                    IllegalArgumentException("Imported file is not a valid image"),
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Guess a file extension from image magic bytes read from [file].
     */
    private fun guessExtensionFromFile(file: File): String? {
        return try {
            val bytes = ByteArray(16)
            file.inputStream().use { stream ->
                val read = stream.read(bytes)
                if (read < 2) return null
                guessExtension(bytes, read)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Guess a file extension from the first [len] bytes of image data.
     */
    private fun guessExtension(bytes: ByteArray, len: Int = bytes.size): String? {
        return when {
            len >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            len >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
            len >= 4 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() -> "webp"
            else -> null
        }
    }

    /**
     * Bounded streaming copy from [input] to [output].
     *
     * @throws WallpaperImportTooLargeException if data exceeds [maxBytes].
     * @return total bytes copied.
     */
    @Throws(WallpaperImportTooLargeException::class, IOException::class)
    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw WallpaperImportTooLargeException(maxBytes)
            }
            output.write(buffer, 0, read)
        }
        return total
    }

    /** Thrown when a wallpaper import exceeds the maximum allowed file size. */
    private class WallpaperImportTooLargeException(maxBytes: Long) :
        IOException("Image exceeds maximum import size of ${maxBytes / (1024 * 1024)} MB")

    private companion object {
        private const val WALLPAPER_DIR = "chat_wallpapers"
        /** Maximum allowed size for imported wallpaper files: 25 MB. */
        private const val MAX_WALLPAPER_IMPORT_BYTES = 25L * 1024L * 1024L
        /** Transfer buffer size for streaming copy operations. */
        private const val DEFAULT_BUFFER_SIZE = 8192
    }
}
