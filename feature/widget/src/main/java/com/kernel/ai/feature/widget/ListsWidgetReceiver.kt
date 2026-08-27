package com.kernel.ai.feature.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.kernel.ai.core.memory.lists.ListsDataChanged

/**
 * AppWidget broadcast receiver for the Lists widget.
 *
 * In addition to the standard Glance lifecycle (`APPWIDGET_UPDATE`, etc.), it listens for the
 * dependency-safe [ListsDataChanged.ACTION] broadcast emitted after any successful local list
 * mutation (in-app or via `add_to_list`). On that signal it asks Glance to re-render every
 * widget instance, which causes [ListsWidget.provideGlance] to re-read the shared list store.
 */
class ListsWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = ListsWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ListsDataChanged.ACTION) {
            ListsWidgetRefresher.refresh(context)
        }
    }
}
