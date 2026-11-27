package com.kernel.ai.core.voice.kokoro

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists "a Kokoro native load is in progress" across process restarts (#1449). A native abort during a load cannot
 * be caught, so the next launch reads [unfinishedAtLaunch] and skips the automatic warm-up, which would otherwise
 * abort again on every start. Reply-time loads still run; a load that completes clears the flag.
 */
@Singleton
class KokoroLoadMarker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Read once per process, before this process writes the flag. */
    val unfinishedAtLaunch: Boolean = prefs.getBoolean(KEY_LOADING, false)

    fun loading() {
        prefs.edit().putBoolean(KEY_LOADING, true).commit()
    }

    fun loaded() {
        prefs.edit().putBoolean(KEY_LOADING, false).commit()
    }

    private companion object {
        const val PREFS = "kokoro_load_marker"
        const val KEY_LOADING = "native_load_in_progress"
    }
}
