package com.kernel.ai.core.inference.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DownloadNetworkPreferencesTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** One DataStore per test: DataStore rejects two live instances over the same file. */
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tempDir, "download_network.preferences_pb") },
        )
    }

    @Test
    fun `nothing is chosen on a fresh store`() = runTest {
        assertNull(DownloadNetworkPreferences(dataStore).choice.first())
    }

    @Test
    fun `choice survives a new wrapper over the same store after process death`() = runTest {
        DownloadNetworkPreferences(dataStore).setChoice(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)

        val restored = DownloadNetworkPreferences(dataStore).choice.first()
        assertEquals(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, restored)
    }
}
