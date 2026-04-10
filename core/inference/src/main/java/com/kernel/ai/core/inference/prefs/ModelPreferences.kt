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
import kotlinx.coroutines.flow.map
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
        .map { prefs ->
            prefs[preferredModelKey]?.let { name ->
                KernelModel.entries.find { it.name == name }
                    .also { if (it == null) Log.w(TAG, "ModelPreferences: unknown model key '$name', ignoring") }
            }
        }

    suspend fun setPreferredModel(model: KernelModel?) {
        context.modelPrefsDataStore.edit { prefs ->
            if (model == null) {
                prefs.remove(preferredModelKey)
                Log.i(TAG, "ModelPreferences: cleared preferred model (auto mode)")
            } else {
                prefs[preferredModelKey] = model.name
                Log.i(TAG, "ModelPreferences: set preferred model to ${model.displayName}")
            }
        }
    }
}
