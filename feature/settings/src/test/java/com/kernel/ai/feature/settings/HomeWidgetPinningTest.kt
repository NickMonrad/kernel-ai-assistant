package com.kernel.ai.feature.settings

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HomeWidgetPinningTest {

    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<AppWidgetManager>(relaxed = true)
    private val provider = mockk<ComponentName>(relaxed = true)
    private val successCallback = mockk<PendingIntent>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { context.getSystemService(AppWidgetManager::class.java) } returns manager
    }

    @Test
    fun `reports Unsupported when the launcher cannot pin widgets`() {
        every { manager.isRequestPinAppWidgetSupported } returns false

        assertEquals(
            HomeWidgetPinning.PinResult.Unsupported,
            HomeWidgetPinning.requestWidgetPin(context, provider, successCallback),
        )
        verify(exactly = 0) { manager.requestPinAppWidget(any(), any(), any()) }
    }

    @Test
    fun `reports Unsupported when AppWidgetManager is unavailable`() {
        every { context.getSystemService(AppWidgetManager::class.java) } returns null

        assertEquals(
            HomeWidgetPinning.PinResult.Unsupported,
            HomeWidgetPinning.requestWidgetPin(context, provider, successCallback),
        )
    }

    @Test
    fun `reports Requested and hands the success callback to the launcher when accepted`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(provider, null, successCallback) } returns true

        assertEquals(
            HomeWidgetPinning.PinResult.Requested,
            HomeWidgetPinning.requestWidgetPin(context, provider, successCallback),
        )
        verify { manager.requestPinAppWidget(provider, null, successCallback) }
    }

    @Test
    fun `reports Rejected when the launcher declines a supported request`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(provider, null, successCallback) } returns false

        assertEquals(
            HomeWidgetPinning.PinResult.Rejected,
            HomeWidgetPinning.requestWidgetPin(context, provider, successCallback),
        )
    }

    @Test
    fun `reports Rejected instead of Requested when the launcher throws`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(provider, null, successCallback) } throws
            SecurityException("launcher refused")

        assertEquals(
            HomeWidgetPinning.PinResult.Rejected,
            HomeWidgetPinning.requestWidgetPin(context, provider, successCallback),
        )
    }
}
