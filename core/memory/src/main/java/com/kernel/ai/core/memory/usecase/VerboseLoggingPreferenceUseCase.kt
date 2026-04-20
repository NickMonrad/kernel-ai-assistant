package com.kernel.ai.core.memory.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.kernel.ai.core.memory.rag.RagRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named

/**
 * Loads and applies the verbose logging preference from DataStore.
 * Called once at app startup to initialize RagRepository's verbose logging flag.
 *
 * Injects the DataStore from AboutPreferencesModule to avoid multiple active
 * DataStore instances on the same file (which causes IllegalStateException).
 */
class VerboseLoggingPreferenceUseCase @Inject constructor(
    @Named("about") private val dataStore: DataStore<Preferences>,
    private val ragRepository: RagRepository,
) {

    suspend fun loadAndApplyVerboseLoggingPreference() {
        try {
            val keyVerboseLogging = booleanPreferencesKey("verbose_logging")
            val enabled = dataStore.data
                .first()
                .get(keyVerboseLogging) ?: false
            ragRepository.setVerboseLogging(enabled)
        } catch (e: Exception) {
            // Silently fail — verbose logging is optional for debugging
            android.util.Log.d("VerboseLoggingPreferenceUseCase", "Failed to load preference: ${e.message}")
        }
    }
}
