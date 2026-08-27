package com.kernel.ai.feature.widget

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListsWidgetConfigTest {

    private fun makePrefs(): SharedPreferences {
        val store = mutableMapOf<String, Long>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getLong(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        every { editor.putLong(any(), any()) } answers {
            store[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            store.remove(firstArg())
            editor
        }
        return prefs
    }

    @Test
    fun `returns INVALID sentinel when nothing stored`() {
        val config = ListsWidgetConfig(makePrefs())
        assertEquals(ListsWidgetConfig.INVALID, config.getSelectedListId(7))
    }

    @Test
    fun `persists and reads selected list id per widget independently`() {
        val config = ListsWidgetConfig(makePrefs())
        config.setSelectedListId(3, 42L)
        config.setSelectedListId(9, 99L)
        assertEquals(42L, config.getSelectedListId(3))
        assertEquals(99L, config.getSelectedListId(9))
        assertEquals(ListsWidgetConfig.INVALID, config.getSelectedListId(1))
    }

    @Test
    fun `clear removes only the targeted widget`() {
        val config = ListsWidgetConfig(makePrefs())
        config.setSelectedListId(3, 42L)
        config.setSelectedListId(9, 99L)
        config.clear(3)
        assertEquals(ListsWidgetConfig.INVALID, config.getSelectedListId(3))
        assertEquals(99L, config.getSelectedListId(9))
    }
}
