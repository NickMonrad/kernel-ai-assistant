package com.kernel.ai.feature.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.AppWidgetId
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListNameEntity
import dagger.hilt.android.EntryPointAccessors
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListsWidgetLaunchCallbackTest {

    @Test
    fun `launch destination uses list detail for an existing configured list`() {
        assertEquals(
            ListsWidgetLaunchDestination.Detail("lists/42"),
            ListsWidgetLaunchCallback.launchDestinationFor(
                42L,
                listExists = true,
                appWidgetId = APPWIDGET_ID,
            ),
        )
    }

    @Test
    fun `launch destination requests configuration for an unconfigured widget`() {
        assertEquals(
            ListsWidgetLaunchDestination.Configure(APPWIDGET_ID),
            ListsWidgetLaunchCallback.launchDestinationFor(
                selectedListId = 0L,
                listExists = false,
                appWidgetId = APPWIDGET_ID,
            ),
        )
        assertEquals(
            ListsWidgetLaunchDestination.Configure(APPWIDGET_ID),
            ListsWidgetLaunchCallback.launchDestinationFor(
                selectedListId = -1L,
                listExists = false,
                appWidgetId = APPWIDGET_ID,
            ),
        )
    }

    @Test
    fun `launch destination requests configuration when the configured list was deleted`() {
        assertEquals(
            ListsWidgetLaunchDestination.Configure(APPWIDGET_ID),
            ListsWidgetLaunchCallback.launchDestinationFor(
                selectedListId = 42L,
                listExists = false,
                appWidgetId = APPWIDGET_ID,
            ),
        )
    }

    @Test
    fun `onAction launches MainActivity once for an existing configured widget`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.kernel.ai"
        every { context.applicationContext } returns context
        val prefs = mockk<SharedPreferences>()
        every { prefs.getLong(any(), any()) } returns 42L
        every { context.getSharedPreferences("lists_widget_config", any()) } returns prefs
        val listNameDao = mockk<ListNameDao>()
        coEvery { listNameDao.getById(42L) } returns ListNameEntity(id = 42L, name = "Groceries")
        val entryPoint = mockk<ListsWidgetDataEntryPoint>()
        every { entryPoint.listNameDao() } returns listNameDao
        mockkStatic(EntryPointAccessors::class)
        try {
            every {
                EntryPointAccessors.fromApplication(context, ListsWidgetDataEntryPoint::class.java)
            } returns entryPoint

            val glanceId: GlanceId = AppWidgetId(APPWIDGET_ID)
            ListsWidgetLaunchCallback().onAction(context, glanceId, actionParametersOf())

            verify(exactly = 1) { context.startActivity(any()) }
        } finally {
            unmockkStatic(EntryPointAccessors::class)
        }
    }

    private companion object {
        const val APPWIDGET_ID = 5
    }
}
