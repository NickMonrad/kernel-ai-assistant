package com.kernel.ai.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernel.ai.core.memory.lists.ListsDataChanged

/**
 * Non-exported receiver for the internal [ListsDataChanged.ACTION] signal.
 *
 * It is deliberately NOT exported: only broadcasts from within this app (and the now
 * package-targeted [ListsDataChanged.broadcast]) can reach it, so another app cannot spoof the
 * action to trigger a widget re-render or DB read. The standard app-widget lifecycle is handled
 * by [ListsWidgetReceiver].
 */
class ListsDataChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ListsDataChanged.ACTION) {
            ListsWidgetRefresher.refresh(context)
        }
    }
}
