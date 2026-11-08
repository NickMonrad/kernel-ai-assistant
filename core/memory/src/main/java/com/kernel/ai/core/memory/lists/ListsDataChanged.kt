package com.kernel.ai.core.memory.lists

import android.content.Context
import android.content.Intent

/**
 * Dependency-safe invalidation signal for the Lists home-screen widget.
 *
 * List mutations happen in two places that the widget cannot observe directly:
 *  - [com.kernel.ai.core.skills.natives.NativeIntentHandler] (voice / chat `add_to_list`, etc.)
 *  - [com.kernel.ai.feature.settings.ListsViewModel] (in-app Lists UI)
 *
 * Both live in modules that depend on `:core:memory`, but neither should depend on
 * `:feature:widget`. To keep module boundaries intact, list mutations broadcast this
 * plain intent; [com.kernel.ai.feature.widget.ListsWidgetReceiver] listens for it and
 * refreshes the Glance widget without polling or a shared event bus.
 *
 * Broadcasting is the only cross-module coupling and is intentionally fire-and-forget:
 * the local list mutation remains authoritative even if the broadcast or a widget update
 * fails.
 */
object ListsDataChanged {
    /** Action carried by the lists-changed broadcast. */
    const val ACTION = "com.kernel.ai.action.LISTS_DATA_CHANGED"

    /**
     * Notify listeners (the Lists widget) that list data may have changed.
     *
     * Wrapped in try/catch so a failed/unsupported broadcast can never make an otherwise
     * successful local list mutation fail.
     */
    fun broadcast(context: Context) {
        try {
            context.sendBroadcast(Intent(ACTION).apply { setPackage(context.packageName) })
        } catch (_: Throwable) {
            // A failed/unsupported broadcast must never roll back a successful list mutation.
        }
    }
}
