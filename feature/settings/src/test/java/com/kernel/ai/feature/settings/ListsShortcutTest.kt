package com.kernel.ai.feature.settings

import android.content.Context
import android.content.pm.ShortcutManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ListsShortcutTest {

    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<ShortcutManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(ListsShortcut)
        every { ListsShortcut.buildShortcutInfo(any()) } returns mockk(relaxed = true)
        every { context.getSystemService(ShortcutManager::class.java) } returns manager
    }

    @AfterEach
    fun tearDown() { unmockkObject(ListsShortcut) }

    @Test
    fun `canonical constants match the static shortcut definition`() {
        assertEquals("lists", ListsShortcut.ID)
        assertEquals("lists", ListsShortcut.NAV_ROUTE)
        assertEquals("navigation_route", ListsShortcut.NAV_ROUTE_EXTRA)
        assertEquals(R.string.shortcut_lists_label, ListsShortcut.LABEL_RES)
        assertEquals(R.drawable.ic_shortcut_lists, ListsShortcut.ICON_RES)
    }

    @Test
    fun `requestPin reports Unsupported when pinning is not supported`() {
        every { manager.isRequestPinShortcutSupported } returns false
        assertEquals(ListsShortcut.PinResult.Unsupported, ListsShortcut.requestPin(context))
        verify(exactly = 0) { manager.requestPinShortcut(any(), any()) }
    }

    @Test
    fun `requestPin reports Requested when supported and accepted`() {
        every { manager.isRequestPinShortcutSupported } returns true
        every { manager.requestPinShortcut(any(), any()) } returns true
        assertEquals(ListsShortcut.PinResult.Requested, ListsShortcut.requestPin(context))
        verify { manager.requestPinShortcut(any(), any()) }
    }

    @Test
    fun `requestPin reports Unsupported when the system rejects the pin`() {
        every { manager.isRequestPinShortcutSupported } returns true
        every { manager.requestPinShortcut(any(), any()) } returns false
        assertEquals(ListsShortcut.PinResult.Unsupported, ListsShortcut.requestPin(context))
    }
}
