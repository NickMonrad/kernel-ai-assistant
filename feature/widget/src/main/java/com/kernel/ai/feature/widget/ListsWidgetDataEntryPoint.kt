package com.kernel.ai.feature.widget

import androidx.glance.appwidget.GlanceAppWidget
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point exposing the list DAOs to the Glance widget.
 *
 * A [GlanceAppWidget.provideGlance] runs outside any `@AndroidEntryPoint`/ViewModel, so it
 * cannot use field injection. This entry point lets the widget read the same Room-backed list
 * data used by the in-app Lists UI and `add_to_list`, keeping a single source of truth.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ListsWidgetDataEntryPoint {
    fun listNameDao(): ListNameDao
    fun listItemDao(): ListItemDao
}
