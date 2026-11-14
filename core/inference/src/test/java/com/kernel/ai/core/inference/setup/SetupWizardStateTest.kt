package com.kernel.ai.core.inference.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SetupWizardStateTest {

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
            produceFile = { File(tempDir, "setup_wizard.preferences_pb") },
        )
    }

    // ── Flow ────────────────────────────────────────────────────────────────

    @Test
    fun `next moves forward and returns null on the last step`() {
        assertEquals(SetupStep.DOWNLOAD, SetupWizardFlow.next(SetupStep.HOW_IT_WORKS))
        assertNull(SetupWizardFlow.next(SetupStep.DOWNLOAD))
    }

    @Test
    fun `previous moves back and returns null on the first step`() {
        assertEquals(SetupStep.HOW_IT_WORKS, SetupWizardFlow.previous(SetupStep.DOWNLOAD))
        assertNull(SetupWizardFlow.previous(SetupStep.HOW_IT_WORKS))
    }

    @Test
    fun `unknown stored step falls back to the first step`() {
        assertEquals(SetupStep.HOW_IT_WORKS, SetupStep.fromStorageName("GONE"))
        assertEquals(SetupStep.HOW_IT_WORKS, SetupStep.fromStorageName(null))
    }

    // ── First-run decision ──────────────────────────────────────────────────

    @Test
    fun `new install is not completed so the wizard shows once`() {
        assertFalse(SetupWizardFlow.completedAfterFirstLaunchDecision(alreadyDecided = false, previouslyCompleted = false, existingInstall = false))
    }

    @Test
    fun `existing install with models is completed and never shown the wizard`() {
        assertTrue(SetupWizardFlow.completedAfterFirstLaunchDecision(alreadyDecided = false, previouslyCompleted = false, existingInstall = true))
    }

    @Test
    fun `once decided a new install stays incomplete until the wizard finishes`() {
        assertFalse(SetupWizardFlow.completedAfterFirstLaunchDecision(alreadyDecided = true, previouslyCompleted = false, existingInstall = true))
    }

    // ── Persistence and process-death restore ───────────────────────────────

    @Test
    fun `new install shows the wizard from the first step`() = runTest {
        SetupWizardPreferences(dataStore).decideFirstRun(existingInstall = false)

        val progress = SetupWizardPreferences(dataStore).progress.first()
        assertFalse(progress.completed)
        assertEquals(SetupStep.HOW_IT_WORKS, progress.step)
    }

    @Test
    fun `saved step is restored by a new wrapper after process death`() = runTest {
        val prefs = SetupWizardPreferences(dataStore)
        prefs.decideFirstRun(existingInstall = false)
        prefs.saveStep(SetupStep.DOWNLOAD)

        val restored = SetupWizardPreferences(dataStore).progress.first()
        assertEquals(SetupStep.DOWNLOAD, restored.step)
        assertFalse(restored.completed)
    }

    @Test
    fun `finished wizard stays completed across restarts`() = runTest {
        val prefs = SetupWizardPreferences(dataStore)
        prefs.decideFirstRun(existingInstall = false)
        prefs.markCompleted()

        assertTrue(SetupWizardPreferences(dataStore).progress.first().completed)
    }

    @Test
    fun `existing install is marked complete on first launch`() = runTest {
        SetupWizardPreferences(dataStore).decideFirstRun(existingInstall = true)

        assertTrue(SetupWizardPreferences(dataStore).progress.first().completed)
    }

    @Test
    fun `re-run restarts from the first step`() = runTest {
        val prefs = SetupWizardPreferences(dataStore)
        prefs.decideFirstRun(existingInstall = true)
        prefs.saveStep(SetupStep.DOWNLOAD)

        prefs.restartFromFirstStep()

        val progress = SetupWizardPreferences(dataStore).progress.first()
        assertFalse(progress.completed)
        assertEquals(SetupStep.HOW_IT_WORKS, progress.step)
    }
}
