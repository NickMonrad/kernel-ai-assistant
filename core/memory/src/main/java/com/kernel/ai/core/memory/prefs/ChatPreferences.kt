package com.kernel.ai.core.memory.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chatPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_preferences")

/**
 * DataStore for chat-related user preferences.
 *
 * [archiveRetentionDays]: How many days to keep archived conversations before auto-deletion.
 *  - Positive values: number of days (1, 3, 7, 14, 30)
 *  - -1: Never auto-delete
 *  - Default: 7 days
 *
 * [fontSize]: 0 = small, 1 = medium (default), 2 = large
 * [bubbleTheme]: preset name, "system" (default), "ocean", "forest", "sunset", "mono"
 * [userFontColor]: ARGB color for user bubble text; null = system default
 * [assistantFontColor]: ARGB color for assistant bubble text; null = system default
 * [wallpaperType]: "none" (default), "color", "image"
 * [wallpaperColor]: ARGB color when type=color; null = none
 * [wallpaperImageUri]: content URI string when type=image; null = none
 */
@Singleton
class ChatPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ---- Archive ----
    private val archiveRetentionDaysKey = intPreferencesKey("archive_retention_days")

    val archiveRetentionDays: Flow<Int> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[archiveRetentionDaysKey] ?: 7 }

    suspend fun setArchiveRetentionDays(days: Int) {
        context.chatPrefsDataStore.edit { prefs ->
            prefs[archiveRetentionDaysKey] = days
        }
    }

    // ---- Font size ----
    private val fontSizeKey = intPreferencesKey("font_size")

    /** 0=small, 1=medium, 2=large. Default: 1 (medium). */
    val fontSize: Flow<Int> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[fontSizeKey] ?: 1 }

    suspend fun setFontSize(size: Int) {
        context.chatPrefsDataStore.edit { prefs ->
            prefs[fontSizeKey] = size.coerceIn(0, 2)
        }
    }

    // ---- Bubble theme ----
    private val bubbleThemeKey = stringPreferencesKey("bubble_theme")

    /** Preset name, e.g. "system", "ocean", "forest", "sunset", "mono". Default: "system". */
    val bubbleTheme: Flow<String> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[bubbleThemeKey] ?: "system" }

    suspend fun setBubbleTheme(theme: String) {
        context.chatPrefsDataStore.edit { prefs ->
            prefs[bubbleThemeKey] = theme
        }
    }

    // ---- Font colours (per bubble side) ----
    private val userFontColorKey = longPreferencesKey("user_font_color")
    private val assistantFontColorKey = longPreferencesKey("assistant_font_color")

    /** ARGB color for user bubble text; null = inherit from theme. */
    val userFontColor: Flow<Long?> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[userFontColorKey] }

    suspend fun setUserFontColor(color: Long?) {
        context.chatPrefsDataStore.edit { prefs ->
            if (color != null) prefs[userFontColorKey] = color
            else prefs.remove(userFontColorKey)
        }
    }

    /** ARGB color for assistant bubble text; null = inherit from theme. */
    val assistantFontColor: Flow<Long?> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[assistantFontColorKey] }

    suspend fun setAssistantFontColor(color: Long?) {
        context.chatPrefsDataStore.edit { prefs ->
            if (color != null) prefs[assistantFontColorKey] = color
            else prefs.remove(assistantFontColorKey)
        }
    }

    // ---- Wallpaper ----
    private val wallpaperTypeKey = stringPreferencesKey("wallpaper_type")
    private val wallpaperColorKey = longPreferencesKey("wallpaper_color")
    private val wallpaperImageUriKey = stringPreferencesKey("wallpaper_image_uri")

    /** "none" (default), "color", "image". */
    val wallpaperType: Flow<String> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[wallpaperTypeKey] ?: "none" }

    suspend fun setWallpaperType(type: String) {
        context.chatPrefsDataStore.edit { prefs ->
            prefs[wallpaperTypeKey] = type
        }
    }

    /** ARGB color when wallpaperType="color"; null = none. */
    val wallpaperColor: Flow<Long?> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[wallpaperColorKey] }

    suspend fun setWallpaperColor(color: Long?) {
        context.chatPrefsDataStore.edit { prefs ->
            if (color != null) prefs[wallpaperColorKey] = color
            else prefs.remove(wallpaperColorKey)
        }
    }

    /** Content URI string when wallpaperType="image"; null = none. */
    val wallpaperImageUri: Flow<String?> = context.chatPrefsDataStore.data
        .map { prefs -> prefs[wallpaperImageUriKey] }

    suspend fun setWallpaperImageUri(uri: String?) {
        context.chatPrefsDataStore.edit { prefs ->
            if (uri != null) prefs[wallpaperImageUriKey] = uri
            else prefs.remove(wallpaperImageUriKey)
        }
    }
}