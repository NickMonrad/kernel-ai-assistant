package com.kernel.ai.feature.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * AppWidget lifecycle receiver for the Lists widget.
 *
 * It only handles the standard app-widget lifecycle (`APPWIDGET_UPDATE`, etc.). The internal
 * [com.kernel.ai.core.memory.lists.ListsDataChanged.ACTION] refresh signal is handled by the
 * non-exported [ListsDataChangedReceiver] so another app cannot spoof it to force a widget
 * re-render or DB read.
 */
class ListsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListsWidget()
}
