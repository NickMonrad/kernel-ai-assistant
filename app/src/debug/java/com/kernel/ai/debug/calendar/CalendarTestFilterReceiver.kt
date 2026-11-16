package com.kernel.ai.debug.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.skills.natives.CalendarEventStore

/**
 * Debug-only (#1942): limits calendar reads to one named calendar, so device evidence on a phone with real
 * events uses only made-up ones. Extra "calendar" = calendar display name; no extra clears the filter.
 * Explicit component only.
 */
class CalendarTestFilterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("calendar")?.trim()?.takeIf { it.isNotEmpty() }
        context.getSharedPreferences(CalendarEventStore.DEBUG_TEST_CALENDAR_PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (name == null) remove(CalendarEventStore.DEBUG_TEST_CALENDAR_NAME)
                else putString(CalendarEventStore.DEBUG_TEST_CALENDAR_NAME, name)
            }
            .commit()
        Log.i(TAG, "calendar test filter ${if (name == null) "cleared" else "set"}")
    }

    private companion object {
        const val TAG = "KernelAI"
    }
}
