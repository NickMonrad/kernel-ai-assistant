package com.kernel.ai.core.memory.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatPreferencesSystemColorsTest {

    @Test
    fun `untouched install defaults to Jandal colours, not system colours`() {
        assertEquals(false, emptyPreferences().resolveUseSystemColors())
    }

    @Test
    fun `an explicit stored choice is kept, whichever way it was set`() {
        val key = booleanPreferencesKey("use_system_colors")
        assertEquals(true, mutablePreferencesOf(key to true).resolveUseSystemColors())
        assertEquals(false, mutablePreferencesOf(key to false).resolveUseSystemColors())
    }
}
