package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.repository.ConversationRepository
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class MemoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val memoryRepository: MemoryRepository = mockk()
    private val conversationRepository: ConversationRepository = mockk()
    private val embeddingDao: MessageEmbeddingDao = mockk()
    private val embeddingEngine: EmbeddingEngine = mockk()

    private val coreMemoriesFlow = MutableStateFlow<List<CoreMemoryEntity>>(emptyList())
    private val episodicCountFlow = MutableStateFlow(0)
    private val episodicMemoriesFlow = MutableStateFlow<List<EpisodicMemoryEntity>>(emptyList())
    private val conversationsFlow = MutableStateFlow<List<ConversationEntity>>(emptyList())

    private lateinit var viewModel: MemoryViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { memoryRepository.observeCoreMemories() } returns coreMemoriesFlow
        every { memoryRepository.observeEpisodicCount() } returns episodicCountFlow
        every { memoryRepository.observeEpisodicMemories() } returns episodicMemoriesFlow
        every { conversationRepository.observeConversations() } returns conversationsFlow
        viewModel = MemoryViewModel(memoryRepository, conversationRepository, embeddingDao, embeddingEngine)
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

    private fun episodicMemory(id: String, content: String, conversationId: String = "conv-1") =
        EpisodicMemoryEntity(
            rowId = 0L,
            id = id,
            conversationId = conversationId,
            content = content,
            createdAt = System.currentTimeMillis(),
        )

    @Test
    fun `addCoreMemory calls repository with trimmed text`() = runTest {
        every { embeddingEngine.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        coEvery { memoryRepository.addCoreMemory(any(), any(), any()) } returns "id-1"

        viewModel.openAddDialog()
        viewModel.onAddDialogTextChange("  remember this  ")
        viewModel.addCoreMemory()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            memoryRepository.addCoreMemory(
                content = "remember this",
                source = "user",
                embeddingVector = floatArrayOf(0.1f, 0.2f),
            )
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
    fun `addCoreMemory does not call repository when embedding engine returns empty`() = runTest {
        every { embeddingEngine.embed(any()) } returns floatArrayOf()

        viewModel.openAddDialog()
        viewModel.onAddDialogTextChange("some text")
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

    @Test
    fun `episodicMemories state reflects repository flow`() = runTest {
        val states = mutableListOf<MemoryViewModel.MemoryUiState>()
        val collectJob = launch { viewModel.uiState.collect { states.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        val memories = listOf(episodicMemory("ep-1", "content one"))
        episodicMemoriesFlow.value = memories
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, states.last().episodicMemories.size)
        assertEquals("ep-1", states.last().episodicMemories.first().id)

        collectJob.cancel()
    }

    @Test
    fun `requestDeleteEpisodicMemory sets pendingDeleteEpisodicId`() = runTest {
        val states = mutableListOf<MemoryViewModel.MemoryUiState>()
        val collectJob = launch { viewModel.uiState.collect { states.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestDeleteEpisodicMemory("ep-42")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("ep-42", states.last().pendingDeleteEpisodicId)

        collectJob.cancel()
    }

    @Test
    fun `dismissDeleteEpisodicConfirmation clears pendingDeleteEpisodicId`() = runTest {
        val states = mutableListOf<MemoryViewModel.MemoryUiState>()
        val collectJob = launch { viewModel.uiState.collect { states.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestDeleteEpisodicMemory("ep-42")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(states.last().pendingDeleteEpisodicId)

        viewModel.dismissDeleteEpisodicConfirmation()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(states.last().pendingDeleteEpisodicId)

        collectJob.cancel()
    }

    @Test
    fun `deleteEpisodicMemory delegates to repository and clears pending id`() = runTest {
        coEvery { memoryRepository.deleteEpisodicMemory(any()) } just Runs

        val states = mutableListOf<MemoryViewModel.MemoryUiState>()
        val collectJob = launch { viewModel.uiState.collect { states.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestDeleteEpisodicMemory("ep-99")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteEpisodicMemory("ep-99")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { memoryRepository.deleteEpisodicMemory("ep-99") }
        assertNull(states.last().pendingDeleteEpisodicId)

        collectJob.cancel()
    }

    @Test
    fun `conversationTitles map reflects conversations flow`() = runTest {
        val states = mutableListOf<MemoryViewModel.MemoryUiState>()
        val collectJob = launch { viewModel.uiState.collect { states.add(it) } }
        testDispatcher.scheduler.advanceUntilIdle()

        conversationsFlow.value = listOf(
            ConversationEntity(id = "conv-1", title = "My Chat", createdAt = 0L, updatedAt = 0L),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("My Chat", states.last().conversationTitles["conv-1"])

        collectJob.cancel()
    }
}
