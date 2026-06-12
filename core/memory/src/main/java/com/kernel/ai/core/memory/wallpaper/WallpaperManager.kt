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
     * image, and writes a copy to `files/chat_wallpapers/`.
     *
     * @return [Result.success] with the absolute file path, or [Result.failure] with the error.
     */
    suspend fun importWallpaper(sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate the source is a readable image
            val validateResult = validateImageUri(sourceUri)
            if (validateResult.isFailure) {
                return@withContext Result.failure(validateResult.exceptionOrNull()!!)
            }

            // Read the source bytes
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                stream.readBytes()
            } ?: return@withContext Result.failure(
                FileNotFoundException("Cannot open $sourceUri"),
            )

            // Generate a unique filename
            val ext = guessExtension(bytes) ?: "jpg"
            val filename = "wallpaper_${System.currentTimeMillis()}_${sourceUri.hashCode()}.$ext"
            val destFile = File(wallpaperDir, filename)

            destFile.outputStream().use { output ->
                output.write(bytes)
            }

            Result.success(destFile.absolutePath)
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
     */
    private fun validateImageUri(uri: Uri): Result<Unit> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    return Result.failure(IllegalArgumentException("URI does not point to a valid image"))
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
     * Guess a file extension from image magic bytes.
     */
    private fun guessExtension(bytes: ByteArray): String? {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
            bytes.size >= 4 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() -> "webp"
            else -> null
        }
    }

    private companion object {
        private const val WALLPAPER_DIR = "chat_wallpapers"
    }
}
