package com.kernel.ai.core.inference.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kernel.ai.core.inference.download.KernelModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"
private val Context.modelPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_preferences")

open class ModelPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val preferredModelKey = stringPreferencesKey("preferred_conversation_model")
    private val suppressedOptionalModelIdsKey = stringSetPreferencesKey("suppressed_optional_model_ids")

    /**
     * The DataStore instance. Exposed as an internal property so test subclasses
     * can override it with an in-memory DataStore without changing the DI setup.
     */
    internal val dataStore: DataStore<Preferences> = context.modelPrefsDataStore

    /** Null means "auto" — let tier-based logic decide. */
    val preferredConversationModel: Flow<KernelModel?> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "ModelPreferences: DataStore read error, falling back to auto", e)
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else throw e
        }
        .map { prefs ->
            prefs[preferredModelKey]?.let { name ->
                KernelModel.entries.find { it.name == name }
                    .also { if (it == null) Log.w(TAG, "ModelPreferences: unknown model key '$name', ignoring") }
            }
        }

    /**
     * Set of model name strings that the user has manually deleted/suppressed.
     * Used to prevent auto-queue of tier-preferred optional models on app restart.
     */
    val suppressedOptionalModelIds: Flow<Set<String>> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "ModelPreferences: DataStore read error, falling back to empty suppressed set", e)
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else throw e
        }
        .map { prefs -> prefs[suppressedOptionalModelIdsKey] ?: emptySet() }

    /** Returns true if [model] is in the suppressed-optional set. */
    suspend fun isOptionalModelSuppressed(model: KernelModel): Boolean {
        return dataStore.data.first()[suppressedOptionalModelIdsKey]?.contains(model.name) == true
    }

    /** Record that the user has manually deleted [model]. Only meaningful for optional models. */
    suspend fun suppressOptionalModel(model: KernelModel) {
        try {
            dataStore.edit { prefs ->
                val current = prefs[suppressedOptionalModelIdsKey] ?: emptySet()
                prefs[suppressedOptionalModelIdsKey] = current + model.name
            }
            Log.i(TAG, "ModelPreferences: suppressed optional model ${model.displayName}")
        } catch (e: IOException) {
            Log.e(TAG, "ModelPreferences: failed to suppress model", e)
        }
    }

    /** Remove [model] from the suppressed set — called when the user manually starts a download. */
    suspend fun unsuppressOptionalModel(model: KernelModel) {
        try {
            dataStore.edit { prefs ->
                val current = prefs[suppressedOptionalModelIdsKey] ?: emptySet()
                prefs[suppressedOptionalModelIdsKey] = current - model.name
            }
            Log.i(TAG, "ModelPreferences: unsuppressed optional model ${model.displayName}")
        } catch (e: IOException) {
            Log.e(TAG, "ModelPreferences: failed to unsuppress model", e)
        }
    }

    suspend fun setPreferredModel(model: KernelModel?) {
        try {
            dataStore.edit { prefs ->
                if (model == null) {
                    prefs.remove(preferredModelKey)
                    Log.i(TAG, "ModelPreferences: cleared preferred model (auto mode)")
                } else {
                    prefs[preferredModelKey] = model.name
                    Log.i(TAG, "ModelPreferences: set preferred model to ${model.displayName}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "ModelPreferences: failed to save preference", e)
            throw e  // re-throw so caller can surface feedback to the user
        }
    }
}
