package com.kernel.ai.feature.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.AppWidgetId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListsWidgetLaunchCallbackTest {

    @Test
    fun `routeFor maps a configured list to its deep link`() {
        assertEquals("lists/42", ListsWidgetLaunchCallback.routeFor(42L))
    }

    @Test
    fun `routeFor falls back to the overview for unconfigured or invalid ids`() {
        assertEquals("lists", ListsWidgetLaunchCallback.routeFor(-1L))
        assertEquals("lists", ListsWidgetLaunchCallback.routeFor(0L))
    }

    @Test
    fun `onAction launches MainActivity once for a configured widget`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.kernel.ai"
        val prefs = mockk<SharedPreferences>()
        every { prefs.getLong(any(), any()) } returns 42L
        every { context.getSharedPreferences("lists_widget_config", any()) } returns prefs
        val glanceId: GlanceId = AppWidgetId(APPWIDGET_ID)

        ListsWidgetLaunchCallback().onAction(context, glanceId, actionParametersOf())

        verify(exactly = 1) { context.startActivity(any()) }
    }

    private companion object {
        const val APPWIDGET_ID = 5
    }
}
