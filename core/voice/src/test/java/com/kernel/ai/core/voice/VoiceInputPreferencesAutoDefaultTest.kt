package com.kernel.ai.core.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** One-time Automatic move for release installs (#1980). Each test gets its own store file. */
class VoiceInputPreferencesAutoDefaultTest {

    private class Store(isRelease: Boolean) {
        private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(createTempDirectory().toFile(), "voice_input.preferences_pb") },
        )
        val preferences = VoiceInputPreferences(dataStore, isRelease)

        /** Writes the raw stored value, as an install from an earlier build would have it. */
        suspend fun seedStoredEngine(name: String) {
            dataStore.edit { it[stringPreferencesKey("selected_voice_input_engine")] = name }
        }
    }

    @Test
    fun `first release launch moves a stored Android native choice to Automatic`() = runBlocking {
        val store = Store(isRelease = true)
        store.seedStoredEngine(VoiceInputEngine.AndroidNative.name)

        store.preferences.applyAutoDefaultOnce()

        assertEquals(VoiceInputEngine.Auto, store.preferences.selectedEngine.first())
    }

    @Test
    fun `first release launch moves a stored asr4all choice to Automatic`() = runBlocking {
        val store = Store(isRelease = true)
        store.seedStoredEngine(VoiceInputEngine.Asr4all.name)

        store.preferences.applyAutoDefaultOnce()

        assertEquals(VoiceInputEngine.Auto, store.preferences.selectedEngine.first())
    }

    @Test
    fun `an Android native choice saved after the move is kept on later launches`() = runBlocking {
        val store = Store(isRelease = true)
        store.seedStoredEngine(VoiceInputEngine.AndroidNative.name)
        store.preferences.applyAutoDefaultOnce()

        store.preferences.setSelectedEngine(VoiceInputEngine.AndroidNative)
        // A later launch runs the same one-time step again; it must not undo the explicit choice.
        store.preferences.applyAutoDefaultOnce()

        assertEquals(VoiceInputEngine.AndroidNative, store.preferences.selectedEngine.first())
    }

    @Test
    fun `a choice saved before the first launch step is kept`() = runBlocking {
        val store = Store(isRelease = true)
        store.preferences.setSelectedEngine(VoiceInputEngine.Asr4all)

        store.preferences.applyAutoDefaultOnce()

        assertEquals(VoiceInputEngine.Asr4all, store.preferences.selectedEngine.first())
    }

    @Test
    fun `a fresh release install stores nothing, so the setup wizard still sees no stored engine`() = runBlocking {
        val store = Store(isRelease = true)

        store.preferences.applyAutoDefaultOnce()

        assertFalse(store.preferences.hasStoredEngine())
        assertEquals(VoiceInputEngine.Auto, store.preferences.selectedEngine.first())
    }

    @Test
    fun `debug build keeps a stored Android native choice`() = runBlocking {
        val store = Store(isRelease = false)
        store.seedStoredEngine(VoiceInputEngine.AndroidNative.name)

        store.preferences.applyAutoDefaultOnce()

        assertEquals(VoiceInputEngine.AndroidNative, store.preferences.selectedEngine.first())
    }
}
