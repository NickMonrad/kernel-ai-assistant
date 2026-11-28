package com.kernel.ai.feature.settings

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        every { manager.getAppWidgetIds(any()) } returns intArrayOf()
        HomeWidgetPinning.clearPendingWidgetPins()
    }

    @AfterEach
    fun tearDownPendingIntentMocks() {
        unmockkConstructor(Intent::class)
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `reports Unsupported when the launcher cannot pin widgets`() {
        every { manager.isRequestPinAppWidgetSupported } returns false

        assertEquals(
            HomeWidgetPinning.PinResult.Unsupported,
            HomeWidgetPinning.requestWidgetPin(context, HomeItem.JANDAL_WIDGET, provider, successCallback),
        )
        verify(exactly = 0) { manager.requestPinAppWidget(any(), any(), any()) }
    }

    @Test
    fun `reports Unsupported when AppWidgetManager is unavailable`() {
        every { context.getSystemService(AppWidgetManager::class.java) } returns null

        assertEquals(
            HomeWidgetPinning.PinResult.Unsupported,
            HomeWidgetPinning.requestWidgetPin(context, HomeItem.JANDAL_WIDGET, provider, successCallback),
        )
    }

    @Test
    fun `reports Requested and hands the success callback to the launcher when accepted`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(provider, null, successCallback) } returns true

        assertEquals(
            HomeWidgetPinning.PinResult.Requested,
            HomeWidgetPinning.requestWidgetPin(context, HomeItem.JANDAL_WIDGET, provider, successCallback),
        )
        verify { manager.requestPinAppWidget(provider, null, successCallback) }
    }

    @Test
    fun `reports Rejected when the launcher declines a supported request`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(provider, null, successCallback) } returns false

        assertEquals(
            HomeWidgetPinning.PinResult.Rejected,
            HomeWidgetPinning.requestWidgetPin(context, HomeItem.JANDAL_WIDGET, provider, successCallback),
        )
    }

    @Test
    fun `reports Rejected instead of Requested when the launcher throws`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(provider, null, successCallback) } throws
            SecurityException("launcher refused")

        assertEquals(
            HomeWidgetPinning.PinResult.Rejected,
            HomeWidgetPinning.requestWidgetPin(context, HomeItem.JANDAL_WIDGET, provider, successCallback),
        )
    }

    @Test
    fun `confirmation PendingIntent is update-current and immutable, never mutable`() {
        val flags = slot<Int>()
        mockkConstructor(Intent::class)
        mockkStatic(PendingIntent::class)
        every { anyConstructed<Intent>().setClass(any(), any()) } returns mockk(relaxed = true)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } returns mockk(relaxed = true)
        every { PendingIntent.getBroadcast(any(), any(), any(), capture(flags)) } returns successCallback

        HomeWidgetPinning.confirmationPendingIntent(context, HomeItem.LISTS_WIDGET)

        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, flags.captured)
        assertEquals(0, flags.captured and PendingIntent.FLAG_MUTABLE)
        assertEquals(HomeWidgetPinning.CONFIRMATION_FLAGS, flags.captured)
    }

    @Test
    fun `confirmation intent targets the receiver explicitly and carries the item name`() {
        val target = slot<Class<*>>()
        mockkConstructor(Intent::class)
        mockkStatic(PendingIntent::class)
        every { anyConstructed<Intent>().setClass(any(), capture(target)) } returns mockk(relaxed = true)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } returns mockk(relaxed = true)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns successCallback

        HomeWidgetPinning.confirmationPendingIntent(context, HomeItem.LISTS_WIDGET)

        assertEquals(HomeWidgetPinConfirmedReceiver::class.java, target.captured)
        verify { anyConstructed<Intent>().putExtra(HomeWidgetPinning.EXTRA_ITEM, HomeItem.LISTS_WIDGET.name) }
    }

    @Test
    fun `confirmedItem resolves every item name from a confirmation broadcast`() {
        HomeItem.entries.forEach { item ->
            assertEquals(item, HomeWidgetPinning.confirmedItem(HomeWidgetPinning.ACTION_PIN_CONFIRMED, item.name))
        }
    }

    @Test
    fun `confirmedItem ignores a broadcast with another action`() {
        assertNull(HomeWidgetPinning.confirmedItem("android.intent.action.VIEW", HomeItem.LISTS_WIDGET.name))
    }

    @Test
    fun `confirmedItem ignores a missing or unknown item extra`() {
        assertNull(HomeWidgetPinning.confirmedItem(HomeWidgetPinning.ACTION_PIN_CONFIRMED, null))
        assertNull(HomeWidgetPinning.confirmedItem(HomeWidgetPinning.ACTION_PIN_CONFIRMED, "NOT_AN_ITEM"))
    }

    @Test
    fun `ignores a confirmation for an item that was never requested`() {
        assertFalse(HomeWidgetPinning.claimConfirmation(HomeItem.LISTS_WIDGET, widgetCount = 1))
    }

    @Test
    fun `a rejected request is not pending, so its callback cannot claim`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(any(), any(), any()) } returns false
        HomeWidgetPinning.requestWidgetPin(context, HomeItem.JANDAL_WIDGET, provider, successCallback)

        assertFalse(HomeWidgetPinning.claimConfirmation(HomeItem.JANDAL_WIDGET, widgetCount = 1))
    }

    @Test
    fun `ignores a stale callback when no new widget appeared since the request`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(any(), any(), any()) } returns true
        every { manager.getAppWidgetIds(any()) } returns intArrayOf(7)
        HomeWidgetPinning.requestWidgetPin(context, HomeItem.LISTS_WIDGET, provider, successCallback)

        assertFalse(HomeWidgetPinning.claimConfirmation(HomeItem.LISTS_WIDGET, widgetCount = 1))
    }

    @Test
    fun `claims a confirmation once when the widget count grew, then ignores duplicates`() {
        every { manager.isRequestPinAppWidgetSupported } returns true
        every { manager.requestPinAppWidget(any(), any(), any()) } returns true
        HomeWidgetPinning.requestWidgetPin(context, HomeItem.JANDAL_WIDGET, provider, successCallback)

        assertTrue(HomeWidgetPinning.claimConfirmation(HomeItem.JANDAL_WIDGET, widgetCount = 1))
        assertFalse(HomeWidgetPinning.claimConfirmation(HomeItem.JANDAL_WIDGET, widgetCount = 1))
    }
}
