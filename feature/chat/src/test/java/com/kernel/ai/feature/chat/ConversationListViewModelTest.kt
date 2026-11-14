package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.inference.setup.SetupWizardProgress
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConversationListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val repository: ConversationRepository = mockk(relaxed = true)
    private val chatPreferences: ChatPreferences = mockk(relaxed = true)
    private val downloadManager: ModelDownloadManager = mockk(relaxed = true)
    private val downloadNetworkPreferences: DownloadNetworkPreferences = mockk(relaxed = true)
    private val setupWizardPreferences: SetupWizardPreferences = mockk(relaxed = true)

    /** Total conversations, archived included. Tests set this before creating the view model. */
    private val conversationCount = MutableStateFlow(0)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeActive() } returns flowOf(emptyList())
        every { repository.observeArchived() } returns flowOf(emptyList())
        every { repository.observeConversationCount() } returns conversationCount
        every { chatPreferences.chatsWelcomeDismissed } returns flowOf(false)
        every { chatPreferences.tabHintsDismissed } returns flowOf(false)
        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.WIFI_ONLY)
        every { setupWizardPreferences.progress } returns flowOf(SetupWizardProgress(decided = true, completed = true))
        every { downloadManager.waitingForWifi } returns MutableStateFlow(emptySet())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first run keeps the welcome until a conversation exists`() {
        ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { chatPreferences.setChatsWelcomeDismissed(any()) }

        conversationCount.value = 1
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { chatPreferences.setChatsWelcomeDismissed(true) }
    }

    @Test
    fun `returning user whose chats are all archived never sees the welcome again`() {
        // Active list is empty, but the user has archived conversations: the welcome must stay hidden.
        conversationCount.value = 3
        ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { chatPreferences.setChatsWelcomeDismissed(true) }
    }

    @Test
    fun `hiding the welcome persists the dismissal`() {
        val viewModel = ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        viewModel.hideWelcome()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { chatPreferences.setChatsWelcomeDismissed(true) }
    }

    @Test
    fun `dismissing tab hints persists only the tab-hint flag`() {
        val viewModel = ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        viewModel.dismissTabHints()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { chatPreferences.setTabHintsDismissed(true) }
        coVerify(exactly = 0) { chatPreferences.setChatsWelcomeDismissed(true) }
    }

    @Test
    fun `welcome download action uses the same start path as the chat recovery card`() {
        val viewModel = ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        viewModel.downloadAssistantModel(KernelModel.GEMMA_4_E4B)
        verify(exactly = 1) { downloadManager.startDownload(KernelModel.GEMMA_4_E4B, false, any()) }
    }

    @Test
    fun `welcome state is unknown until the conversation count has loaded`() {
        every { repository.observeConversationCount() } returns MutableSharedFlow()
        val viewModel = ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        val collector = CoroutineScope(dispatcher).launch { viewModel.chatsWelcomeDismissed.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.chatsWelcomeDismissed.value)
        collector.cancel()
    }

    @Test
    fun `returning user with conversations never sees the welcome, even before the preference is written`() {
        every { chatPreferences.chatsWelcomeDismissed } returns flowOf(false)
        conversationCount.value = 2
        val viewModel = ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        val collector = CoroutineScope(dispatcher).launch { viewModel.chatsWelcomeDismissed.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.chatsWelcomeDismissed.value)
        collector.cancel()
    }

    @Test
    fun `first run with no conversations shows the welcome once both values have loaded`() {
        every { chatPreferences.chatsWelcomeDismissed } returns flowOf(false)
        val viewModel = ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        val collector = CoroutineScope(dispatcher).launch { viewModel.chatsWelcomeDismissed.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.chatsWelcomeDismissed.value)
        collector.cancel()
    }

    @Test
    fun `hidden welcome stays hidden when there are no conversations`() {
        every { chatPreferences.chatsWelcomeDismissed } returns flowOf(true)
        val viewModel = ConversationListViewModel(repository, chatPreferences, downloadManager, downloadNetworkPreferences, setupWizardPreferences)
        val collector = CoroutineScope(dispatcher).launch { viewModel.chatsWelcomeDismissed.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.chatsWelcomeDismissed.value)
        collector.cancel()
    }
}
