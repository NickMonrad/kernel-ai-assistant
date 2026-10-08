package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
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
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.permissions.CapabilityKey
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
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    private var weatherSkillResult: SkillResult = SkillResult.Success("Currently 18°C and partly cloudy in your area.")

    private val weatherSkill = object : Skill {
        override val name = "get_weather"
        override val description = "Get weather"
        override val schema = SkillSchema()

        override suspend fun execute(call: SkillCall): SkillResult = weatherSkillResult
    }

    private val spokenResponsesEnabled = MutableStateFlow(false)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        weatherSkillResult = SkillResult.Success("Currently 18°C and partly cloudy in your area.")
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
    fun `weather capability required opens contextual dialog without assistant failure`() = runTest(dispatcher) {
        val input = "what's the weather"
        weatherSkillResult = SkillResult.CapabilityRequired(
            capabilityKey = CapabilityKey.WeatherCurrentLocation,
            skillName = "get_weather",
        )

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onInputChanged(input)
        viewModel.sendMessage()
        advanceUntilIdle()

        verify(exactly = 0) { inferenceEngine.generate(any()) }
        assertEquals(ChatViewModel.WeatherLocationState(), viewModel.weatherLocationState.value)
        val conversation = viewModel.getConversationAsText()
        assertEquals(1, conversation.lines().count { it.startsWith("You:") })
        assertEquals(0, conversation.lines().count { it.startsWith("Jandal:") })
        assertFalse(conversation.contains("Location access"))
    }

    @Test
    fun `grant retries weather skill and appends only one assistant message`() = runTest(dispatcher) {
        weatherSkillResult = SkillResult.CapabilityRequired(
            capabilityKey = CapabilityKey.WeatherCurrentLocation,
            skillName = "get_weather",
        )
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onInputChanged("what's the weather")
        viewModel.sendMessage()
        advanceUntilIdle()

        weatherSkillResult = SkillResult.Success("Currently 18°C and partly cloudy in your area.")
        viewModel.onWeatherLocationPermissionGranted()
        advanceUntilIdle()

        val conversation = viewModel.getConversationAsText()
        assertNull(viewModel.weatherLocationState.value)
        assertEquals(1, conversation.lines().count { it.startsWith("You:") })
        assertEquals(1, conversation.lines().count { it.startsWith("Jandal:") })
        assertTrue(viewModel.getConversationAsText().contains("partly cloudy"))
    }

    @Test
    fun `first weather denial remains retryable`() = runTest(dispatcher) {
        val viewModel = submitWeatherCapabilityRequest()
        advanceUntilIdle()

        viewModel.onWeatherLocationPermissionDenied(shouldShowRationale = false)

        assertEquals(ChatViewModel.WeatherLocationState(), viewModel.weatherLocationState.value)
    }

    @Test
    fun `second weather denial becomes blocked repair state`() = runTest(dispatcher) {
        val viewModel = submitWeatherCapabilityRequest()
        advanceUntilIdle()

        viewModel.onWeatherLocationPermissionDenied(shouldShowRationale = true)
        viewModel.onWeatherLocationPermissionDenied(shouldShowRationale = false)

        assertEquals(
            ChatViewModel.WeatherLocationState(isPermanentlyDenied = true),
            viewModel.weatherLocationState.value,
        )
    }

    @Test
    fun `location settings grant retries pending weather request`() = runTest(dispatcher) {
        weatherSkillResult = SkillResult.CapabilityRequired(
            capabilityKey = CapabilityKey.WeatherCurrentLocation,
            skillName = "get_weather",
        )
        val viewModel = submitWeatherCapabilityRequest()
        advanceUntilIdle()
        viewModel.onWeatherLocationOpenAppPermissions()
        advanceUntilIdle()
        weatherSkillResult = SkillResult.Success("Weather restored.")
        viewModel.onChatWeatherLocationRepairResumeCheck(hasPermission = true)
        advanceUntilIdle()

        assertNull(viewModel.weatherLocationState.value)
        assertTrue(viewModel.getConversationAsText().contains("Weather restored."))
    }

    @Test
    fun `location settings return without grant restores blocked repair state`() = runTest(dispatcher) {
        val viewModel = submitWeatherCapabilityRequest()
        advanceUntilIdle()

        viewModel.onWeatherLocationOpenAppPermissions()
        advanceUntilIdle()
        viewModel.onChatWeatherLocationRepairResumeCheck(hasPermission = false)

        assertEquals(
            ChatViewModel.WeatherLocationState(isPermanentlyDenied = true),
            viewModel.weatherLocationState.value,
        )
    }

    @Test
    fun `named place fallback clears pending weather permission and guides chat input`() = runTest(dispatcher) {
        val viewModel = submitWeatherCapabilityRequest()
        advanceUntilIdle()

        val errorState = async {
            viewModel.uiState
                .filterIsInstance<ChatUiState.Ready>()
                .first()
        }
        viewModel.onWeatherLocationTypePlace()
        assertNull(viewModel.weatherLocationState.value)
        assertEquals(
            "Type a place name in the chat input, like \"weather in Tokyo\".",
            errorState.await().error,
        )
    }

    @Test
    fun `not now dismisses pending weather permission without assistant output`() = runTest(dispatcher) {
        val viewModel = submitWeatherCapabilityRequest()
        advanceUntilIdle()

        viewModel.dismissWeatherLocationDialog()

        assertNull(viewModel.weatherLocationState.value)
        assertEquals(
            1,
            viewModel.getConversationAsText().lines().count { it.startsWith("You:") },
        )
    }

    @Test
    fun `unrelated capability required keeps generic assistant guidance`() = runTest(dispatcher) {
        weatherSkillResult = SkillResult.CapabilityRequired(
            capabilityKey = CapabilityKey.ContactLookup,
            skillName = "get_weather",
        )
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onInputChanged("what's the weather")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertNull(viewModel.weatherLocationState.value)
        assertTrue(viewModel.getConversationAsText().contains("Permission required for get_weather."))
    }

    @Test
    fun `weather permission request emits runtime launcher event`() = runTest(dispatcher) {
        val viewModel = submitWeatherCapabilityRequest()
        advanceUntilIdle()
        val event = async { viewModel.events.first() }

        viewModel.onWeatherLocationRequestPermission()

        assertEquals(ChatViewModel.UiEvent.RequestWeatherLocationPermission, event.await())
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

    @Test
    fun `debug safe run-intent marker initializes a cold engine before generation and closes sandbox`() = runTest(dispatcher) {
        val input = "What day and date is it today, and what is the current local time on this device?"
        val marker = "__orchtest:safe_run_intent:"
        val router = mockk<QuickIntentRouter>()
        val generationPrompts = mutableListOf<String>()
        val systemPrompts = mutableListOf<String>()
        var sandboxOpened = false
        var sandboxClosed = false
        val engineReady = MutableStateFlow(false)
        val modelEvents = mutableListOf<String>()
        every { inferenceEngine.isReady } returns engineReady
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        every { downloadManager.getModelPath(KernelModel.GEMMA_4_E4B) } returns
            "/models/gemma-4-E4B-it.litertlm"
        coEvery { downloadManager.preferredConversationModel() } returns KernelModel.GEMMA_4_E4B
        coEvery { modelSettingsRepository.getSettings("gemma_4_e4b") } returns
            ModelSettingsEntity(
                modelId = "gemma_4_e4b",
                contextWindowSize = 8192,
                temperature = 0.7f,
                topP = 0.9f,
                topK = 64,
                showThinkingProcess = true,
                speculativeDecodingEnabled = false,
                updatedAt = 1L,
            )
        coEvery { inferenceEngine.initialize(any()) } coAnswers {
            modelEvents += "initialize"
            engineReady.value = true
        }

        coEvery { userProfileRepository.get() } returns "PRIVATE_PROFILE_MARKER"
        every { inferenceEngine.generate(capture(generationPrompts)) } answers {
            modelEvents += "generate"
            flowOf(
                GenerationResult.Token("It's 12:00 on Monday, 1 June 2026"),
                GenerationResult.Complete(durationMs = 1L),
            )
        }
        coEvery { inferenceEngine.updateSystemPrompt(capture(systemPrompts)) } just runs
        every { kernelAIToolSet.beginSafeModelTestSandbox() } answers {
            sandboxOpened = true
            AutoCloseable { sandboxClosed = true }
        }

        val viewModel = createViewModel(router)

        val viewModelStore = ViewModelStore().apply { put("safe-run-intent", viewModel) }
        advanceUntilIdle()
        assertFalse(engineReady.value)
        coVerify(exactly = 0) { inferenceEngine.initialize(any()) }
        clearMocks(
            userProfileRepository,
            ragRepository,
            mealPlanSessionRepository,
            conversationRepository,
            router,
            recordedCalls = true,
            answers = false,
        )

        viewModel.onInputChanged(marker + input)
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue(sandboxOpened)
        assertTrue(sandboxClosed)
        verify(exactly = 0) { router.route(any()) }
        verify(exactly = 1) { inferenceEngine.generate(any()) }
        assertEquals(listOf("initialize", "generate"), modelEvents)
        coVerify(exactly = 1) { inferenceEngine.initialize(any()) }
        assertTrue(engineReady.value)
        coVerify(exactly = 0) { userProfileRepository.get() }
        coVerify(exactly = 0) { conversationRepository.getMessagesOnce(any()) }
        coVerify(exactly = 0) { ragRepository.getRelevantContext(any(), any(), any()) }
        coVerify(exactly = 0) {
            mealPlanSessionRepository.hasActiveSessionForConversation(any())
        }
        coVerify(exactly = 0) { ragRepository.indexMessage(any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepository.renameConversation(any(), any()) }
        coVerify(exactly = 0) {
            conversationRepository.addMessage(any(), any(), any(), any(), any())
        }

        val safeSystemPrompt = systemPrompts.last()
        assertFalse(safeSystemPrompt.contains("PRIVATE_PROFILE_MARKER"))
        assertFalse(safeSystemPrompt.contains("[Current date and time]"))
        val inferencePrompt = generationPrompts.single()
        assertTrue(inferencePrompt.contains(input))
        assertFalse(inferencePrompt.contains(marker))
        val chatText = viewModel.getConversationAsText()
        assertTrue(chatText.contains(input))
        assertFalse(chatText.contains(marker))
        assertTrue(chatText.contains("12:00"))
        viewModelStore.clear()
        coVerify(exactly = 0) {
            conversationRepository.addMessage(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { mealPlanSessionRepository.hasAnySessionForConversation(any()) }
        coVerify(exactly = 0) { episodicDistillationUseCase.distil(any(), any()) }

    }

    private suspend fun TestScope.submitWeatherCapabilityRequest(): ChatViewModel {
        weatherSkillResult = SkillResult.CapabilityRequired(
            capabilityKey = CapabilityKey.WeatherCurrentLocation,
            skillName = "get_weather",
        )
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onInputChanged("what's the weather")
        viewModel.sendMessage()
        advanceUntilIdle()
        return viewModel
    }

    private fun createViewModel(router: QuickIntentRouter = realRouter): ChatViewModel = ChatViewModel(
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
        quickIntentRouter = router,
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
