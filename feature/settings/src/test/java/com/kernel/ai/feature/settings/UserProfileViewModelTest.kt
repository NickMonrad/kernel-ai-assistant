package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.profile.UserProfileYaml
import com.kernel.ai.core.memory.repository.UserProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val profileTextFlow = MutableStateFlow("")
    private val structuredProfileFlow = MutableStateFlow<UserProfileYaml?>(null)
    private val repository: UserProfileRepository = mockk()

    private lateinit var viewModel: UserProfileViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.maxLength } returns 2000
        every { repository.observe() } returns profileTextFlow
        every { repository.observeStructured() } returns structuredProfileFlow
        coEvery { repository.save(any()) } answers {
            val text = firstArg<String>()
            profileTextFlow.value = text
        }
        coEvery { repository.clear() } answers {
            profileTextFlow.value = ""
            structuredProfileFlow.value = null
        }
        viewModel = UserProfileViewModel(repository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial profile text is empty`() = runTest(testDispatcher) {
        assertEquals("", viewModel.profileText.value)
    }

    @Test
    fun `initial structured profile is null`() = runTest(testDispatcher) {
        assertEquals(null, viewModel.structuredProfile.value)
    }

    @Test
    fun `initial saving state is false`() = runTest(testDispatcher) {
        assertFalse(viewModel.saving.value)
    }

    @Test
    fun `maxLength matches repository`() = runTest(testDispatcher) {
        assertEquals(2000, viewModel.maxLength)
    }

    // ── Save behaviour ────────────────────────────────────────────────────────

    @Test
    fun `save sets saving false after completion`() = runTest(testDispatcher) {
        viewModel.save("my name is Nick")
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.saving.value, "saving should be false after save completes")
    }

    @Test
    fun `save persists text to repository`() = runTest(testDispatcher) {
        viewModel.save("my name is Nick")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { repository.save("my name is Nick") }
    }

    @Test
    fun `save updates profileText flow after completion`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.save("my name is Nick. I'm a developer.")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("my name is Nick. I'm a developer.", viewModel.profileText.value)
    }

    @Test
    fun `save emits Success result`() = runTest(testDispatcher) {
        var result: SaveResult? = null
        val job = launch {
            viewModel.saveResult.collect {
                result = it
            }
        }
        viewModel.save("my name is Nick")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(result is SaveResult.Success, "save should emit Success, got: $result")
        job.cancel()
    }

    @Test
    fun `save with empty string persists empty`() = runTest(testDispatcher) {
        viewModel.save("")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { repository.save("") }
    }

    // ── Suspended save lifecycle ──────────────────────────────────────────────

    @Test
    fun `saving is true while save is in progress`() = runTest(testDispatcher) {
        val deferred = CompletableDeferred<Unit>()
        coEvery { repository.save(any()) } coAnswers {
            deferred.await()
        }
        // Start save (will suspend on deferred)
        viewModel.save("test save lifecycle")
        // Advance dispatcher so the coroutine starts and sets _saving = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.saving.value, "saving should be true while save is in progress")
        // Complete the deferred so save finishes
        deferred.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.saving.value, "saving should be false after save completes")
    }

    @Test
    fun `duplicate save is blocked while saving`() = runTest(testDispatcher) {
        val deferred = CompletableDeferred<Unit>()
        coEvery { repository.save(any()) } coAnswers {
            deferred.await()
        }
        // Start first save
        viewModel.save("first")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.saving.value, "saving should be true after first save starts")
        // Attempt second save — should be blocked
        viewModel.save("second")
        testDispatcher.scheduler.advanceUntilIdle()
        // Only the first save should have been attempted
        coVerify(exactly = 1) { repository.save(any()) }
        // Complete the deferred
        deferred.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.saving.value, "saving should be false after save completes")
        // Still exactly one save
        coVerify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `only first save text is persisted when duplicate is blocked`() = runTest(testDispatcher) {
        val deferred = CompletableDeferred<Unit>()
        coEvery { repository.save(any()) } coAnswers {
            deferred.await()
        }

        viewModel.save("original version")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.saving.value, "saving should be true after first save")

        // Second save attempt is blocked — different text should NOT reach repository
        viewModel.save("second version")
        testDispatcher.scheduler.advanceUntilIdle()

        // Only "original version" was passed to the repository
        coVerify(exactly = 1) { repository.save("original version") }
        deferred.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.saving.value, "saving should be false after save completes")
        // Still only one save call
        coVerify(exactly = 1) { repository.save("original version") }
    }

    @Test
    fun `save emits Success after deferred completes`() = runTest(testDispatcher) {
        val deferred = CompletableDeferred<Unit>()
        coEvery { repository.save(any()) } coAnswers {
            deferred.await()
        }
        var result: SaveResult? = null
        val job = launch {
            viewModel.saveResult.collect {
                result = it
            }
        }
        viewModel.save("test deferred success")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.saving.value, "saving should be true before deferred completes")
        // Complete the deferred
        deferred.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.saving.value, "saving should be false after deferred completes")
        assertTrue(result is SaveResult.Success, "should emit Success after deferred completes")
        job.cancel()
    }

    // ── Clear behaviour ───────────────────────────────────────────────────────

    @Test
    fun `clear clears repository`() = runTest(testDispatcher) {
        viewModel.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { repository.clear() }
    }

    @Test
    fun `clear resets profile text`() = runTest(testDispatcher) {
        profileTextFlow.value = "my name is Nick"
        viewModel = UserProfileViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("my name is Nick", viewModel.profileText.value)

        viewModel.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", viewModel.profileText.value)
    }

    // ── SaveResult event ──────────────────────────────────────────────────────

    @Test
    fun `saveResult emits Error when repository throws`() = runTest(testDispatcher) {
        coEvery { repository.save(any()) } throws RuntimeException("DB error")
        viewModel = UserProfileViewModel(repository)

        var result: SaveResult? = null
        val job = launch {
            viewModel.saveResult.collect {
                result = it
            }
        }
        viewModel.save("test")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(result is SaveResult.Error, "should emit Error on exception, got: $result")
        if (result is SaveResult.Error) {
            assertEquals("DB error", (result as SaveResult.Error).message)
        }
        job.cancel()
    }

    @Test
    fun `saving is false after repository throws`() = runTest(testDispatcher) {
        coEvery { repository.save(any()) } throws RuntimeException("DB error")
        viewModel = UserProfileViewModel(repository)

        viewModel.save("test")
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.saving.value, "saving should be false after error")
    }

    // ── Structured profile ────────────────────────────────────────────────────

    @Test
    fun `structuredProfile reflects repository`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceUntilIdle()
        val yaml = UserProfileYaml(name = "Nick", role = "developer")
        structuredProfileFlow.value = yaml
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(yaml, viewModel.structuredProfile.value)
    }

    @Test
    fun `structuredProfile is null when not set`() = runTest(testDispatcher) {
        assertEquals(null, viewModel.structuredProfile.value)
    }
}
