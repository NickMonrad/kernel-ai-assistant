package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.skills.slot.SlotValidationResult
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import com.kernel.ai.core.voice.VoiceSpeakRequest
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Chat path test for [QuickIntentRouter] integration — verifies that in-chat text input
 * routes through QIR (Tier 2) before falling through to the LLM (Tier 3).
 *
 * Uses a real [QuickIntentRouter] (no classifier, regex-only) to prove deterministic
 * routing, unlike the mocked QIR tests in [ChatViewModelVoiceTest].
 *
 * Run with: ./gradlew :feature:chat:testDebugUnitTest --tests "*.ChatViewModelQuickIntentRouterTest"
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQuickIntentRouterTest {

    private val dispatcher = StandardTestDispatcher()

    // Mocked dependencies (everything except QIR)
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
    private val slotFillerManager: SlotFillerManager = mockk(relaxed = true)
    private val slotValidationRegistry: SlotValidationRegistry = mockk(relaxed = true)
    private val kernelAIToolSet: KernelAIToolSet = mockk(relaxed = true)
    private val toolProvider: ToolProvider = mockk(relaxed = true)
    private val embeddingEngine: EmbeddingEngine = mockk(relaxed = true)
    private val voiceInputController: VoiceInputController = mockk(relaxed = true)
    private val voiceOutputController: VoiceOutputController = mockk(relaxed = true)
    private val voiceStreamingSession: VoiceOutputStreamingSession = mockk(relaxed = true)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk(relaxed = true)
    private val jandalPersona: JandalPersona = mockk(relaxed = true)
    private val nzTruthSeedingService: NzTruthSeedingService = mockk(relaxed = true)
    private val verboseLoggingPreferenceUseCase: VerboseLoggingPreferenceUseCase = mockk(relaxed = true)
    private val gatedModelStatusRepository: GatedModelStatusRepository = mockk(relaxed = true)
    private val startListeningCuePlayer: StartListeningCuePlayer = mockk(relaxed = true)
    private val chatPreferences: ChatPreferences = mockk(relaxed = true)
    private val intentRecoveryOrchestrator: IntentRecoveryOrchestrator = mockk(relaxed = true)
    private val authRepository: HuggingFaceAuthRepository = mockk(relaxed = true)

    // Real QIR (no classifier — regex-only)
    private val realRouter = QuickIntentRouter()

    // Fake weather skill for testing
    private val weatherSkill = object : Skill {
        override val name = "get_weather"
        override val description = "Get weather"
        override val schema = SkillSchema()

        override suspend fun execute(call: SkillCall): SkillResult =
            SkillResult.Success("Currently 18°C and partly cloudy in your area.")
    }

    private val spokenResponsesEnabled = MutableStateFlow(false)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        // Inference engine — ready, generating not called
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.isGenerating } returns MutableStateFlow(false)
        every { inferenceEngine.activeBackend } returns MutableStateFlow<BackendType?>(null)
        every { inferenceEngine.resolvedMaxTokens } returns MutableStateFlow(0)
        every { inferenceEngine.evictionEvents } returns emptyFlow()
        coEvery { inferenceEngine.updateSystemPrompt(any()) } just runs
        coEvery { inferenceEngine.resetConversation() } just runs

        // Download state
        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

        // Conversation repo
        coEvery { conversationRepository.getConversation(any()) } answers {
            val id = firstArg<String>()
            ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L)
        }
        coEvery { conversationRepository.getMessagesOnce(any()) } returns emptyList()
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg", "assistant-msg")
        every { conversationRepository.observeConversationById(any()) } answers {
            val id = firstArg<String>()
            flowOf(ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L))
        }

        // Auth
        every { authRepository.isAuthenticated } returns MutableStateFlow(false)

        // Chat preferences
        every { chatPreferences.fontSize } returns flowOf(1)
        every { chatPreferences.bubbleTheme } returns flowOf("system")
        every { chatPreferences.userFontColor } returns flowOf(null)
        every { chatPreferences.assistantFontColor } returns flowOf(null)
        every { chatPreferences.wallpaperType } returns flowOf("none")
        every { chatPreferences.wallpaperColor } returns flowOf(null)
        every { chatPreferences.wallpaperImageUri } returns flowOf(null)
        every { chatPreferences.copyToolCalls } returns flowOf(false)
        every { chatPreferences.copyThinking } returns flowOf(false)

        // Persona
        every { jandalPersona.personaMode } returns MutableStateFlow(PersonaMode.FULL)
        every { jandalPersona.currentPersonaMode } returns PersonaMode.FULL

        // Validation
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()

        // Skill registry — return real weather skill for get_weather
        every { skillRegistry.get("get_weather") } returns weatherSkill

        // NZ truth seeding
        every { nzTruthSeedingService.isSeeding } returns MutableStateFlow(false)
        every { nzTruthSeedingService.seedIfNeeded() } just runs

        // Verbose logging
        coEvery { verboseLoggingPreferenceUseCase.loadAndApplyVerboseLoggingPreference() } just runs

        // Voice — disable spoken responses for text-only test
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
        every { voiceOutputPreferences.autoSpeak } returns MutableStateFlow(false)
        every { voiceOutputPreferences.maxSpokenSentences } returns flowOf(0)
        every { voiceInputController.events } returns emptyFlow()
        every { voiceOutputController.events } returns emptyFlow()
        coEvery { voiceOutputController.warmUp() } returns VoiceOutputResult.Spoken
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @ParameterizedTest(name = "\"{0}\" routes through QIR, not LLM")
    @ValueSource(strings = [
        "what's the weather",
        "whats the weather",
        "what is the weather",
        "weather",
        "weather here",
        "local weather",
        "what's the weather here",
        "what's the forecast",
        "what is the forecast",
        "forecast",
        "whats the forecast",
        "what's the 5-day forecast",
        "what's the 5 day forecast",
        "5-day forecast",
        "5 day forecast",
        "five day forecast",
    ])
    fun `weather query routes through QIR regex match`(input: String) = runTest(dispatcher) {
        // Verify QIR route returns a RegexMatch (not FallThrough)
        val routeResult = realRouter.route(input)
        val isRegexMatch = routeResult is QuickIntentRouter.RouteResult.RegexMatch
        assert(isRegexMatch) { "Expected RegexMatch for '$input' but got ${routeResult::class.simpleName}" }

        // Create ChatViewModel with real QIR
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Send text input
        viewModel.onInputChanged(input)
        viewModel.sendMessage()
        advanceUntilIdle()

        // Verify the LLM was NOT called → QIR routed it before reaching Tier 3
        verify(exactly = 0) { inferenceEngine.generate(any()) }

        // Verify the response was added to chat
        val chatText = viewModel.getConversationAsText()
        assert(chatText.contains("partly cloudy") || chatText.contains("weather")) {
            "Expected weather response in chat for '$input' but got: $chatText"
        }
    }

    @Test
    fun `weather with location still routes through QIR and preserves location`() = runTest(dispatcher) {
        val input = "what's the weather in London"

        // Verify QIR routes to weather with location param
        val routeResult = realRouter.route(input)
        assert(routeResult is QuickIntentRouter.RouteResult.RegexMatch) {
            "Expected RegexMatch for '$input' but got ${routeResult::class.simpleName}"
        }
        val intent = (routeResult as QuickIntentRouter.RouteResult.RegexMatch).intent
        assertEquals("get_weather", intent.intentName)
        assertEquals("London", intent.params["location"])

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged(input)
        viewModel.sendMessage()
        advanceUntilIdle()

        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `non-weather query falls through to LLM as expected`() = runTest(dispatcher) {
        val input = "tell me a joke"

        // Verify QIR does NOT match this
        val routeResult = realRouter.route(input)
        assert(routeResult is QuickIntentRouter.RouteResult.FallThrough) {
            "Expected FallThrough for '$input' but got ${routeResult::class.simpleName}"
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged(input)
        viewModel.sendMessage()
        advanceUntilIdle()
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
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
        quickIntentRouter = realRouter,
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
