package com.kernel.ai.feature.widget

import android.app.Activity
import android.content.SharedPreferences
import io.mockk.every
import org.junit.jupiter.api.Test
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.test.runTest

class ListsWidgetConfigureActivityTest {

    /**
     * Fake prefs that distinguish synchronous [SharedPreferences.Editor.commit] from asynchronous
     * [SharedPreferences.Editor.apply]: [SharedPreferences.Editor.putLong] only stages a value, and
     * [SharedPreferences.Editor.commit] (when [commitSucceeds]) is what flushes it to the readable
     * store. This proves the configuration path relies on the synchronous commit, not apply.
     */
    private fun makePrefs(commitSucceeds: Boolean = true): SharedPreferences {
        val store = mutableMapOf<String, Long>()
        val staged = mutableMapOf<String, Long>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.edit() } returns editor
        every { prefs.getLong(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        every { editor.putLong(any(), any()) } answers { staged[firstArg()] = secondArg(); editor }
        every { editor.remove(any()) } answers { staged.remove(firstArg()); editor }
        every { editor.commit() } answers {
            val ok = commitSucceeds
            if (ok) { store.putAll(staged); staged.clear() }
            ok
        }
        every { editor.apply() } answers { /* asynchronous: not flushed synchronously */ }
        return prefs
    }

    @Test
    fun `commit success persists selection and returns RESULT_OK on render success`() = runTest {
        val config = ListsWidgetConfig(makePrefs())
        var rendered = false
        val result = persistSelectionAndResult(config, 11, 4242L) { rendered = true }
        assertEquals(Activity.RESULT_OK, result)
        assertEquals(4242L, config.getSelectedListId(11))
        assertEquals(true, rendered)
    }

    @Test
    fun `commit success persists selection but returns RESULT_CANCELED when initial composition fails`() = runTest {
        val config = ListsWidgetConfig(makePrefs())
        var rendered = false
        val result = persistSelectionAndResult(config, 12, 99L) {
            rendered = true
            throw RuntimeException("initial widget composition failed")
        }
        assertEquals(Activity.RESULT_CANCELED, result)
        assertEquals(true, rendered)
        assertEquals(99L, config.getSelectedListId(12))
    }

    @Test
    fun `platform RemoteViews update failure returns RESULT_CANCELED`() = runTest {
        val config = ListsWidgetConfig(makePrefs())
        val result = persistSelectionAndResult(config, 13, 100L) {
            throw IllegalStateException("AppWidgetManager.updateAppWidget failed")
        }
        assertEquals(Activity.RESULT_CANCELED, result)
        assertEquals(100L, config.getSelectedListId(13))
    }

    @Test
    fun `commit failure returns RESULT_CANCELED without attempting initial render`() = runTest {
        val config = ListsWidgetConfig(makePrefs(commitSucceeds = false))
        var rendered = false
        val result = persistSelectionAndResult(config, 7, 555L) {
            rendered = true
        }
        assertEquals(Activity.RESULT_CANCELED, result)
        assertEquals(false, rendered)
        assertEquals(ListsWidgetConfig.INVALID, config.getSelectedListId(7))
    }

    @Test
    fun `same appWidgetId is used for the committed binding`() = runTest {
        val config = ListsWidgetConfig(makePrefs())
        persistSelectionAndResult(config, 21, 808L) {}
        assertEquals(808L, config.getSelectedListId(21))
        assertEquals(ListsWidgetConfig.INVALID, config.getSelectedListId(22))
    }

    @Test
    fun `multiple widget ids remain independently persisted`() = runTest {
        val config = ListsWidgetConfig(makePrefs())
        assertEquals(Activity.RESULT_OK, persistSelectionAndResult(config, 31, 1L) {})
        assertEquals(Activity.RESULT_OK, persistSelectionAndResult(config, 32, 2L) {})
        assertEquals(1L, config.getSelectedListId(31))
        assertEquals(2L, config.getSelectedListId(32))
    }
}
