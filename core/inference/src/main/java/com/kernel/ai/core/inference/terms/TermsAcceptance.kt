package com.kernel.ai.core.inference.terms

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "KernelAI"
private val Context.termsDataStore: DataStore<Preferences> by preferencesDataStore(name = "terms_acceptance_prefs")

/**
 * Version of the bundled TERMS.md that users accept (#1998). It equals the document's effective date, in
 * ISO form. Change it with TERMS.md: a new version asks every user to accept again. The app test
 * `TermsParityTest` fails when the two drift apart.
 */
const val TERMS_VERSION = "2026-11-22"

/** Refusal shown on a model download that needs the Terms of Use accepted first (#1998). */
const val TERMS_NOT_ACCEPTED_MESSAGE = "Accept the Terms of Use to download this speech model."

/** Refusal shown when a voice capture starts without the Terms accepted. The voice UI adds an "Open Voice settings" action (#2049). */
const val TERMS_NOT_ACCEPTED_VOICE_MESSAGE = "Accept the Terms of Use to use this speech model."

/** True when this is the Terms refusal a download gate leaves behind (#2049), so other states must not replace it. */
fun DownloadState?.isTermsRefusal(): Boolean = this is DownloadState.Error && message == TERMS_NOT_ACCEPTED_MESSAGE

/** What is stored on this device about the Terms of Use. */
data class TermsAcceptanceRecord(
    /** The [TERMS_VERSION] the user ticked, or null when nothing is accepted. */
    val acceptedVersion: String? = null,
    /** When [acceptedVersion] was ticked, in epoch milliseconds. Null with [acceptedVersion]. */
    val acceptedAtMillis: Long? = null,
    /** The version whose update prompt the user dismissed. Stops the prompt from returning for that version. */
    val dismissedVersion: String? = null,
) {
    /** True only for the version the app ships now. An older acceptance does not count (a new version asks again). */
    fun isAccepted(currentVersion: String = TERMS_VERSION): Boolean = acceptedVersion == currentVersion

    /** True when the update prompt should show: not accepted for [currentVersion] and not dismissed for it. */
    fun promptPending(currentVersion: String = TERMS_VERSION): Boolean =
        !isAccepted(currentVersion) && dismissedVersion != currentVersion
}

/**
 * Whether [model] may download now (#1998). asr4all needs the Terms accepted for the version the app ships;
 * the other models are never blocked by the Terms.
 */
fun termsAllowDownload(
    model: KernelModel,
    record: TermsAcceptanceRecord,
    currentVersion: String = TERMS_VERSION,
): Boolean = !model.requiresTermsAcceptance || record.isAccepted(currentVersion)

/**
 * Persists the Terms of Use acceptance (#1998). Acceptance is written as soon as the checkbox is ticked, so a
 * process death or a rotation keeps it, and the stored version decides whether the user is asked again.
 */
class TermsAcceptancePreferences internal constructor(private val dataStore: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.termsDataStore)

    private val acceptedVersionKey = stringPreferencesKey("accepted_version")
    private val acceptedAtKey = longPreferencesKey("accepted_at_millis")
    private val dismissedVersionKey = stringPreferencesKey("dismissed_version")

    val record: Flow<TermsAcceptanceRecord> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e(TAG, "TermsAcceptancePreferences: DataStore read error, using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            TermsAcceptanceRecord(
                acceptedVersion = prefs[acceptedVersionKey],
                acceptedAtMillis = prefs[acceptedAtKey],
                dismissedVersion = prefs[dismissedVersionKey],
            )
        }

    /** Ticks ([accepted] = true) or unticks the checkbox for [TERMS_VERSION]. Unticking clears the acceptance. */
    suspend fun setAccepted(accepted: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            if (accepted) {
                prefs[acceptedVersionKey] = TERMS_VERSION
                prefs[acceptedAtKey] = nowMillis
            } else {
                prefs.remove(acceptedVersionKey)
                prefs.remove(acceptedAtKey)
            }
        }
    }

    /** Dismisses the update prompt for [TERMS_VERSION]. The prompt does not return for this version. */
    suspend fun dismissPrompt() {
        dataStore.edit { prefs -> prefs[dismissedVersionKey] = TERMS_VERSION }
    }
}
