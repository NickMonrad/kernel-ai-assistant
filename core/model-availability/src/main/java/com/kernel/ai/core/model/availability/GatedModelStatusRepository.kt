package com.kernel.ai.core.model.availability

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kernel.ai.core.inference.download.KernelModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GatedModelStatusRepo"

private val Context.gatedStatusDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "gated_model_status")

/**
 * DataStore-backed repository for per-model [GatedModelStatus].
 *
 * This is a lightweight scaffolding until the real HuggingFace moderation
 * webhook is implemented. The debug toggle in Settings → About provides
 * manual QA control over each model's status.
 */
@Singleton
class GatedModelStatusRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Observe the status for a specific model. */
    fun get(model: KernelModel): Flow<GatedModelStatus> =
        context.gatedStatusDataStore.data
            .catch { e ->
                if (e is IOException) {
                    Log.w(TAG, "DataStore read error for ${model.modelId}", e)
                    emit(emptyPreferences())
                } else throw e
            }
            .map { prefs ->
                val raw = prefs[key(model)] ?: return@map GatedModelStatus.NONE
                try {
                    GatedModelStatus.valueOf(raw)
                } catch (_: IllegalArgumentException) {
                    GatedModelStatus.NONE
                }
            }

    /** Set the status for a specific model. */
    suspend fun set(model: KernelModel, status: GatedModelStatus) {
        context.gatedStatusDataStore.edit { prefs ->
            if (status == GatedModelStatus.NONE) {
                prefs.remove(key(model))
            } else {
                prefs[key(model)] = status.name
            }
        }
        Log.i(TAG, "Set ${model.modelId} → $status")
    }

    /** Snapshot read (non-flow). Useful for one-shot checks. */
    suspend fun getSnapshot(model: KernelModel): GatedModelStatus =
        get(model).first()

    private fun key(model: KernelModel) = stringPreferencesKey("gated_${model.modelId}")
}
