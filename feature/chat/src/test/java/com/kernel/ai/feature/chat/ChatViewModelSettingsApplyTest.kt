package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceException
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.skills.slot.SlotValidationResult
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.memory.prefs.ChatPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSettingsApplyTest {
    private val dispatcher = StandardTestDispatcher()

    // Mutable flows we can update across the test lifecycle.
    private val isReadyFlow = MutableStateFlow(false)
    private val isGeneratingFlow = MutableStateFlow(false)
    private val activeBackendFlow = MutableStateFlow<BackendType?>(null)
    private val resolvedMaxTokensFlow = MutableStateFlow(0)
    private val downloadStatesFlow = MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
    private val downloadSourcesFlow = MutableStateFlow<Map<KernelModel, DownloadSource>>(emptyMap())

    private val inferenceEngine: InferenceEngine = mockk(relaxed = true)
    private val downloadManager: ModelDownloadManager = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val ragRepository: RagRepository = mockk(relaxed = true)
    private val userProfileRepository: UserProfileRepository = mockk(relaxed = true)
    private val memoryRepository: MemoryRepository = mockk(relaxed = true)
    private val mealPlanSessionRepository: MealPlanSessionRepository = mockk(relaxed = true)
    private val episodicDistillationUseCase: EpisodicDistillationUseCase = mockk(relaxed = true)
    private val modelSettingsRepository: ModelSettingsRepository = mockk(relaxed = true)
    private val mealPlannerCoordinator: MealPlannerCoordinator = mockk(relaxed = true)
    private val skillRegistry: SkillRegistry = mockk(relaxed = true)
    private val skillExecutor: SkillExecutor = mockk(relaxed = true)
    private val quickIntentRouter: QuickIntentRouter = mockk(relaxed = true)
    private val slotFillerManager: SlotFillerManager = mockk(relaxed = true)
    private val slotValidationRegistry: SlotValidationRegistry = mockk(relaxed = true)
    private val kernelAIToolSet: KernelAIToolSet = mockk(relaxed = true)
    private val toolProvider: ToolProvider = mockk(relaxed = true)
    private val embeddingEngine: EmbeddingEngine = mockk(relaxed = true)
    private val voiceInputController: VoiceInputController = mockk(relaxed = true)
    private val voiceOutputController: VoiceOutputController = mockk(relaxed = true)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk(relaxed = true)
    private val jandalPersona: JandalPersona = mockk(relaxed = true)
    private val nzTruthSeedingService: NzTruthSeedingService = mockk(relaxed = true)
    private val verboseLoggingPreferenceUseCase: VerboseLoggingPreferenceUseCase = mockk(relaxed = true)
    private val gatedModelStatusRepository: GatedModelStatusRepository = mockk(relaxed = true)
    private val startListeningCuePlayer: StartListeningCuePlayer = mockk(relaxed = true)
    private val authRepository: HuggingFaceAuthRepository = mockk(relaxed = true)
    private val chatPreferences: ChatPreferences = mockk(relaxed = true)
    private val intentRecoveryOrchestrator: IntentRecoveryOrchestrator = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        // Reset mutable flows
        isReadyFlow.value = false
        isGeneratingFlow.value = false
        activeBackendFlow.value = null
        resolvedMaxTokensFlow.value = 0
        downloadStatesFlow.value = emptyMap()
        downloadSourcesFlow.value = emptyMap()

        every { inferenceEngine.isReady } returns isReadyFlow
        every { inferenceEngine.isGenerating } returns isGeneratingFlow
        every { inferenceEngine.activeBackend } returns activeBackendFlow
        every { inferenceEngine.resolvedMaxTokens } returns resolvedMaxTokensFlow
        every { inferenceEngine.evictionEvents } returns emptyFlow()
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()
        coEvery { inferenceEngine.resetConversation() } just runs
        coEvery { inferenceEngine.reconfigureConversation(any()) } just runs
        coEvery { inferenceEngine.cancelGeneration() } just runs

        every { downloadManager.downloadStates } returns downloadStatesFlow
        every { downloadManager.downloadSources } returns downloadSourcesFlow
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

        coEvery { conversationRepository.createConversation() } returns "conv-new"
        coEvery { conversationRepository.getConversation(any()) } returns
            com.kernel.ai.core.memory.entity.ConversationEntity(
                id = "conv-new", title = null, createdAt = 1L, updatedAt = 1L,
            )
        coEvery { conversationRepository.getMessagesOnce(any()) } returns emptyList()
        every { conversationRepository.observeConversationById(any()) } answers {
            val id = firstArg<String>()
            flowOf(com.kernel.ai.core.memory.entity.ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L))
        }

        every { authRepository.isAuthenticated } returns MutableStateFlow(false)
        every { chatPreferences.fontSize } returns flowOf(1)
        every { chatPreferences.bubbleTheme } returns flowOf("system")
        every { chatPreferences.userFontColor } returns flowOf(null)
        every { chatPreferences.assistantFontColor } returns flowOf(null)
        every { chatPreferences.wallpaperType } returns flowOf("none")
        every { chatPreferences.wallpaperColor } returns flowOf(null)
        every { chatPreferences.wallpaperImageUri } returns flowOf(null)
        every { chatPreferences.copyToolCalls } returns flowOf(false)
        every { chatPreferences.copyThinking } returns flowOf(false)
        every { jandalPersona.personaMode } returns MutableStateFlow(PersonaMode.FULL)
        every { jandalPersona.currentPersonaMode } returns PersonaMode.FULL
        every { voiceOutputPreferences.spokenResponsesEnabled } returns MutableStateFlow(false)
        every { voiceOutputPreferences.autoSpeak } returns MutableStateFlow(false)
        every { voiceOutputPreferences.maxSpokenSentences } returns flowOf(0)
        every { nzTruthSeedingService.isSeeding } returns MutableStateFlow(false)
        every { nzTruthSeedingService.seedIfNeeded() } just runs
        coEvery { verboseLoggingPreferenceUseCase.loadAndApplyVerboseLoggingPreference() } just runs
        coEvery { modelSettingsRepository.saveSettings(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    /** Set up mocks so engine init can complete. */
    private fun setupInitPrerequisites() {
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        downloadStatesFlow.value = mapOf(
            KernelModel.GEMMA_4_E4B to DownloadState.Downloaded("/path/to/model"),
        )
        coEvery { downloadManager.preferredConversationModel() } returns KernelModel.GEMMA_4_E4B
        coEvery { downloadManager.getModelPath(any()) } returns "/path/to/model"
        coEvery { modelSettingsRepository.getSettings(any()) } returns ModelSettingsEntity(
            modelId = "gemma_4_e4b",
            contextWindowSize = 8192,
            temperature = 0.5f,
            topP = 0.85f,
            topK = 32,
            showThinkingProcess = false,
            speculativeDecodingEnabled = true,
            updatedAt = 2L,
        )
    }

    private fun clearViewModel(viewModel: ChatViewModel) {
        val method = androidx.lifecycle.ViewModel::class.java
            .getDeclaredMethod("clear\$lifecycle_viewmodel_release")
        method.isAccessible = true
        method.invoke(viewModel)
    }

    /** Common draft used across tests. */
    private fun draft(
        modelId: String = "gemma_4_e4b",
        contextWindowSize: Int = 4096,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 64,
        showThinkingProcess: Boolean = true,
        speculativeDecodingEnabled: Boolean = false,
    ) = ModelSettingsEntity(
        modelId = modelId,
        contextWindowSize = contextWindowSize,
        temperature = temperature,
        topP = topP,
        topK = topK,
        showThinkingProcess = showThinkingProcess,
        speculativeDecodingEnabled = speculativeDecodingEnabled,
        updatedAt = 1L,
    )

    @Test
    fun `apply without active model sets error`() = runTest(dispatcher) {
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        // #1459/review #1460: a warm engine hydrates from the loaded model path —
        // with no resolvable loaded model, the ViewModel genuinely has no active
        // model, so apply must fail.
        every { inferenceEngine.loadedModelPath } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        val d = draft()
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        coVerify(exactly = 0) { inferenceEngine.reconfigureConversation(any()) }
        coVerify(exactly = 1) { conversationRepository.createConversation() }
        coVerify(exactly = 0) { modelSettingsRepository.saveSettings(any()) }

        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("no active model", ignoreCase = true))

        clearViewModel(viewModel)
    }

    @Test
    fun `apply with valid state calls reconfigureConversation`() = runTest(dispatcher) {
        setupInitPrerequisites()

        val viewModel = createViewModel()
        advanceUntilIdle()
        isReadyFlow.value = true
        advanceUntilIdle()

        val d = draft()
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        coVerify(exactly = 1) { inferenceEngine.reconfigureConversation(any()) }
        coVerify(exactly = 2) { conversationRepository.createConversation() }
        coVerify(exactly = 1) { modelSettingsRepository.saveSettings(d) }

        clearViewModel(viewModel)
    }

    @Test
    fun `apply clears messages and creates new conversation`() = runTest(dispatcher) {
        setupInitPrerequisites()

        val viewModel = createViewModel()
        advanceUntilIdle()
        isReadyFlow.value = true
        advanceUntilIdle()

        val d = draft()
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertEquals(0, state.messages.size)
        assertNull(state.conversationTitle)
        assertNull(state.error)

        clearViewModel(viewModel)
    }

    @Test
    fun `apply with no model path sets error`() = runTest(dispatcher) {
        setupInitPrerequisites()

        val viewModel = createViewModel()
        advanceUntilIdle()
        isReadyFlow.value = true
        advanceUntilIdle()

        // Override getModelPath after init — activeModel is set but path retrieval fails
        coEvery { downloadManager.getModelPath(any()) } returns null

        val d = draft()
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        coVerify(exactly = 0) { inferenceEngine.reconfigureConversation(any()) }
        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("model file not found", ignoreCase = true))

        clearViewModel(viewModel)
    }

    @Test
    fun `activeModelSettings updated after apply`() = runTest(dispatcher) {
        setupInitPrerequisites()

        val viewModel = createViewModel()
        advanceUntilIdle()
        isReadyFlow.value = true
        advanceUntilIdle()

        val d = draft()
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        assertEquals(d, viewModel.activeModelSettings.value)
        clearViewModel(viewModel)
    }

    @Test
    fun `reconfigureConversation propagates conversation-scoped settings`() = runTest(dispatcher) {
        setupInitPrerequisites()

        val viewModel = createViewModel()
        advanceUntilIdle()
        isReadyFlow.value = true
        advanceUntilIdle()

        val d = draft(
            contextWindowSize = 4096,   // engine-scoped — should NOT propagate
            speculativeDecodingEnabled = false, // engine-scoped — should NOT propagate
        )
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            inferenceEngine.reconfigureConversation(
                withArg { config ->
                    // Conversation-scoped: come from draft
                    assertEquals(d.temperature, config.temperature)
                    assertEquals(d.topP, config.topP)
                    assertEquals(d.topK, config.topK)
                    assertEquals(d.showThinkingProcess, config.thinkingEnabled)

                    // Engine-scoped: come from active settings (setupInitPrerequisites returns
                    // contextWindowSize=8192, speculativeDecodingEnabled=true), NOT from draft
                    assertEquals("/path/to/model", config.modelPath)
                    assertEquals(8192, config.maxTokens)
                    assertEquals(true, config.speculativeDecodingEnabled)
                },
            )
        }
        clearViewModel(viewModel)
    }

    @Test
    fun `reconfigureConversation failure does not create conversation or persist settings`() = runTest(dispatcher) {
        setupInitPrerequisites()

        val viewModel = createViewModel()
        advanceUntilIdle()
        isReadyFlow.value = true
        advanceUntilIdle()

        // Capture the active settings BEFORE the failed apply, to verify they remain unchanged
        val activeBeforeApply = viewModel.activeModelSettings.value
        assertNotNull(activeBeforeApply)

        // Make reconfigure throw (simulates engine not initialized)
        coEvery { inferenceEngine.reconfigureConversation(any()) } throws
            com.kernel.ai.core.inference.InferenceException("Engine not initialized")

        val d = draft()
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        // reconfigure was called but failed
        coVerify(exactly = 1) { inferenceEngine.reconfigureConversation(any()) }

        // No new conversation should be created — only the init conversation exists
        coVerify(exactly = 1) { conversationRepository.createConversation() }

        // saveSettings must NOT be called when reconfigure fails
        coVerify(exactly = 0) { modelSettingsRepository.saveSettings(any()) }

        // activeModelSettings must remain at pre-apply values (NOT updated to draft)
        assertEquals(activeBeforeApply, viewModel.activeModelSettings.value)
        assertEquals(0.5f, viewModel.activeModelSettings.value!!.temperature)
        assertEquals(0.85f, viewModel.activeModelSettings.value!!.topP)

        // Error must be surfaced
        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Engine not initialized", ignoreCase = true))

        clearViewModel(viewModel)
    }
    @Test
    fun `isApplyingSettings tracks apply lifecycle`() = runTest(dispatcher) {
        setupInitPrerequisites()

        val viewModel = createViewModel()
        advanceUntilIdle()
        isReadyFlow.value = true
        advanceUntilIdle()

        assertFalse(viewModel.isApplyingSettings.value)

        val d = draft()
        viewModel.applyModelSettingsAndStartNewChat(d)
        advanceUntilIdle()

        assertFalse(viewModel.isApplyingSettings.value)
        clearViewModel(viewModel)
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): ChatViewModel = ChatViewModel(
        savedStateHandle = savedStateHandle,
        chatPreferences = chatPreferences,
        authRepository = authRepository,
        inferenceEngine = inferenceEngine,
        downloadManager = downloadManager,
        conversationRepository = conversationRepository,
        ragRepository = ragRepository,
        userProfileRepository = userProfileRepository,
        memoryRepository = memoryRepository,
        episodicDistillationUseCase = episodicDistillationUseCase,
        modelSettingsRepository = modelSettingsRepository,
        skillRegistry = skillRegistry,
        skillExecutor = skillExecutor,
        quickIntentRouter = quickIntentRouter,
        intentRecoveryOrchestrator = intentRecoveryOrchestrator,
        intentContractRegistry = IntentContractRegistry(),
        slotFillerManager = slotFillerManager,
            slotValidationRegistry = slotValidationRegistry,
        kernelAIToolSet = kernelAIToolSet,
        toolProvider = toolProvider,
        embeddingEngine = embeddingEngine,
        voiceInputController = voiceInputController,
        voiceOutputController = voiceOutputController,
        voiceOutputPreferences = voiceOutputPreferences,
        jandalPersona = jandalPersona,
        nzTruthSeedingService = nzTruthSeedingService,
        verboseLoggingPreferenceUseCase = verboseLoggingPreferenceUseCase,
        gatedModelStatusRepository = gatedModelStatusRepository,
        startListeningCuePlayer = startListeningCuePlayer,
        mealPlanSessionRepository = mealPlanSessionRepository,
        mealPlannerCoordinator = mealPlannerCoordinator,
    )
}
