package com.kernel.ai.core.inference.setup

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "KernelAI"
private val Context.setupWizardDataStore: DataStore<Preferences> by preferencesDataStore(name = "setup_wizard_prefs")

/** Persisted wizard progress. Written on every step change so a process death resumes the same step. */
data class SetupWizardProgress(
    /** The first-run decision has been made (see [SetupWizardFlow.completedAfterFirstLaunchDecision]). */
    val decided: Boolean = false,
    /** Finished or skipped. A completed wizard is not shown on launch; it can be re-run from Settings. */
    val completed: Boolean = false,
    val step: SetupStep = SetupStep.HOW_IT_WORKS,
)

class SetupWizardPreferences internal constructor(private val dataStore: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.setupWizardDataStore)

    private val decidedKey = booleanPreferencesKey("decided")
    private val completedKey = booleanPreferencesKey("completed")
    private val stepKey = stringPreferencesKey("step")

    val progress: Flow<SetupWizardProgress> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "SetupWizardPreferences: DataStore read error, using defaults", e)
                emit(emptyPreferences())
            } else throw e
        }
        .map { prefs ->
            SetupWizardProgress(
                decided = prefs[decidedKey] ?: false,
                completed = prefs[completedKey] ?: false,
                step = SetupStep.fromStorageName(prefs[stepKey]),
            )
        }

    /**
     * Records the first-launch decision. [existingInstall] is true for an install updated from an earlier
     * build (see `isUpdatedInstall`). Updated installs are marked complete and never shown the wizard. Fresh
     * installs are recorded as decided but not completed, so the wizard shows until finished or skipped.
     */
    suspend fun decideFirstRun(existingInstall: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[decidedKey] ?: false
            val completed = prefs[completedKey] ?: false
            prefs[completedKey] = SetupWizardFlow.completedAfterFirstLaunchDecision(
                alreadyDecided = current,
                previouslyCompleted = completed,
                existingInstall = existingInstall,
            )
            prefs[decidedKey] = true
        }
    }

    suspend fun saveStep(step: SetupStep) {
        dataStore.edit { prefs -> prefs[stepKey] = step.name }
    }

    /** Finishing or skipping the wizard. Keeps the saved step so a re-run starts from the first step. */
    suspend fun markCompleted() {
        dataStore.edit { prefs ->
            prefs[decidedKey] = true
            prefs[completedKey] = true
        }
    }

    /**
     * Makes the wizard show again from its first step, without deleting models or choices. Used by the
     * Settings re-run entry and the debug-only "run as first run" entry.
     */
    suspend fun restartFromFirstStep() {
        dataStore.edit { prefs ->
            prefs[decidedKey] = true
            prefs[completedKey] = false
            prefs[stepKey] = SetupStep.HOW_IT_WORKS.name
        }
    }
}
