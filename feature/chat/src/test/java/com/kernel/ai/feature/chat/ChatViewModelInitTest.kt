package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.DEFAULT_SYSTEM_PROMPT
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.MINIMAL_SYSTEM_PROMPT
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeMode
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.skills.slot.SlotValidationResult
import com.kernel.ai.core.skills.mealplan.MealPlannerActivity
import com.kernel.ai.core.skills.mealplan.MealPlannerActivityState
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.skills.mealplan.MealPlannerReply
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
import io.mockk.clearMocks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelInitTest {
    private val dispatcher = StandardTestDispatcher()

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

        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        every { inferenceEngine.isGenerating } returns MutableStateFlow(false)
        every { inferenceEngine.activeBackend } returns MutableStateFlow<BackendType?>(null)
        every { inferenceEngine.resolvedMaxTokens } returns MutableStateFlow(0)
        every { inferenceEngine.evictionEvents } returns emptyFlow()
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()
        coEvery { inferenceEngine.resetConversation() } just runs

        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

        coEvery { conversationRepository.createConversation() } returns "conv-new"
        coEvery { conversationRepository.getConversation(any()) } answers {
            val id = firstArg<String>()
            ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L)
        }
        coEvery { conversationRepository.getMessagesOnce(any()) } returns emptyList()
        every { conversationRepository.observeConversationById(any()) } answers {
            val id = firstArg<String>()
            flowOf(ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L))
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
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `eviction reinit does not wait when app is already foregrounded`() {
        assertFalse(shouldWaitForAppForegroundAfterEviction(androidx.lifecycle.Lifecycle.State.STARTED))
        assertFalse(shouldWaitForAppForegroundAfterEviction(androidx.lifecycle.Lifecycle.State.RESUMED))
    }

    @Test
    fun `eviction reinit waits for foreground when app is backgrounded`() {
        assertTrue(shouldWaitForAppForegroundAfterEviction(androidx.lifecycle.Lifecycle.State.CREATED))
        assertTrue(shouldWaitForAppForegroundAfterEviction(androidx.lifecycle.Lifecycle.State.INITIALIZED))
    }

    @Test
    fun `fresh chat initialization resets inherited inference session`() = runTest(dispatcher) {
        ChatViewModel(
          savedStateHandle = SavedStateHandle(),
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

        advanceUntilIdle()

        coVerify(exactly = 1) { conversationRepository.createConversation() }
        coVerify(exactly = 1) { inferenceEngine.resetConversation() }
    }

    @Test
    fun `restored chat initialization does not reset current inference session`() = runTest(dispatcher) {
        ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.createConversation() }
        coVerify(exactly = 0) { inferenceEngine.resetConversation() }
    }

    @Test
    fun `closing chat never shuts down process scoped inference engine`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()
        clearViewModel(viewModel)

        coVerify(exactly = 0) { inferenceEngine.shutdown() }
    }


    @Test
    fun `restored chat initialization shows actionable meal planner resume prompt without persisting it`() = runTest(dispatcher) {
        val prompt = "I still need to finish Day 2 of 3. Say 'generate recipes' to continue or 'cancel' to stop."
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true

        coEvery { mealPlannerCoordinator.activeSessionReply("conv-existing") } returns MealPlannerReply(prompt)

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()

        coVerify(exactly = 1) { mealPlannerCoordinator.activeSessionReply("conv-existing") }
        coVerify(exactly = 0) { conversationRepository.addMessage("conv-existing", "assistant", prompt, any(), any()) }
        coVerify(exactly = 0) { ragRepository.indexMessage(any(), any(), any()) }
        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals(prompt, state.messages.last().content)
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `restored chat initialization restores planner activity from canonical session state`() = runTest(dispatcher) {
        val activity = MealPlannerActivity(
            title = "Meal plan ready",
            subtitle = "Say 'show current plan' or 'done meal planning'.",
            state = MealPlannerActivityState.WAITING,
        )
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        coEvery { mealPlannerCoordinator.activeSessionActivity("conv-existing") } returns activity

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()

        assertEquals(activity, viewModel.mealPlannerActivity.value)
    }

    @Test
    fun `restored chat initialization syncs placeholder title to canonical meal plan name`() = runTest(dispatcher) {
        val snapshot = MealPlanSnapshot(
            sessionId = "session-1",
            conversationId = "conv-existing",
            displayName = "Meal Plan 2026-05-17 (MP-001)",
            status = MealPlanSessionStatus.PLAN_REVIEW,
            peopleCount = 2,
            daysCount = 3,
            dietaryRestrictions = emptyList(),
            proteinPreferences = listOf("chicken"),
            cuisinePreferences = emptyList(),
            favouriteRecipeMode = FavouriteRecipeMode.NONE,
            activeDayIndex = null,
            pendingGenerationKind = null,
            pendingGenerationDayIndex = null,
            planVersion = 1,
            finalSummaryWritten = false,
            createdAt = 1L,
            updatedAt = 1L,
            completedAt = null,
            cancelledAt = null,
            days = listOf(
                MealPlanSnapshotDay(
                    id = "day-1",
                    dayIndex = 0,
                    title = "Chicken stir-fry",
                    summary = "Quick bowl",
                    proteinTags = listOf("chicken"),
                    status = MealPlanDayStatus.DRAFTED,
                    currentRecipeVersion = null,
                    attemptCount = 0,
                    lastErrorCode = null,
                    lastErrorMessage = null,
                    currentRecipe = null,
                    recipeKey = null,
                    isFavouriteRecipe = false,
                ),
            ),
        )
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        coEvery { conversationRepository.getConversation("conv-existing") } returns
            ConversationEntity(id = "conv-existing", title = "plan meals…", createdAt = 1L, updatedAt = 1L)
        coEvery { mealPlanSessionRepository.getActiveSession("conv-existing") } returns snapshot

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()

        coVerify(exactly = 1) { conversationRepository.renameConversation("conv-existing", snapshot.displayName) }
        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals(snapshot.displayName, state.conversationTitle)
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `restored chat initialization does not duplicate latest meal planner resume prompt`() = runTest(dispatcher) {
        val prompt = "I still need to finish Day 2 of 3. Say 'generate recipes' to continue or 'cancel' to stop."
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true

        coEvery { mealPlannerCoordinator.activeSessionReply("conv-existing") } returns MealPlannerReply(prompt)
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "assistant",
                content = prompt,
                thinkingText = null,
                timestamp = 1L,
            ),
        )

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()

        coVerify(exactly = 1) { mealPlannerCoordinator.activeSessionReply("conv-existing") }
        coVerify(exactly = 0) { conversationRepository.addMessage("conv-existing", "assistant", prompt, any(), any()) }
        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals(1, state.messages.size)
            assertEquals(prompt, state.messages.last().content)
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `restored chat initialization re-shows planner guidance after later conversation turns without writing duplicates`() = runTest(dispatcher) {
        val prompt = "I still need to finish Day 2 of 3. Say 'generate recipes' to continue or 'cancel' to stop."
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        coEvery { mealPlannerCoordinator.activeSessionReply("conv-existing") } returns MealPlannerReply(prompt)
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "assistant",
                content = prompt,
                thinkingText = null,
                timestamp = 1L,
            ),
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-2",
                conversationId = "conv-existing",
                role = "user",
                content = "continue please",
                thinkingText = null,
                timestamp = 2L,
            ),
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-3",
                conversationId = "conv-existing",
                role = "assistant",
                content = "Still working on Day 2 — give me a moment.",
                thinkingText = null,
                timestamp = 3L,
            ),
        )

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()

        coVerify(exactly = 1) { mealPlannerCoordinator.activeSessionReply("conv-existing") }
        coVerify(exactly = 0) { conversationRepository.addMessage("conv-existing", "assistant", prompt, any(), any()) }
        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals(4, state.messages.size)
            assertEquals(prompt, state.messages.last().content)
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `actions fallthrough initial query uses minimal prompt and skips rag`() = runTest(dispatcher) {
        val systemPrompts = mutableListOf<String>()
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Token("Hi"), GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } answers {
            systemPrompts += firstArg<String>()
        }
        coEvery { ragRepository.getRelevantContext(any(), any(), any()) } returns "memory context"
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg", "assistant-msg")
        every { quickIntentRouter.route("and bred to my last") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "and bred to my last")

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "minimalContext" to true,
            ),
        )

        val viewModel = ChatViewModel(
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

        advanceUntilIdle()
        viewModel.onInputChanged("and bred to my last")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue(
            systemPrompts.any { prompt ->
                prompt.contains(MINIMAL_SYSTEM_PROMPT) &&
                    !prompt.contains("[User Profile]") &&
                    !prompt.contains("[Previous conversation context]")
            },
            systemPrompts.joinToString(separator = "\n---\n"),
        )
        assertTrue(savedStateHandle.get<Boolean>("minimalContext") == false)
        coVerify(exactly = 0) { ragRepository.getRelevantContext(any(), any(), any()) }
    }


    @Test
    fun `starting new conversation restores full prompt after minimal handoff`() = runTest(dispatcher) {
        val systemPrompts = mutableListOf<String>()
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Token("Hi"), GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } answers {
            systemPrompts += firstArg<String>()
        }
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg", "assistant-msg")
        every { quickIntentRouter.route("and bred to my last") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "and bred to my last")

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("minimalContext" to true)),
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

        advanceUntilIdle()
        viewModel.onInputChanged("and bred to my last")
        viewModel.sendMessage()
        advanceUntilIdle()
        viewModel.startNewConversation()
        advanceUntilIdle()
        assertTrue(systemPrompts.last().contains(DEFAULT_SYSTEM_PROMPT), systemPrompts.joinToString(separator = "\n---\n"))
    }

    @Test
    fun `initial query is not resent after restored conversation reload`() = runTest(dispatcher) {
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "user",
                content = "and ice cream to my last",
                thinkingText = null,
                timestamp = 1L,
            ),
        )

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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

        advanceUntilIdle()
        viewModel.submitInitialQueryIfNeeded("and ice cream to my last")
        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.addMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `submitInitialQueryIfNeeded resets state between independent commands`() = runTest(dispatcher) {
        val systemPrompts = mutableListOf<String>()
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Token("ok"), GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } answers {
            systemPrompts += firstArg<String>()
        }
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg-1", "assistant-msg-1", "user-msg-2", "assistant-msg-2",
                    "user-msg-3", "assistant-msg-3")
        every { quickIntentRouter.route("remember that I prefer dark mode") } returns
            QuickIntentRouter.RouteResult.RegexMatch(
                QuickIntentRouter.MatchedIntent(
                    intentName = "save_memory",
                    params = mapOf("content" to "I prefer dark mode"),
                    source = "regex",
                ),
            )
        every { quickIntentRouter.route("what time is it") } returns
            QuickIntentRouter.RouteResult.RegexMatch(
                QuickIntentRouter.MatchedIntent(
                    intentName = "get_time",
                    params = emptyMap(),
                    source = "regex",
                ),
            )
        every { skillRegistry.get("run_intent") } returns mockk(relaxed = true) {
            coEvery { execute(any()) } returns SkillResult.Success("Done")
        }
        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(),
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

        advanceUntilIdle()

        // Command 1: "remember that I prefer dark mode" — triggers needsConversationReset
        viewModel.submitInitialQueryIfNeeded("remember that I prefer dark mode")
        advanceUntilIdle()
        clearMocks(slotFillerManager, inferenceEngine)
        // Re-stub after clear
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.isGenerating } returns MutableStateFlow(false)
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()
        coEvery { inferenceEngine.resetConversation() } just runs
        coEvery { slotFillerManager.cancel() } just runs

        // Command 2: "what time is it" — must also trigger conversation reset for independent command
        viewModel.submitInitialQueryIfNeeded("what time is it")
        advanceUntilIdle()
        coVerify(atLeast = 1) { slotFillerManager.cancel() }
        coVerify(atLeast = 1) { inferenceEngine.resetConversation() }
    }

    @Test
    fun `skipping duplicate restored initial query clears minimal handoff for next send`() = runTest(dispatcher) {
        val systemPrompts = mutableListOf<String>()
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Token("Hi"), GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } answers {
            systemPrompts += firstArg<String>()
        }
        coEvery { ragRepository.getRelevantContext(any(), any(), any()) } returns "memory context"
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg-1", "assistant-msg-1", "user-msg-2", "assistant-msg-2")
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "user",
                content = "and ice cream to my last",
                thinkingText = null,
                timestamp = 1L,
            ),
        )
        every { quickIntentRouter.route("follow up question") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "follow up question")

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "conversationId" to "conv-existing",
                "minimalContext" to true,
            ),
        )
        val viewModel = ChatViewModel(
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

        advanceUntilIdle()
        viewModel.submitInitialQueryIfNeeded("and ice cream to my last")
        assertTrue(savedStateHandle.get<Boolean>("minimalContext") == false)

        viewModel.onInputChanged("follow up question")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(atLeast = 1) { ragRepository.getRelevantContext("follow up question", any(), any()) }
        assertTrue(
            systemPrompts.any { it.contains(DEFAULT_SYSTEM_PROMPT) },
            systemPrompts.joinToString(separator = "\n---\n"),
        )
    }

    @Test
    fun `blank response retries without RAG context on first zero-token generation`() = runTest(dispatcher) {
        val prompts = mutableListOf<String>()
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        // First call: 0 tokens (blank). Second call (retry): normal response.
        every { inferenceEngine.generate(capture(prompts)) } returnsMany listOf(
            flowOf(GenerationResult.Complete(durationMs = 1L)),
            flowOf(GenerationResult.Token("Got it."), GenerationResult.Complete(durationMs = 1L)),
        )
        coEvery { ragRepository.getRelevantContext(any(), any(), any()) } returns "RAG memory context"
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg-id", "assistant-msg-id")
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(input = "hello")

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(),
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
        advanceUntilIdle()

        viewModel.onInputChanged("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        // First prompt included RAG context; retry prompt must not.
        assertTrue(prompts.size == 2, "Expected 2 generate() calls, got ${prompts.size}")
        assertTrue(prompts[0].contains("RAG memory context"), "First prompt should contain RAG context")
        assertTrue(!prompts[1].contains("RAG memory context"), "Retry prompt must not contain RAG context")

        // Final assistant message saved to DB must be the retry response (not a blank or fallback).
        coVerify(atLeast = 1) { conversationRepository.addMessage(any(), eq("assistant"), eq("Got it."), any(), any()) }
    }

    @Test
    fun `blank response shows fallback when retry also produces zero tokens`() = runTest(dispatcher) {
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        // Both calls produce 0 tokens.
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Complete(durationMs = 1L))
        coEvery { ragRepository.getRelevantContext(any(), any(), any()) } returns "RAG memory context"
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg-id", "assistant-fallback-id")
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(input = "hello")

        val viewModel = ChatViewModel(
          savedStateHandle = SavedStateHandle(),
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
        advanceUntilIdle()

        viewModel.onInputChanged("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Fallback message must be persisted to DB.
        coVerify(atLeast = 1) {
            conversationRepository.addMessage(
                any(), eq("assistant"), match { it.contains("lost my train of thought") }, any(), any(),
            )
        }
        // User message must be indexed into RAG even when fallback fires.
        coVerify(atLeast = 1) { ragRepository.indexMessage("user-msg-id", any(), "hello") }
        // Fallback text must not be indexed.
        coVerify(exactly = 0) { ragRepository.indexMessage("assistant-fallback-id", any(), any()) }
    }

    private fun clearViewModel(viewModel: ChatViewModel) {
        val method = androidx.lifecycle.ViewModel::class.java
            .getDeclaredMethod("clear\$lifecycle_viewmodel_release")
        method.isAccessible = true
        method.invoke(viewModel)
    }

    // Replaced by ChatViewModelRetryStateMachineTest Scenario B — with a real
    // KernelAIToolSet, the terminal-tool-flow path exercises the correct state machine.

    @Test
    fun `independent commands do not leak previous message history into system prompt`() = runTest(dispatcher) {
        val systemPrompts = mutableListOf<String>()
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.isGenerating } returns MutableStateFlow(false)
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Token("ok"), GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } answers {
            systemPrompts += firstArg<String>()
        }
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()
        coEvery { inferenceEngine.resetConversation() } just runs
        // Force GPU backend so needsHistoryReplay is forced true every turn,
        // testing that wasConversationReset correctly suppresses history replay.
        every { inferenceEngine.activeBackend } returns MutableStateFlow(BackendType.GPU)
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg-1", "assistant-msg-1", "user-msg-2", "assistant-msg-2")
        // Both inputs fall through to LLM (no QIR match) to trigger the history replay path
        every { quickIntentRouter.route(any()) } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "ignored")

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Command 1: independent command that goes through LLM
        viewModel.submitInitialQueryIfNeeded("set a timer for 2 hours")
        advanceUntilIdle()

        // Command 2: independent command — must NOT leak Command 1's text into system prompt history
        viewModel.submitInitialQueryIfNeeded("what time is it")
        advanceUntilIdle()

        // The last system prompt should be for Command 2
        // It must NOT contain the first command's user text in the history turns
        val lastSystemPrompt = systemPrompts.lastOrNull()
        assertNotNull(lastSystemPrompt, "Expected at least one updateSystemPrompt call")
        assertFalse(
            lastSystemPrompt!!.contains("set a timer for 2 hours"),
            "Independent command history replay must not leak previous commands into system prompt",
        )
    }

    @Test
    fun `fresh viewmodel on warm engine hydrates model and settings without reinitialising`() = runTest(dispatcher) {
        val persisted = ModelSettingsEntity(
            modelId = "gemma_4_e4b",
            contextWindowSize = 8192,
            temperature = 0.42f,
            topP = 0.7f,
            topK = 96,
            showThinkingProcess = false,
            speculativeDecodingEnabled = true,
            updatedAt = 2L,
        )
        // Engine reports ready BEFORE the ViewModel's init path runs (#1459).
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        every { downloadManager.downloadStates } returns MutableStateFlow(
            mapOf(KernelModel.GEMMA_4_E4B to DownloadState.Downloaded("/path/to/model")),
        )
        coEvery { downloadManager.preferredConversationModel() } returns KernelModel.GEMMA_4_E4B
        coEvery { downloadManager.getModelPath(any()) } returns "/path/to/model"
        coEvery { modelSettingsRepository.getSettings(any()) } returns persisted

        val viewModel = createViewModel()
        // currentModel is stateIn(WhileSubscribed) — collect to observe hydration.
        val currentModels = mutableListOf<KernelModel?>()
        val collector = launch { viewModel.currentModel.collect { currentModels += it } }
        advanceUntilIdle()

        assertEquals(KernelModel.GEMMA_4_E4B, currentModels.last())
        assertEquals(persisted, viewModel.activeModelSettings.value)

        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertNotNull(state.modelCapabilities)
        assertEquals(persisted.temperature, state.temperature)
        assertEquals(persisted.topP, state.topP)
        assertEquals(persisted.topK, state.topK)
        assertFalse(state.showThinkingProcess)

        // The already-ready path must never re-initialise the warm engine.
        coVerify(exactly = 0) { inferenceEngine.initialize(any()) }
        collector.cancel()
    }

    @Test
    fun `sendMessage waiting on warmup mutex hydrates state without double initialising`() = runTest(dispatcher) {
        val persisted = ModelSettingsEntity(
            modelId = "gemma_4_e4b",
            contextWindowSize = 8192,
            temperature = 0.42f,
            topP = 0.7f,
            topK = 96,
            showThinkingProcess = false,
            speculativeDecodingEnabled = true,
            updatedAt = 2L,
        )
        val isReadyFlow = MutableStateFlow(false)
        // Engine warms up DURING this test. getSettings blocks until the test flips
        // isReady, so initEngineWhenReady holds the init mutex while sendMessage's
        // initGemma4 waits on it — the exact race that hits initGemma4's
        // already-ready branch (#1459).
        val settingsGate = CompletableDeferred<Unit>()
        every { inferenceEngine.isReady } returns isReadyFlow
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        every { downloadManager.downloadStates } returns MutableStateFlow(
            mapOf(KernelModel.GEMMA_4_E4B to DownloadState.Downloaded("/path/to/model")),
        )
        coEvery { downloadManager.preferredConversationModel() } returns KernelModel.GEMMA_4_E4B
        coEvery { downloadManager.getModelPath(any()) } returns "/path/to/model"
        coEvery { modelSettingsRepository.getSettings(any()) } coAnswers { settingsGate.await(); persisted }
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(input = "hello")
        every { inferenceEngine.generate(any()) } returns flowOf(GenerationResult.Complete(durationMs = 1L))

        val viewModel = createViewModel()
        // currentModel is stateIn(WhileSubscribed) — collect to observe hydration.
        val currentModels = mutableListOf<KernelModel?>()
        val collector = launch { viewModel.currentModel.collect { currentModels += it } }
        runCurrent()
        // initEngineWhenReady is now parked inside getSettings, holding the init mutex.

        viewModel.onInputChanged("hello")
        viewModel.sendMessage()
        runCurrent()
        // sendMessage's initGemma4 is now waiting on the mutex.

        // Engine completes warm-up while initGemma4 waits.
        isReadyFlow.value = true
        settingsGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(KernelModel.GEMMA_4_E4B, currentModels.last())
        assertEquals(persisted, viewModel.activeModelSettings.value)

        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertNotNull(state.modelCapabilities)
        assertEquals(persisted.temperature, state.temperature)
        assertFalse(state.showThinkingProcess)

        // Only initEngineWhenReady's cold path may initialise — initGemma4's
        // already-ready branch must never re-initialise the warm engine.
        coVerify(exactly = 1) { inferenceEngine.initialize(any()) }
        collector.cancel()
    }

    @Test
    fun `warm engine settings read failure surfaces error without reinitialising`() = runTest(dispatcher) {
        val persisted = ModelSettingsEntity(
            modelId = "gemma_4_e4b",
            contextWindowSize = 8192,
            temperature = 0.42f,
            topP = 0.7f,
            topK = 96,
            showThinkingProcess = false,
            speculativeDecodingEnabled = true,
            updatedAt = 2L,
        )
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        every { downloadManager.downloadStates } returns MutableStateFlow(
            mapOf(KernelModel.GEMMA_4_E4B to DownloadState.Downloaded("/path/to/model")),
        )
        coEvery { downloadManager.preferredConversationModel() } returns KernelModel.GEMMA_4_E4B
        coEvery { downloadManager.getModelPath(any()) } returns "/path/to/model"
        // Settings read failure must be caught and surfaced like the cold path, not
        // escape the init coroutine (review #1460). Only the FIRST read (the init
        // hydration) fails — later reads from the persona-collector prompt builder
        // are unrelated pre-existing behaviour.
        var settingsReads = 0
        coEvery { modelSettingsRepository.getSettings(any()) } coAnswers {
            if (settingsReads++ == 0) throw RuntimeException("db down") else persisted
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertTrue(
            state.error!!.contains("Failed to load AI model"),
            "settings read failure on warm engine must surface an error, got: ${state.error}",
        )
        coVerify(exactly = 0) { inferenceEngine.initialize(any()) }
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

    private fun invokeOnCleared(viewModel: ChatViewModel) {
        val method = ChatViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(viewModel)
    }
}
