package com.kernel.ai.core.memory.usecase

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kernel.ai.core.memory.rag.RagRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Loads and applies the verbose logging preference from DataStore.
 * Called once at app startup to initialize RagRepository's verbose logging flag.
 */
class VerboseLoggingPreferenceUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ragRepository: RagRepository,
) {
    private val Context.preferencesDataStore by preferencesDataStore(name = "about_prefs")

    suspend fun loadAndApplyVerboseLoggingPreference() {
        try {
            val keyVerboseLogging = booleanPreferencesKey("verbose_logging")
            val enabled = context.preferencesDataStore.data
                .first()
                .get(keyVerboseLogging) ?: false
            ragRepository.setVerboseLogging(enabled)
        } catch (e: Exception) {
            // Silently fail — verbose logging is optional for debugging
            android.util.Log.d("VerboseLoggingPreferenceUseCase", "Failed to load preference: ${e.message}")
        }
    }
}
