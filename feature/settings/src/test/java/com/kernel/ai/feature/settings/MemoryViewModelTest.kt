package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class MemoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val memoryRepository: MemoryRepository = mockk()

    private val coreMemoriesFlow = MutableStateFlow<List<CoreMemoryEntity>>(emptyList())
    private val episodicCountFlow = MutableStateFlow(0)

    private lateinit var viewModel: MemoryViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { memoryRepository.observeCoreMemories() } returns coreMemoriesFlow
        every { memoryRepository.observeEpisodicCount() } returns episodicCountFlow
        viewModel = MemoryViewModel(memoryRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Suppress("unused")
    private fun coreMemory(id: String, content: String) = CoreMemoryEntity(
        id = id,
        content = content,
        createdAt = 0L,
        lastAccessedAt = 0L,
        source = "user",
    )

    @Test
    fun `addCoreMemory calls repository with trimmed text`() = runTest {
        coEvery { memoryRepository.addCoreMemory(any(), any(), any()) } returns "id-1"

        viewModel.openAddDialog()
        viewModel.onAddDialogTextChange("  remember this  ")
        viewModel.addCoreMemory()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            memoryRepository.addCoreMemory(content = "remember this", source = "user")
        }
    }

    @Test
    fun `addCoreMemory does not call repository when text is blank`() = runTest {
        viewModel.openAddDialog()
        viewModel.onAddDialogTextChange("   ")
        viewModel.addCoreMemory()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { memoryRepository.addCoreMemory(any(), any(), any()) }
    }

    @Test
    fun `deleteCoreMemory delegates to repository`() = runTest {
        coEvery { memoryRepository.deleteCoreMemory(any()) } just Runs

        viewModel.deleteCoreMemory("test-id")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { memoryRepository.deleteCoreMemory("test-id") }
    }

    @Test
    fun `clearEpisodicMemories calls repository and dismisses confirmation`() = runTest {
        coEvery { memoryRepository.clearEpisodicMemories() } just Runs

        // Subscribe to uiState so WhileSubscribed upstream starts emitting
        val states = mutableListOf<MemoryViewModel.MemoryUiState>()
        val collectJob = launch { viewModel.uiState.collect { states.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showClearEpisodicConfirmation()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(states.last().showClearConfirmation)

        viewModel.clearEpisodicMemories()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { memoryRepository.clearEpisodicMemories() }
        assertFalse(states.last().showClearConfirmation)

        collectJob.cancel()
    }

    @Test
    fun `dismissAddDialog clears dialog text`() = runTest {
        // Subscribe to uiState so WhileSubscribed upstream starts emitting
        val states = mutableListOf<MemoryViewModel.MemoryUiState>()
        val collectJob = launch { viewModel.uiState.collect { states.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.openAddDialog()
        viewModel.onAddDialogTextChange("some text")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissAddDialog()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = states.last()
        assertFalse(state.isAddDialogOpen)
        assertEquals("", state.addDialogText)

        collectJob.cancel()
    }
}
