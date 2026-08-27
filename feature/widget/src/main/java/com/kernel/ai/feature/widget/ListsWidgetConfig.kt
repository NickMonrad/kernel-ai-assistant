package com.kernel.ai.feature.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Per-widget configuration: which local Jandal list each widget instance displays.
 *
 * This is configuration metadata only — the actual list contents always come from the
 * shared Room store (see [ListsWidget]). Each widget instance is keyed by its Android
 * `appWidgetId`, so multiple widgets can point at different lists independently, and the
 * selection survives process death and normal app restart.
 *
 * The [SharedPreferences] instance is injected (rather than looked up from a [Context]) so
 * the persistence logic is unit-testable with a fake preferences implementation.
 */
class ListsWidgetConfig(private val prefs: SharedPreferences) {

    /** Selected list id for [appWidgetId], or [INVALID] if the widget is not configured. */
    fun getSelectedListId(appWidgetId: Int): Long {
        val stored = prefs.getLong(key(appWidgetId), INVALID)
        return if (stored > 0L) stored else INVALID
    }

    /** Persist the selected list id for [appWidgetId]. */
    fun setSelectedListId(appWidgetId: Int, listId: Long) {
        prefs.edit { putLong(key(appWidgetId), listId) }
    }

    /** Remove configuration for a deleted widget instance. */
    fun clear(appWidgetId: Int) {
        prefs.edit { remove(key(appWidgetId)) }
    }


    private fun key(appWidgetId: Int) = "list_id_$appWidgetId"

    companion object {
        /** Sentinel for "no list selected". */
        const val INVALID: Long = -1L

        private const val PREF_FILE = "lists_widget_config"

        /** Runtime accessor — resolves the dedicated preferences file from a [Context]. */
        fun from(context: Context): ListsWidgetConfig =
            ListsWidgetConfig(context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE))
    }
}
