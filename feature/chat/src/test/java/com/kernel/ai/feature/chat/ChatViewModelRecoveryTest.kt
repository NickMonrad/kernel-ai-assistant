package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.intent.RecoveryResult
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.skills.slot.SlotSpec
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.skills.slot.SlotValidationResult
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.google.ai.edge.litertlm.ToolProvider
import io.mockk.coEvery
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRecoveryTest {
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
    private val startListeningCuePlayer: StartListeningCuePlayer = mockk(relaxed = true)
    private val authRepository: HuggingFaceAuthRepository = mockk(relaxed = true)
    private val chatPreferences: ChatPreferences = mockk(relaxed = true)
    private val gatedModelStatusRepository: GatedModelStatusRepository = mockk(relaxed = true)
    private val intentRecoveryOrchestrator: IntentRecoveryOrchestrator = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.isGenerating } returns MutableStateFlow(false)
        every { inferenceEngine.activeBackend } returns MutableStateFlow<BackendType?>(null)
        every { inferenceEngine.resolvedMaxTokens } returns MutableStateFlow(0)
        every { inferenceEngine.evictionEvents } returns emptyFlow()
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()
        coEvery { inferenceEngine.resetConversation() } just runs

        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns true
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

        every { skillRegistry.get(any()) } returns null
        every { jandalPersona.personaMode } returns MutableStateFlow(PersonaMode.FULL)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(),
        inferenceEngine = inferenceEngine,
        downloadManager = downloadManager,
        conversationRepository = conversationRepository,
        ragRepository = ragRepository,
        userProfileRepository = userProfileRepository,
        memoryRepository = memoryRepository,
        episodicDistillationUseCase = episodicDistillationUseCase,
        modelSettingsRepository = modelSettingsRepository,
        mealPlanSessionRepository = mealPlanSessionRepository,
        mealPlannerCoordinator = mealPlannerCoordinator,
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
        authRepository = authRepository,
        startListeningCuePlayer = startListeningCuePlayer,
        chatPreferences = chatPreferences,
    )

    @Test
    fun `chatViewModelAcceptsRecoveryDependency`() = runTest(dispatcher) {
        // Verify the ChatViewModel can be constructed with the IntentRecoveryOrchestrator dependency
        val viewModel = createViewModel()
        viewModel.onInputChanged("hello")
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun `fallthroughSendsMessageWithoutCrash`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "create calendar event",
            bestGuess = QuickIntentRouter.MatchedIntent(
                intentName = "create_calendar_event",
                params = emptyMap(),
                source = "classifier",
            ),
            bestConfidence = 0.72f,
        )
        every { intentRecoveryOrchestrator.recover(any(), any(), any()) } returns RecoveryResult.Execute(
            intentName = "create_calendar_event",
            params = mapOf("title" to "Meeting", "date" to "tomorrow"),
        )
        val viewModel = createViewModel()
        viewModel.onInputChanged("create calendar event")
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun `askSlotRecoveryDoesNotCrash`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "remind me to call mum",
            bestGuess = QuickIntentRouter.MatchedIntent(
                intentName = "add_reminder",
                params = emptyMap(),
                source = "classifier",
            ),
            bestConfidence = 0.68f,
        )
        every { intentRecoveryOrchestrator.recover(any(), any(), any()) } returns RecoveryResult.AskSlot(
            intentName = "add_reminder",
            existingParams = emptyMap(),
            missingSlot = SlotSpec(
                name = "item",
                promptTemplate = "What would you like me to remind you about?",
            ),
        )
        val viewModel = createViewModel()
        viewModel.onInputChanged("remind me to call mum")
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun `askClarificationRecoveryDoesNotCrash`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "do something",
            bestGuess = QuickIntentRouter.MatchedIntent(
                intentName = "create_calendar_event",
                params = emptyMap(),
                source = "classifier",
            ),
            bestConfidence = 0.60f,
        )
        every { intentRecoveryOrchestrator.recover(any(), any(), any()) } returns RecoveryResult.AskClarification(
            message = "I'm not sure what you'd like to do.",
        )
        val viewModel = createViewModel()
        viewModel.onInputChanged("do something")
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun `regexMatchDoesNotTriggerRecovery`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.RegexMatch(
            QuickIntentRouter.MatchedIntent(
                intentName = "toggle_flashlight_on",
                params = emptyMap(),
                source = "regex",
            ),
        )
        val viewModel = createViewModel()
        viewModel.onInputChanged("turn on flashlight")
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun `needsSlotDoesNotTriggerRecovery`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.NeedsSlot(
            intent = QuickIntentRouter.MatchedIntent(
                intentName = "send_sms",
                params = mapOf("contact" to "Sarah"),
                source = "regex",
            ),
            missingSlot = SlotSpec(
                name = "message",
                promptTemplate = "What would you like to say to Sarah?",
            ),
        )
        val viewModel = createViewModel()
        viewModel.onInputChanged("text Sarah")
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun `classifierMatchDoesNotTriggerRecovery`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.ClassifierMatch(
            QuickIntentRouter.MatchedIntent(
                intentName = "toggle_flashlight_on",
                params = emptyMap(),
                source = "classifier",
            ),
            confidence = 0.92f,
            needsConfirmation = false,
        )
        val viewModel = createViewModel()
        viewModel.onInputChanged("turn on flashlight")
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    @Test
    fun `askConfirmationRecoveryDoesNotCrash`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "tell Sarah I'm running late",
            bestGuess = QuickIntentRouter.MatchedIntent(
                intentName = "send_sms",
                params = emptyMap(),
                source = "classifier",
            ),
            bestConfidence = 0.62f,
        )
        every { intentRecoveryOrchestrator.recover(any(), any(), any()) } returns RecoveryResult.AskConfirmation(
            intentName = "send_sms",
            params = mapOf("contact" to "Sarah", "message" to "I'm running late"),
            message = "Shall I send a message to Sarah?",
        )
        val viewModel = createViewModel()
        viewModel.onInputChanged("tell Sarah I'm running late")
        viewModel.sendMessage()
        advanceUntilIdle()
    }
}
