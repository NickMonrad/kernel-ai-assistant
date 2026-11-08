package com.kernel.ai.feature.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.runBlocking

/**
 * Refreshes all instances of the Lists widget.
 *
 * Extracted into a standalone object so the receiver's refresh side-effect is unit-testable: a
 * test can `mockkObject` this and assert `refresh` is invoked for the lists-changed broadcast
 * (and not for unrelated actions) without spinning up the Glance runtime.
 */
object ListsWidgetRefresher {
    fun refresh(context: Context) {
        try {
            runBlocking {
                val manager = GlanceAppWidgetManager(context)
                manager.getGlanceIds(ListsWidget::class.java)
                    .forEach { ListsWidget().update(context, it) }
            }
        } catch (_: Throwable) {
            // Widget refresh is best-effort; never break the list-mutation path that triggered it.
        }
    }
}
