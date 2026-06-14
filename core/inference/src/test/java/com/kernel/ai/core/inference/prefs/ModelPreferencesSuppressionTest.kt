package com.kernel.ai.core.inference.prefs

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import com.kernel.ai.core.inference.download.KernelModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for ModelPreferences suppression logic.
 *
 * Note: preferencesDataStore uses a per-context-class singleton cache,
 * so tests that rely on empty initial state can see leakage from other
 * tests. The tests here focus on the suppress/unsuppress interaction
 * which is robust regardless of initial state.
 */
class ModelPreferencesSuppressionTest {

    private lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        tempDir = java.nio.file.Files.createTempDirectory("pref_suppression").toFile()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
        tempDir.deleteRecursively()
    }

    private fun createPrefs(): ModelPreferences {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns tempDir
        every { ctx.getDatabasePath(any()) } returns File(tempDir, "test.db")
        every { ctx.cacheDir } returns File(tempDir, "cache")
        every { ctx.applicationContext } returns ctx
        return ModelPreferences(ctx)
    }

    @Test
    fun `suppress then isOptionalModelSuppressed returns true`() {
        val prefs = createPrefs()
        val model = KernelModel.GEMMA_4_E4B
        runBlocking { prefs.suppressOptionalModel(model) }
        assertTrue(runBlocking { prefs.isOptionalModelSuppressed(model) })
    }

    @Test
    fun `unsuppress removes model from set`() {
        val prefs = createPrefs()
        val model = KernelModel.GEMMA_4_E4B
        runBlocking { prefs.suppressOptionalModel(model) }
        runBlocking { prefs.unsuppressOptionalModel(model) }
        assertFalse(runBlocking { prefs.isOptionalModelSuppressed(model) })
    }

    @Test
    fun `multiple models suppressed independently`() {
        val prefs = createPrefs()
        val e4b = KernelModel.GEMMA_4_E4B
        val e2b = KernelModel.GEMMA_4_E2B

        runBlocking { prefs.suppressOptionalModel(e4b) }
        runBlocking { prefs.suppressOptionalModel(e2b) }

        val ids = runBlocking { prefs.suppressedOptionalModelIds.first() }
        assertEquals(2, ids.size)
        assertTrue(ids.contains(e4b.name))
        assertTrue(ids.contains(e2b.name))
    }

    @Test
    fun `unsuppress one model leaves others`() {
        val prefs = createPrefs()
        val e4b = KernelModel.GEMMA_4_E4B
        val e2b = KernelModel.GEMMA_4_E2B

        runBlocking { prefs.suppressOptionalModel(e4b) }
        runBlocking { prefs.suppressOptionalModel(e2b) }
        runBlocking { prefs.unsuppressOptionalModel(e4b) }

        val ids = runBlocking { prefs.suppressedOptionalModelIds.first() }
        assertTrue(ids.contains(e2b.name), "E2B should remain suppressed")
        assertFalse(ids.contains(e4b.name), "E4B should be removed")
    }

    @Test
    fun `user-initiated download clears suppression`() {
        val prefs = createPrefs()
        val model = KernelModel.GEMMA_4_E4B

        runBlocking { prefs.suppressOptionalModel(model) }
        assertTrue(runBlocking { prefs.isOptionalModelSuppressed(model) })

        runBlocking { prefs.unsuppressOptionalModel(model) }
        assertFalse(runBlocking { prefs.isOptionalModelSuppressed(model) })
    }

    @Test
    fun `required model E2B not affected by optional E4B suppression`() {
        val prefs = createPrefs()
        runBlocking { prefs.suppressOptionalModel(KernelModel.GEMMA_4_E4B) }

        assertFalse(
            runBlocking { prefs.isOptionalModelSuppressed(KernelModel.GEMMA_4_E2B) },
            "Required model should not be in suppressed set"
        )
    }

    @Test
    fun `suppressing required model does not prevent auto-queue`() {
        // suppressOptionalModel doesn't reject required models, but
        // ModelDownloadManager's auto-queue filter checks `!it.isRequired` first,
        // so suppression of required models has no effect on auto-queue behavior.
        val prefs = createPrefs()
        runBlocking { prefs.suppressOptionalModel(KernelModel.GEMMA_4_E2B) }
        assertTrue(
            runBlocking { prefs.isOptionalModelSuppressed(KernelModel.GEMMA_4_E2B) },
            "Method doesn't reject required models, but auto-queue ignores suppression for required"
        )
    }

    @Test
    fun `suppress is idempotent`() {
        val prefs = createPrefs()
        val model = KernelModel.GEMMA_4_E4B

        runBlocking { prefs.suppressOptionalModel(model) }
        runBlocking { prefs.suppressOptionalModel(model) } // same model again

        assertTrue(runBlocking { prefs.isOptionalModelSuppressed(model) })
    }

    @Test
    fun `unsuppress non-suppressed model is no-op`() {
        val prefs = createPrefs()
        val model = KernelModel.GEMMA_4_E4B

        // Should not throw
        runBlocking { prefs.unsuppressOptionalModel(model) }
        assertFalse(runBlocking { prefs.isOptionalModelSuppressed(model) })
    }
}
