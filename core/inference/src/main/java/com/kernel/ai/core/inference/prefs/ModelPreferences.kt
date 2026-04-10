package com.kernel.ai.core.inference.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kernel.ai.core.inference.download.KernelModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"
private val Context.modelPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_preferences")

@Singleton
class ModelPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val preferredModelKey = stringPreferencesKey("preferred_conversation_model")

    /** Null means "auto" — let tier-based logic decide. */
    val preferredConversationModel: Flow<KernelModel?> = context.modelPrefsDataStore.data
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

    suspend fun setPreferredModel(model: KernelModel?) {
        try {
            context.modelPrefsDataStore.edit { prefs ->
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
