package com.kernel.ai.feature.widget

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListsWidgetConfigObservationTest {

    @Test
    fun `emits current selected list immediately and unregisters after collection`() = runTest {
        val (prefs, _, listenerCount) = makePrefs(mapOf("list_id_7" to 42L))
        val values = mutableListOf<Long>()
        val job = launch { ListsWidgetConfig(prefs).observeSelectedListId(7).take(1).toList(values) }

        runCurrent()
        job.join()

        assertEquals(listOf(42L), values)
        assertEquals(0, listenerCount())
    }

    @Test
    fun `only the exact widget key emits and invalid values map to INVALID`() = runTest {
        val (prefs, update, _) = makePrefs()
        val values = mutableListOf<Long>()
        val job = launch { ListsWidgetConfig(prefs).observeSelectedListId(7).take(3).toList(values) }

        runCurrent()
        update("list_id_8", 88L)
        update("list_id_7", 0L)
        update("list_id_7", 23L)
        job.join()

        assertEquals(listOf(ListsWidgetConfig.INVALID, ListsWidgetConfig.INVALID, 23L), values)
    }

    @Test
    fun `widget bindings remain independent`() = runTest {
        val (prefs, update, _) = makePrefs(mapOf("list_id_7" to 70L, "list_id_8" to 80L))
        val widgetA = mutableListOf<Long>()
        val widgetB = mutableListOf<Long>()
        val jobA = launch { ListsWidgetConfig(prefs).observeSelectedListId(7).take(2).toList(widgetA) }
        val jobB = launch { ListsWidgetConfig(prefs).observeSelectedListId(8).take(2).toList(widgetB) }

        runCurrent()
        update("list_id_8", 81L)
        jobB.join()
        update("list_id_7", 71L)
        jobA.join()

        assertEquals(listOf(70L, 71L), widgetA)
        assertEquals(listOf(80L, 81L), widgetB)
    }

    private fun makePrefs(
        initial: Map<String, Long> = emptyMap(),
    ): Triple<SharedPreferences, (String, Long) -> Unit, () -> Int> {
        val store = initial.toMutableMap()
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val prefs = mockk<SharedPreferences>()
        every { prefs.getLong(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        every { prefs.registerOnSharedPreferenceChangeListener(any()) } answers {
            listeners += firstArg<SharedPreferences.OnSharedPreferenceChangeListener>()
        }
        every { prefs.unregisterOnSharedPreferenceChangeListener(any()) } answers {
            listeners -= firstArg<SharedPreferences.OnSharedPreferenceChangeListener>()
        }
        val update: (String, Long) -> Unit = { key, value ->
            store[key] = value
            listeners.toList().forEach { it.onSharedPreferenceChanged(prefs, key) }
        }
        return Triple(prefs, update, { listeners.size })
    }
}
