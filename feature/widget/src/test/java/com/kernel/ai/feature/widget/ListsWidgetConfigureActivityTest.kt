package com.kernel.ai.feature.widget

import android.app.Activity
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest

class ListsWidgetConfigureActivityTest {

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
    fun `selection persists and RESULT_OK returned even when render throws`() = runTest {
        val config = ListsWidgetConfig(makePrefs())
        val result = persistSelectionAndResult(config, 11, 4242L) {
            throw RuntimeException("glance id not resolvable yet on fresh placement")
        }
        assertEquals(Activity.RESULT_OK, result)
        assertEquals(4242L, config.getSelectedListId(11))
    }

    @Test
    fun `selection persists and RESULT_OK returned when render succeeds`() = runTest {
        val config = ListsWidgetConfig(makePrefs())
        var rendered = false
        val result = persistSelectionAndResult(config, 12, 99L) { rendered = true }
        assertEquals(Activity.RESULT_OK, result)
        assertEquals(99L, config.getSelectedListId(12))
        assertEquals(true, rendered)
    }
}
