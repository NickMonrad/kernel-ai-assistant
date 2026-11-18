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
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ConversationEntity
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
import com.kernel.ai.core.permissions.CapabilityKey
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentation
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.skills.slot.SlotValidationResult
import com.kernel.ai.core.skills.mealplan.MealPlannerActivity
import com.kernel.ai.core.skills.mealplan.MealPlannerActivityState
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.skills.mealplan.MealPlannerReply
import com.kernel.ai.core.skills.mealplan.MealPlannerSuggestion
import com.kernel.ai.core.skills.mealplan.MealPlannerSuggestionComposeMode
import com.kernel.ai.core.voice.SpeechStopCoordinator
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.VoiceNotice
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.intent.RecoveryResult
import com.kernel.ai.feature.chat.model.ChatUiState
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelVoiceTest {
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
    private val voiceStreamingSession: VoiceOutputStreamingSession = mockk(relaxed = true)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk(relaxed = true)
    private val jandalPersona: JandalPersona = mockk(relaxed = true)
    private val nzTruthSeedingService: NzTruthSeedingService = mockk(relaxed = true)
    private val verboseLoggingPreferenceUseCase: VerboseLoggingPreferenceUseCase = mockk(relaxed = true)
    private val gatedModelStatusRepository: GatedModelStatusRepository = mockk(relaxed = true)
    private val startListeningCuePlayer: StartListeningCuePlayer = mockk(relaxed = true)
    private val chatPreferences: ChatPreferences = mockk(relaxed = true)
    private val voiceInputPreferences: com.kernel.ai.core.voice.VoiceInputPreferences = mockk(relaxed = true)
    private val intentRecoveryOrchestrator: IntentRecoveryOrchestrator = mockk(relaxed = true)
    private val authRepository: HuggingFaceAuthRepository = mockk(relaxed = true)

    private val voiceInputEvents = MutableSharedFlow<VoiceInputEvent>()
    private val voiceInputNotices = MutableSharedFlow<VoiceNotice>(replay = 1)
    private val voiceOutputEvents = MutableSharedFlow<VoiceOutputEvent>()
    private val spokenResponsesEnabled = MutableStateFlow(true)
    private val streamedChunks = mutableListOf<Pair<String, Boolean>>()

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
        every { inferenceEngine.generateWithDebugTrace(any(), any()) } answers {
            inferenceEngine.generate(firstArg())
        }
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Token("Hi back"), GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } just runs
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()
        coEvery { inferenceEngine.resetConversation() } just runs

        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

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
        every { voiceInputController.events } returns voiceInputEvents
        every { voiceInputController.notices } returns voiceInputNotices
        coEvery { voiceInputController.startListening(VoiceCaptureMode.Command) } returns
            VoiceInputStartResult.Started(1L)
        every { voiceInputController.stopListening() } just runs
        every { voiceOutputController.events } returns voiceOutputEvents
        coEvery { voiceOutputController.warmUp() } returns VoiceOutputResult.Spoken
        streamedChunks.clear()
        every { kernelAIToolSet.terminalToolName() } returns null
        every { kernelAIToolSet.terminalToolSpokenSummary() } returns null
        every { kernelAIToolSet.terminalToolPresentation() } returns null
        every { kernelAIToolSet.terminalToolResult() } returns null
        coEvery { voiceStreamingSession.cancel() } just runs
        coEvery { voiceOutputController.openStreamingSession(any()) } returns voiceStreamingSession
        coEvery { voiceStreamingSession.append(any(), any()) } coAnswers {
            streamedChunks += firstArg<String>() to secondArg<Boolean>()
            VoiceOutputResult.Spoken
        }
        every { voiceOutputController.stop() } just runs
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
        every { voiceOutputPreferences.autoSpeak } returns MutableStateFlow(true)
        every { voiceOutputPreferences.maxSpokenSentences } returns flowOf(0)
        every { nzTruthSeedingService.isSeeding } returns MutableStateFlow(false)
        every { nzTruthSeedingService.seedIfNeeded() } just runs
        coEvery { verboseLoggingPreferenceUseCase.loadAndApplyVerboseLoggingPreference() } just runs
        every { kernelAIToolSet.lastToolCapabilityRequired() } returns null
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `a cancelled stop clears Processing while waiting for a final transcript`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started(1L)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 1L))
        voiceInputEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "set a timer", 1L))
        voiceInputEvents.emit(VoiceInputEvent.SpeechEnded(VoiceCaptureMode.Command, 1L))
        advanceUntilIdle()

        assertEquals(
            ChatViewModel.VoiceCaptureState.Processing("set a timer"),
            viewModel.voiceCaptureState.value,
        )

        voiceInputEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L, cancelled = true))
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
    }

    @Test
    fun `a cancelled stop clears an owned listening capture without submitting its partial`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started(1L)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 1L))
        voiceInputEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "set a timer", 1L))
        voiceInputEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L, cancelled = true))
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
    }

    @Test
    fun `ListeningStarted emits one chat start haptic signal`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started(1L)
        val viewModel = createViewModel()
        advanceUntilIdle()
        val haptics = mutableListOf<Unit>()
        val collectJob = launch { viewModel.listeningHaptics.collect { haptics += it } }

        viewModel.startVoiceInput()
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 1L))
        advanceUntilIdle()

        assertEquals(1, haptics.size)
        collectJob.cancel()
    }
    @Test
    fun `never-admitted cancellation returns chat capture to Idle without retry`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Cancelled()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }


    @Test
    fun `foreign prior-session events cannot change a preparing or listening replacement`() = runTest(dispatcher) {
        val secondStart = CompletableDeferred<VoiceInputStartResult>()
        var starts = 0
        coEvery { voiceInputController.startListening(VoiceCaptureMode.Command) } coAnswers {
            if (starts++ == 0) VoiceInputStartResult.Started(1L) else secondStart.await()
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        runCurrent()
        viewModel.stopVoiceInput()
        viewModel.startVoiceInput()
        runCurrent()
        assertEquals(ChatViewModel.VoiceCaptureState.Preparing, viewModel.voiceCaptureState.value)

        voiceInputEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "A", 1L))
        advanceUntilIdle()
        assertEquals(ChatViewModel.VoiceCaptureState.Preparing, viewModel.voiceCaptureState.value)

        secondStart.complete(VoiceInputStartResult.Started(2L))
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, 2L))
        advanceUntilIdle()
        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), viewModel.voiceCaptureState.value)

        voiceInputEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L, cancelled = true))
        advanceUntilIdle()
        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), viewModel.voiceCaptureState.value)
    }

    @Test
    fun `alert command transcript does not reach chat llm flow`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "stop", captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals("", viewModel.getConversationAsText())
        verify(exactly = 0) { quickIntentRouter.route(any()) }
    }


    @Test
    fun `idle chat viewmodel ignores command transcript it did not start`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "cancel timer", captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals("", viewModel.getConversationAsText())
        verify(exactly = 0) { quickIntentRouter.route(any()) }
    }


    @Test
    fun `voice transcript submits chat message and speaks assistant reply`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello there") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello there")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello there", captureSessionId = 1L))
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
        advanceUntilIdle()

        assertEquals("You: Hello there\nJandal: Hi back", viewModel.getConversationAsText())
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify {
            voiceOutputController.openStreamingSession(any())
        }
        assertEquals(listOf("Hi back." to true), streamedChunks)
    }

    @Test
    fun `typed chat message stays silent`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello there") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello there")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("Hello there")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("You: Hello there\nJandal: Hi back", viewModel.getConversationAsText())
        val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
        assertEquals("", state.inputText)
        assertEquals(1L, state.inputTextReplacementRevision)
        coVerify(exactly = 0) { voiceOutputController.openStreamingSession(any()) }
    }

    @Test
    fun `startVoiceInput surfaces unavailable recognizer state`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Unavailable("Voice input is unavailable.")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 0) { voiceOutputController.openStreamingSession(any()) }
    }

    @Test
    fun `speaking started event does not clobber active voice input`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Previous reply"))
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), viewModel.voiceCaptureState.value)
    }

    @Test
    fun `voice direct reply speaks spoken summary instead of display text`() = runTest(dispatcher) {
        val runIntentSkill = object : Skill {
            override val name = "run_intent"
            override val description = "Run intent"
            override val schema = SkillSchema(required = listOf("intent_name", "value", "from_unit", "to_unit"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.DirectReply(
                    content = "1 gallon is 3.785411784 liters.",
                    spokenSummary = "1 gallon is 3.79 liters.",
                )
        }
        every {
            quickIntentRouter.route("Convert 1 gallon to litres")
        } returns QuickIntentRouter.RouteResult.RegexMatch(
            QuickIntentRouter.MatchedIntent(
                intentName = "convert_units",
                params = mapOf(
                    "value" to "1",
                    "from_unit" to "gallon",
                    "to_unit" to "litres",
                ),
            ),
        )
        every { skillRegistry.get("convert_units") } returns null
        every { skillRegistry.get("run_intent") } returns runIntentSkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Convert 1 gallon to litres", captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals(
            "You: Convert 1 gallon to litres\nJandal: 1 gallon is 3.785411784 liters.",
            viewModel.getConversationAsText(),
        )
        assertEquals(listOf("1 gallon is 3.79 liters." to true), streamedChunks)
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `streamed reply shorter than 20 chars is spoken once as the final chunk`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Say hi") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Say hi")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token("Hi there, all good. "),
            GenerationResult.Complete(durationMs = 1L),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Say hi", captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals(listOf("Hi there, all good." to true), streamedChunks)
    }

    @Test
    fun `stop during a chunked direct reply cancels the job so no later chunk is queued`() = runTest(dispatcher) {
        val runIntentSkill = object : Skill {
            override val name = "run_intent"
            override val description = "Run intent"
            override val schema = SkillSchema(required = listOf("intent_name", "value", "from_unit", "to_unit"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.DirectReply(
                    content = "Long reply.",
                    spokenSummary = "The first sentence here is long enough to stand alone. " +
                        "The second sentence follows after it and is spoken last.",
                )
        }
        every {
            quickIntentRouter.route("Convert 2 cups to millilitres")
        } returns QuickIntentRouter.RouteResult.RegexMatch(
            QuickIntentRouter.MatchedIntent(
                intentName = "convert_units",
                params = mapOf("value" to "2", "from_unit" to "cups", "to_unit" to "millilitres"),
            ),
        )
        every { skillRegistry.get("convert_units") } returns null
        every { skillRegistry.get("run_intent") } returns runIntentSkill
        val gate = CompletableDeferred<Unit>()
        coEvery { voiceStreamingSession.append(any(), any()) } coAnswers {
            streamedChunks += firstArg<String>() to secondArg<Boolean>()
            gate.await()
            VoiceOutputResult.Spoken
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Convert 2 cups to millilitres", captureSessionId = 1L))
        runCurrent()

        viewModel.stopVoiceOutput()
        advanceUntilIdle()

        assertEquals(1, streamedChunks.size, "no chunk after the stop may be queued")
        coVerify(atLeast = 1) { voiceStreamingSession.cancel() }
    }

    @Test
    fun `home stop during a voice-submitted reply cancels speech and queues no later chunk`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Tell me a story") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me a story")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token("The first sentence here is long enough to stand alone. "),
            GenerationResult.Token("The second sentence follows after it and is spoken last."),
            GenerationResult.Complete(durationMs = 1L),
        )
        val gate = CompletableDeferred<Unit>()
        coEvery { voiceStreamingSession.append(any(), any()) } coAnswers {
            streamedChunks += firstArg<String>() to secondArg<Boolean>()
            gate.await()
            VoiceOutputResult.Spoken
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Tell me a story", captureSessionId = 1L))
        runCurrent()

        clearMocks(voiceOutputController, answers = false)
        viewModel.onScreenStopped(screenOff = false)
        gate.complete(Unit)
        advanceUntilIdle()

        verify(atLeast = 1) { voiceOutputController.stop() }
        coVerify(atLeast = 1) { voiceStreamingSession.cancel() }
        assertEquals(1, streamedChunks.size, "no chunk after Home may be queued")
        assertNull(viewModel.speakingMessageId.value)
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
    }

    @Test
    fun `home before the reply finishes opens no speech for the rest of that turn`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Tell me a story") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me a story")
        val generationGate = CompletableDeferred<Unit>()
        every { inferenceEngine.generate(any()) } returns flow {
            emit(GenerationResult.Token("The first sentence here is long enough to stand alone. "))
            generationGate.await()
            emit(GenerationResult.Token("The second sentence follows after it and is spoken last."))
            emit(GenerationResult.Complete(durationMs = 1L))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Tell me a story", captureSessionId = 1L))
        runCurrent()

        viewModel.onScreenStopped(screenOff = false)
        clearMocks(voiceOutputController, answers = false)
        generationGate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { voiceOutputController.openStreamingSession(any()) }
        coVerify(exactly = 0) { voiceOutputController.speak(any()) }
        assertEquals(emptyList<Pair<String, Boolean>>(), streamedChunks, "generation finishing after Home must not speak")
    }

    @Test
    fun `screen-off during generation still speaks the reply when generation completes`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Say hi later") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Say hi later")
        val generationGate = CompletableDeferred<Unit>()
        every { inferenceEngine.generate(any()) } returns flow {
            generationGate.await()
            emit(GenerationResult.Token("Hi there, all good. "))
            emit(GenerationResult.Complete(durationMs = 1L))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        advanceUntilIdle()
        clearMocks(voiceInputController, answers = false)
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Say hi later", captureSessionId = 1L))
        advanceUntilIdle()

        viewModel.onScreenStopped(screenOff = true)
        advanceUntilIdle()
        assertEquals(emptyList<Pair<String, Boolean>>(), streamedChunks, "nothing spoken while generating")

        generationGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("Hi there, all good." to true), streamedChunks)

        // Speech finishes with the screen off: the back-and-forth loop must not reopen the mic.
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()
        coVerify(exactly = 0) { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }

    @Test
    fun `tool-call syntax in the streamed reply is never spoken`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Weather in Apia") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Weather in Apia")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token("<|tool_call>"),
            GenerationResult.Token("call:get_weather{location:<|\"|>Apia<|\"|>}"),
            GenerationResult.Token("<tool_call|>"),
            GenerationResult.Complete(durationMs = 1L),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Weather in Apia", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(
            streamedChunks.none { (chunk, _) -> chunk.contains("call:") || chunk.contains("get_weather") },
            "tool-call text was spoken: $streamedChunks",
        )
    }

    @Test
    fun `reply retried after an empty first pass is spoken in full`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Tell me about kiwi birds") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me about kiwi birds")
        every { inferenceEngine.generate(any()) } returnsMany listOf(
            flowOf(GenerationResult.Complete(durationMs = 1L)),
            flowOf(
                GenerationResult.Token("Kiwi birds are flightless and nocturnal."),
                GenerationResult.Complete(durationMs = 1L),
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Tell me about kiwi birds", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(
            streamedChunks.any { (chunk, _) -> chunk.contains("flightless and nocturnal") },
            "retried reply was not spoken: $streamedChunks",
        )
    }

    @Test
    fun `retried turn speaks only the final message`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Tell me about kiwi birds") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me about kiwi birds")
        every { inferenceEngine.generate(any()) } returnsMany listOf(
            flowOf(GenerationResult.Token("I've saved that to memory."), GenerationResult.Complete(durationMs = 1L)),
            flowOf(
                GenerationResult.Token("Your keys are in the blue bowl by the door."),
                GenerationResult.Complete(durationMs = 1L),
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Tell me about kiwi birds", captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals(
            listOf("Your keys are in the blue bowl by the door." to true),
            streamedChunks,
            "the discarded pass must not be spoken, and the final reply must be spoken once",
        )
    }

    @Test
    fun `native tool reply speaks the answer after the call, never the text before it or the tool detail`() = runTest(dispatcher) {
        var nativeToolRan = false
        every { quickIntentRouter.route("Weather in Apia") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Weather in Apia")
        every { kernelAIToolSet.terminalToolCalledInCurrentAttempt() } answers { nativeToolRan }
        every { kernelAIToolSet.terminalToolName() } answers { if (nativeToolRan) "get_weather" else null }
        every { kernelAIToolSet.terminalToolRequest() } returns "{}"
        every { kernelAIToolSet.terminalToolDetail() } returns "Apia: 28 C"
        every { kernelAIToolSet.terminalToolSucceeded() } returns true
        every { inferenceEngine.generate(any()) } returns kotlinx.coroutines.flow.flow {
            emit(GenerationResult.Token("It is sunny and warm in Apia today."))
            nativeToolRan = true
            emit(GenerationResult.Token("Take an umbrella, it may rain later."))
            emit(GenerationResult.Complete(durationMs = 1L))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Weather in Apia", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(
            streamedChunks.none { (chunk, _) -> chunk.contains("sunny") },
            "pre-tool text was spoken: $streamedChunks",
        )
        assertTrue(
            streamedChunks.any { (chunk, _) -> chunk.contains("umbrella") },
            "the answer after the tool call was not spoken: $streamedChunks",
        )
        assertTrue(
            streamedChunks.none { (chunk, _) -> chunk.contains("Apia: 28 C") },
            "tool detail was spoken: $streamedChunks",
        )
        assertTrue(streamedChunks.last().second, "tool reply is not final: $streamedChunks")
    }

    @Test
    fun `raw tool-call reply ends voice mode instead of stalling`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Weather in Apia") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Weather in Apia")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token("<|tool_call>call:get_weather{location:<|\"|>Apia<|\"|>}<tool_call|>"),
            GenerationResult.Complete(durationMs = 1L),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Weather in Apia", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(streamedChunks.isEmpty(), "raw tool-call text was spoken: $streamedChunks")
        assertNull(viewModel.voiceMode.value, "voice mode stayed armed after a raw tool-call reply")
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
    }

    private fun stubNativeToolTurn(query: String, postToolText: String?) {
        var nativeToolRan = false
        every { quickIntentRouter.route(query) } returns QuickIntentRouter.RouteResult.FallThrough(input = query)
        every { kernelAIToolSet.terminalToolCalledInCurrentAttempt() } answers { nativeToolRan }
        every { kernelAIToolSet.terminalToolName() } answers { if (nativeToolRan) "get_weather" else null }
        every { kernelAIToolSet.terminalToolRequest() } returns "{}"
        every { inferenceEngine.generate(any()) } returns kotlinx.coroutines.flow.flow {
            nativeToolRan = true
            postToolText?.let { emit(GenerationResult.Token(it)) }
            emit(GenerationResult.Complete(durationMs = 1L))
        }
    }

    private fun stubDirectReply(detail: String, summary: String?) {
        every { kernelAIToolSet.terminalToolDetail() } returns detail
        every { kernelAIToolSet.terminalToolResult() } returns detail
        every { kernelAIToolSet.terminalToolSucceeded() } returns true
        every { kernelAIToolSet.terminalToolSucceededInCurrentAttempt() } returns true
        every { kernelAIToolSet.terminalToolWasDirectReply() } returns true
        every { kernelAIToolSet.terminalToolSpokenSummary() } returns summary
    }

    private suspend fun TestScope.speakVoiceQuery(viewModel: ChatViewModel, query: String) {
        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, query, captureSessionId = 1L))
        advanceUntilIdle()
    }

    private fun spokenText(): String = streamedChunks.joinToString(" ") { (chunk, _) -> chunk.trim() }

    @Test
    fun `direct reply with post-tool text speaks the tool summary, not the model text after the call`() = runTest(dispatcher) {
        val detail = "Reminder set for 9 am tomorrow."
        val summary = "Reminder set for nine tomorrow morning."
        stubNativeToolTurn("Remind me at nine", postToolText = "Done, your reminder is set.")
        stubDirectReply(detail, summary)
        val viewModel = createViewModel()
        advanceUntilIdle()

        speakVoiceQuery(viewModel, "Remind me at nine")

        assertEquals(summary, spokenText(), "direct reply must speak the tool summary, as the bubble's card does")
        coVerify { conversationRepository.addMessage(any(), "assistant", detail, any(), any()) }
    }

    @Test
    fun `direct reply without post-tool text speaks the tool summary`() = runTest(dispatcher) {
        val detail = "Reminder set for 9 am tomorrow."
        val summary = "Reminder set for nine tomorrow morning."
        stubNativeToolTurn("Remind me at nine", postToolText = null)
        stubDirectReply(detail, summary)
        val viewModel = createViewModel()
        advanceUntilIdle()

        speakVoiceQuery(viewModel, "Remind me at nine")

        assertEquals(summary, spokenText())
        coVerify { conversationRepository.addMessage(any(), "assistant", detail, any(), any()) }
    }

    @Test
    fun `failed tool with post-tool text speaks the failure the bubble shows, not the model text`() = runTest(dispatcher) {
        val failure = "Weather service unreachable."
        stubNativeToolTurn("Weather in Apia", postToolText = "Sorry, I could not check that.")
        every { kernelAIToolSet.terminalToolDetail() } returns ""
        every { kernelAIToolSet.terminalToolResult() } returns failure
        every { kernelAIToolSet.terminalToolSucceeded() } returns false
        every { kernelAIToolSet.terminalToolFailed() } returns true
        val viewModel = createViewModel()
        advanceUntilIdle()

        speakVoiceQuery(viewModel, "Weather in Apia")

        assertEquals(failure, spokenText(), "failure must speak what the bubble shows")
        coVerify { conversationRepository.addMessage(any(), "assistant", failure, any(), any()) }
    }

    @Test
    fun `card reply with post-tool text speaks the card summary, not the model text after the call`() = runTest(dispatcher) {
        val card = "Apia: 22 C, drizzle"
        val summary = "Apia is 22 degrees and drizzling."
        stubNativeToolTurn("Weather in Apia", postToolText = "It's 22 degrees in Apia.")
        every { kernelAIToolSet.terminalToolDetail() } returns card
        every { kernelAIToolSet.terminalToolSucceeded() } returns true
        every { kernelAIToolSet.terminalToolPresentation() } returns mockk<ToolPresentation>(relaxed = true)
        every { kernelAIToolSet.terminalToolSpokenSummary() } returns summary
        val viewModel = createViewModel()
        advanceUntilIdle()

        speakVoiceQuery(viewModel, "Weather in Apia")

        assertEquals(summary, spokenText(), "card reply must speak the card summary")
        coVerify { conversationRepository.addMessage(any(), "assistant", card, any(), any()) }
    }

    @Test
    fun `mic tap during first-chunk synthesis cancels the reply speech`() = runTest(dispatcher) {
        val firstChunkSynthesis = CompletableDeferred<VoiceOutputResult>()
        coEvery { voiceStreamingSession.append(any(), any()) } coAnswers { firstChunkSynthesis.await() }
        every { quickIntentRouter.route("Tell me about kiwi birds") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me about kiwi birds")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token("Kiwi birds are flightless. They nest on the ground."),
            GenerationResult.Complete(durationMs = 1L),
        )
        val viewModel = createViewModel()
        advanceUntilIdle()
        speakVoiceQuery(viewModel, "Tell me about kiwi birds")
        assertEquals(
            ChatViewModel.VoicePlaybackState.Idle,
            viewModel.voicePlaybackState.value,
            "precondition: speech has not started, so only the speech job is in flight",
        )

        clearMocks(voiceOutputController, answers = false)
        viewModel.startVoiceInput()
        advanceUntilIdle()

        verify(exactly = 1) { voiceOutputController.stop() }
        firstChunkSynthesis.complete(VoiceOutputResult.Spoken)
    }

    @Test
    fun `final reply is spoken once, chunked by sentence`() = runTest(dispatcher) {
        val reply = "In Brisbane, it's 22 degrees, drizzle, feeling like 25 degrees. " +
            "Today's high is 28 degrees, low is 21 degrees. " +
            "Rain chance is 87 percent, so take an umbrella if you're heading out this afternoon."
        every { quickIntentRouter.route("Tell me about Brisbane") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me about Brisbane")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token(reply),
            GenerationResult.Complete(durationMs = 1L),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Tell me about Brisbane", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(streamedChunks.size > 1, "reply was not split by sentence: $streamedChunks")
        assertEquals(1, streamedChunks.count { (_, isFinal) -> isFinal }, "final chunks: $streamedChunks")
        assertTrue(streamedChunks.last().second, "last chunk is not final: $streamedChunks")
        assertEquals(1, streamedChunks.count { (chunk, _) -> chunk.contains("umbrella") }, "reply spoken more than once")
    }

    @Test
    fun `stop during the final reply cancels the remaining chunks`() = runTest(dispatcher) {
        val reply = "In Brisbane, it's 22 degrees, drizzle, feeling like 25 degrees. " +
            "Today's high is 28 degrees, low is 21 degrees. " +
            "Rain chance is 87 percent, so take an umbrella if you're heading out this afternoon."
        val gate = CompletableDeferred<Unit>()
        coEvery { voiceStreamingSession.append(any(), any()) } coAnswers {
            streamedChunks += firstArg<String>() to secondArg<Boolean>()
            gate.await()
            VoiceOutputResult.Spoken
        }
        every { quickIntentRouter.route("Tell me about Brisbane") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me about Brisbane")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token(reply),
            GenerationResult.Complete(durationMs = 1L),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Tell me about Brisbane", captureSessionId = 1L))
        advanceUntilIdle()
        viewModel.stopVoiceOutput()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, streamedChunks.size, "a chunk was queued after stop: $streamedChunks")
        coVerify(atLeast = 1) { voiceStreamingSession.cancel() }
    }

    @Test
    fun `save memory router match bypasses llm wrapper`() = runTest(dispatcher) {
        val saveMemorySkill = object : Skill {
            override val name = "save_memory"
            override val description = "Save memory"
            override val schema = SkillSchema(required = listOf("content"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.Success("✓ Saved: \"I have a dog named Xena\".")
        }
        every {
            quickIntentRouter.route("Can you remember that I have a dog named Xena")
        } returns QuickIntentRouter.RouteResult.RegexMatch(
            QuickIntentRouter.MatchedIntent(
                intentName = "save_memory",
                params = mapOf("content" to "I have a dog named Xena"),
            )
        )
        every { skillRegistry.get("save_memory") } returns saveMemorySkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("Can you remember that I have a dog named Xena")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(
            "You: Can you remember that I have a dog named Xena\nJandal: ✓ Saved: \"I have a dog named Xena\".",
            viewModel.getConversationAsText(),
        )
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `anaphoric important date bypass uses direct reply path and speaks confirmation`() = runTest(dispatcher) {
        val saveImportantDateSkill = object : Skill {
            override val name = "save_important_date"
            override val description = "Save important date"
            override val schema = SkillSchema(required = listOf("label", "date"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.DirectReply(
                    "I'll remember your birthday is 15 March.",
                    spokenSummary = "I'll remember your birthday is 15 March.",
                )
        }
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "user",
                content = "My birthday is March 15th",
                thinkingText = null,
                timestamp = 1L,
            ),
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-2",
                conversationId = "conv-existing",
                role = "assistant",
                content = "Noted.",
                thinkingText = null,
                timestamp = 2L,
            ),
        )
        every { quickIntentRouter.route("remember that") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "remember that")
        every { quickIntentRouter.route("My birthday is March 15th") } returns
            QuickIntentRouter.RouteResult.RegexMatch(
                QuickIntentRouter.MatchedIntent(
                    intentName = "save_important_date",
                    params = mapOf("label" to "birthday", "date" to "March 15th"),
                ),
            )
        every { skillRegistry.get("save_important_date") } returns saveImportantDateSkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "remember that", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(viewModel.getConversationAsText().contains("Jandal: I'll remember your birthday is 15 March."))
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        assertEquals(listOf("I'll remember your birthday is 15 March." to true), streamedChunks)
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `polite anaphoric important date bypass uses direct reply path`() = runTest(dispatcher) {
        val saveImportantDateSkill = object : Skill {
            override val name = "save_important_date"
            override val description = "Save important date"
            override val schema = SkillSchema(required = listOf("label", "date"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.DirectReply(
                    "I'll remember your birthday is 15 March.",
                    spokenSummary = "I'll remember your birthday is 15 March.",
                )
        }
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "user",
                content = "My birthday is March 15th",
                thinkingText = null,
                timestamp = 1L,
            ),
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-2",
                conversationId = "conv-existing",
                role = "assistant",
                content = "Noted.",
                thinkingText = null,
                timestamp = 2L,
            ),
        )
        every { quickIntentRouter.route("can you remember that") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "can you remember that")
        every { quickIntentRouter.route("My birthday is March 15th") } returns
            QuickIntentRouter.RouteResult.RegexMatch(
                QuickIntentRouter.MatchedIntent(
                    intentName = "save_important_date",
                    params = mapOf("label" to "birthday", "date" to "March 15th"),
                ),
            )
        every { skillRegistry.get("save_important_date") } returns saveImportantDateSkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "can you remember that", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(viewModel.getConversationAsText().contains("Jandal: I'll remember your birthday is 15 March."))
        assertEquals(listOf("I'll remember your birthday is 15 March." to true), streamedChunks)
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `anaphoric important date bypass works even when model is unavailable`() = runTest(dispatcher) {
        val runIntentSkill = object : Skill {
            override val name = "run_intent"
            override val description = "Run intent"
            override val schema = SkillSchema(required = listOf("intent_name"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult {
                assertEquals("save_important_date", call.arguments["intent_name"])
                assertEquals("birthday", call.arguments["label"])
                assertEquals("March 15th", call.arguments["date"])
                return SkillResult.DirectReply(
                    "I'll remember your birthday is 15 March.",
                    spokenSummary = "I'll remember your birthday is 15 March.",
                )
            }
        }
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "user",
                content = "My birthday is March 15th",
                thinkingText = null,
                timestamp = 1L,
            ),
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-2",
                conversationId = "conv-existing",
                role = "assistant",
                content = "Noted.",
                thinkingText = null,
                timestamp = 2L,
            ),
        )
        every { quickIntentRouter.route("remember that") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "remember that")
        every { quickIntentRouter.route("My birthday is March 15th") } returns
            QuickIntentRouter.RouteResult.RegexMatch(
                QuickIntentRouter.MatchedIntent(
                    intentName = "save_important_date",
                    params = mapOf("label" to "birthday", "date" to "March 15th"),
                ),
            )
        every { skillRegistry.get("save_important_date") } returns null
        every { skillRegistry.get("run_intent") } returns runIntentSkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "remember that", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(viewModel.getConversationAsText().contains("Jandal: I'll remember your birthday is 15 March."))
        assertEquals(listOf("I'll remember your birthday is 15 March." to true), streamedChunks)
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `anaphoric save memory surfaces tool failure when model is unavailable`() = runTest(dispatcher) {
        val saveMemorySkill = object : Skill {
            override val name = "save_memory"
            override val description = "Save memory"
            override val schema = SkillSchema(required = listOf("content"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.Failure("save_memory", "Could not save memory right now.")
        }
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        coEvery { conversationRepository.getMessagesOnce("conv-existing") } returns listOf(
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-1",
                conversationId = "conv-existing",
                role = "user",
                content = "I have a dog named Xena",
                thinkingText = null,
                timestamp = 1L,
            ),
            com.kernel.ai.core.memory.entity.MessageEntity(
                id = "msg-2",
                conversationId = "conv-existing",
                role = "assistant",
                content = "Noted.",
                thinkingText = null,
                timestamp = 2L,
            ),
        )
        every { quickIntentRouter.route("remember that") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "remember that")
        every { quickIntentRouter.route("I have a dog named Xena") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "I have a dog named Xena")
        every { skillRegistry.get("save_memory") } returns saveMemorySkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "remember that", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(viewModel.getConversationAsText().contains("Jandal: Could not save memory right now."))
        assertEquals(listOf("Could not save memory right now." to true), streamedChunks)
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `pending confirmation failure surfaces tool error when model is unavailable`() = runTest(dispatcher) {
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        val runIntentSkill = object : Skill {
            override val name = "run_intent"
            override val description = "Run intent"
            override val schema = SkillSchema(required = listOf("intent_name"))

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.Failure("run_intent", "Could not complete the confirmed action.")
        }
        every { skillRegistry.get("run_intent") } returns runIntentSkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        val field = ChatViewModel::class.java.getDeclaredField("pendingConfirmationIntent")
        field.isAccessible = true
        field.set(
            viewModel,
            QuickIntentRouter.MatchedIntent(
                intentName = "toggle_flashlight_on",
                params = emptyMap(),
            ),
        )

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "yes", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(viewModel.getConversationAsText().contains("Jandal: Could not complete the confirmed action."))
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        assertEquals(listOf("Could not complete the confirmed action." to true), streamedChunks)
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `voice reply unavailable resets capture state`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello there") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello there")
        coEvery { voiceStreamingSession.append(any(), any()) } returns VoiceOutputResult.Unavailable("Voice streaming unavailable.")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello there", captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        assertEquals(null, viewModel.voiceMode.value)
        assertEquals(ChatViewModel.VoicePlaybackState.Idle, viewModel.voicePlaybackState.value)
    }

    @Test
    fun `one-shot voice stays idle after spoken reply playback stops`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello one shot") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello one shot")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello one shot", captureSessionId = 1L))
        advanceUntilIdle()

        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        assertEquals("You: Hello one shot\nJandal: Hi back", viewModel.getConversationAsText())
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }

    @Test
    fun `back-and-forth voice re-arms listening after spoken reply playback stops`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello loop") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello loop")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello loop", captureSessionId = 1L))
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }

        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        assertEquals("You: Hello loop\nJandal: Hi back", viewModel.getConversationAsText())
        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), viewModel.voiceCaptureState.value)
        coVerify(exactly = 2) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        coVerify(exactly = 1) {
            voiceOutputController.openStreamingSession(any())
        }
        assertEquals(listOf("Hi back." to true), streamedChunks)
    }

    @Test
    fun `back-and-forth voice re-arms when playback completes without speaking started`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello silent loop") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello silent loop")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello silent loop", captureSessionId = 1L))
        advanceUntilIdle()

        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        assertEquals("You: Hello silent loop\nJandal: Hi back", viewModel.getConversationAsText())
        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), viewModel.voiceCaptureState.value)
        coVerify(exactly = 2) { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }

    @Test
    fun `back-and-forth stop during playback resumes listening`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello stop") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello stop")
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken
        val coordinator = SpeechStopCoordinator(voiceOutputController)
        coordinator.observe(backgroundScope)

        val viewModel = createViewModel(speechStopCoordinator = coordinator)
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello stop", captureSessionId = 1L))
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
        advanceUntilIdle()
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }

        assertTrue(coordinator.requestStop())
        advanceUntilIdle()

        verify(atLeast = 1) { voiceOutputController.stop() }
        coVerify(exactly = 2) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), viewModel.voiceCaptureState.value)
    }

    @Test
    fun `stop during generation re-arms only after generation completes`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello generating") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello generating")
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken
        val generating = MutableStateFlow(false)
        every { inferenceEngine.isGenerating } returns generating
        val coordinator = SpeechStopCoordinator(voiceOutputController)
        coordinator.observe(backgroundScope)

        val viewModel = createViewModel(speechStopCoordinator = coordinator)
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello generating", captureSessionId = 1L))
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
        advanceUntilIdle()

        generating.value = true
        assertTrue(coordinator.requestStop())
        advanceUntilIdle()
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }

        generating.value = false
        advanceUntilIdle()
        coVerify(exactly = 2) { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }

    @Test
    fun `stop for another screen's playback does not re-arm listening here`() = runTest(dispatcher) {
        val coordinator = SpeechStopCoordinator(voiceOutputController)
        coordinator.observe(backgroundScope)

        val viewModel = createViewModel(speechStopCoordinator = coordinator)
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Other screen reply"))
        advanceUntilIdle()

        assertTrue(coordinator.requestStop())
        advanceUntilIdle()

        verify(atLeast = 1) { voiceOutputController.stop() }
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }

    @Test
    fun `stopVoiceOutput prevents speaking stopped from restarting back-and-forth capture`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Stop speaking") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Stop speaking")
        val speakGate = CompletableDeferred<Unit>()
        coEvery { voiceStreamingSession.append(any(), any()) } coAnswers {
            voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
            speakGate.await()
            VoiceOutputResult.Spoken
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Stop speaking", captureSessionId = 1L))
        runCurrent()

        viewModel.stopVoiceOutput()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        speakGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        verify(atLeast = 1) { voiceOutputController.stop() }
    }

    @Test
    fun `speaker button state stays active until playback stops`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.speakMessage("assistant-1", "Hello there")
        advanceUntilIdle()

        assertEquals("assistant-1", viewModel.speakingMessageId.value)

        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        assertNull(viewModel.speakingMessageId.value)
    }

    @Test
    fun `home stop cancels read-aloud speech and clears the speaker state`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.speakMessage("assistant-1", "Hello there")
        advanceUntilIdle()
        assertEquals("assistant-1", viewModel.speakingMessageId.value)

        clearMocks(voiceOutputController, answers = false)
        viewModel.onScreenStopped(screenOff = false)
        advanceUntilIdle()

        verify(atLeast = 1) { voiceOutputController.stop() }
        assertNull(viewModel.speakingMessageId.value)
        assertEquals(ChatViewModel.VoicePlaybackState.Idle, viewModel.voicePlaybackState.value)
    }

    @Test
    fun `initial voice query submitted after Home is sent as text and not spoken`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Tell me a story") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Tell me a story")
        every { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token("Once upon a time there was a dog. "),
            GenerationResult.Complete(durationMs = 1L),
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onScreenStopped(screenOff = false)
        clearMocks(voiceOutputController, answers = false)
        viewModel.submitInitialQueryIfNeeded("Tell me a story", speakResponse = true)
        advanceUntilIdle()

        verify(atLeast = 1) { inferenceEngine.generate(any()) }
        coVerify(exactly = 0) { voiceOutputController.openStreamingSession(any()) }
        coVerify(exactly = 0) { voiceOutputController.speak(any()) }
    }

    @Test
    fun `screen-off stop keeps read-aloud speech playing`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.speakMessage("assistant-1", "Hello there")
        advanceUntilIdle()
        clearMocks(voiceOutputController, answers = false)

        viewModel.onScreenStopped(screenOff = true)
        advanceUntilIdle()

        verify(exactly = 0) { voiceOutputController.stop() }
        assertEquals("assistant-1", viewModel.speakingMessageId.value)
    }

    @Test
    fun `stopVoiceInput clears the back-and-forth loop before a pending re-arm`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Stop listening") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Stop listening")
        val speakGate = CompletableDeferred<Unit>()
        coEvery { voiceStreamingSession.append(any(), any()) } coAnswers {
            voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
            speakGate.await()
            VoiceOutputResult.Spoken
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Stop listening", captureSessionId = 1L))
        runCurrent()

        viewModel.stopVoiceInput()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        speakGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        verify(exactly = 1) { voiceInputController.stopListening() }
    }

    @Test
    fun `voice weather not now terminates back-and-forth turn without assistant response`() = runTest(dispatcher) {
        val input = "What's the weather"
        val weatherSkill = object : Skill {
            override val name = "get_weather"
            override val description = "Get weather"
            override val schema = SkillSchema()

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.CapabilityRequired(
                    capabilityKey = CapabilityKey.WeatherCurrentLocation,
                    skillName = name,
                )
        }
        every { quickIntentRouter.route(input) } returns
            QuickIntentRouter.RouteResult.RegexMatch(
                QuickIntentRouter.MatchedIntent(
                    intentName = "get_weather",
                    params = emptyMap(),
                ),
            )
        every { skillRegistry.get("get_weather") } returns weatherSkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, input, captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals(
            ChatViewModel.VoiceCaptureState.Processing(input),
            viewModel.voiceCaptureState.value,
        )
        assertEquals(ChatViewModel.VoiceMode.BackAndForth, viewModel.voiceMode.value)
        assertEquals(ChatViewModel.WeatherLocationState(), viewModel.weatherLocationState.value)

        voiceInputEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, 1L, cancelled = true))
        advanceUntilIdle()
        assertEquals(
            ChatViewModel.VoiceCaptureState.Processing(input),
            viewModel.voiceCaptureState.value,
        )

        viewModel.dismissWeatherLocationDialog()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        assertNull(viewModel.voiceMode.value)
        assertNull(viewModel.weatherLocationState.value)
        assertEquals("You: $input", viewModel.getConversationAsText())
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        verify(exactly = 1) { voiceInputController.stopListening() }
    }

    @Test
    fun `voice weather named-place fallback terminates turn and keeps typing guidance`() = runTest(dispatcher) {
        val input = "What's the weather"
        val weatherSkill = object : Skill {
            override val name = "get_weather"
            override val description = "Get weather"
            override val schema = SkillSchema()

            override suspend fun execute(call: com.kernel.ai.core.skills.SkillCall): SkillResult =
                SkillResult.CapabilityRequired(
                    capabilityKey = CapabilityKey.WeatherCurrentLocation,
                    skillName = name,
                )
        }
        every { quickIntentRouter.route(input) } returns
            QuickIntentRouter.RouteResult.RegexMatch(
                QuickIntentRouter.MatchedIntent(
                    intentName = "get_weather",
                    params = emptyMap(),
                ),
            )
        every { skillRegistry.get("get_weather") } returns weatherSkill

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, input, captureSessionId = 1L))
        advanceUntilIdle()

        val errorState = async {
            viewModel.uiState.first { state ->
                state is ChatUiState.Ready && state.error != null
            } as ChatUiState.Ready
        }
        viewModel.onWeatherLocationTypePlace()
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        assertNull(viewModel.voiceMode.value)
        assertNull(viewModel.weatherLocationState.value)
        assertEquals(
            "Type a place name in the chat input, like \"weather in Tokyo\".",
            errorState.await().error,
        )
        assertEquals("You: $input", viewModel.getConversationAsText())
        verify(exactly = 1) { voiceInputController.stopListening() }
    }

    @Test
    fun `hand-off note is shown and a handed-off chat capture is not silently retried`() = runTest(dispatcher) {
        // Posted while the start ran (start-time fallback): only the replay can deliver it.
        voiceInputNotices.emit(VoiceNotice(1L, "Using Zipformer for this request."))
        val viewModel = createViewModel()
        advanceUntilIdle()

        val noteState = async {
            viewModel.uiState.first { state ->
                state is ChatUiState.Ready && state.error == "Using Zipformer for this request."
            }
        }
        viewModel.startVoiceInput()
        advanceUntilIdle()
        noteState.await()

        val errorState = async {
            viewModel.uiState.first { state ->
                state is ChatUiState.Ready && state.error?.startsWith("I didn't catch anything") == true
            }
        }
        voiceInputEvents.emit(
            VoiceInputEvent.Error(VoiceCaptureMode.Command, "I didn't catch anything", captureSessionId = 1L),
        )
        advanceUntilIdle()
        errorState.await()

        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
    }


    @Test
    fun `stop phrase in back-and-forth loop ends the session without submitting a message`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        advanceUntilIdle()

        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stop", captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals("", viewModel.getConversationAsText())
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        assertEquals(null, viewModel.voiceMode.value)
        verify(exactly = 1) { voiceInputController.stopListening() }
    }

    @Test
    fun `cancel phrase in back-and-forth loop ends the session`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        advanceUntilIdle()

        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "cancel", captureSessionId = 1L))
        advanceUntilIdle()
        assertEquals("", viewModel.getConversationAsText())
    }
    @Test
    fun `ListeningStarted for owned Command session triggers cue player`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started(1L)
        viewModel.startVoiceInput()
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command, captureSessionId = 1L))
        advanceUntilIdle()
        verify(exactly = 1) { startListeningCuePlayer.playCue(StartListeningCueContext.FOREGROUND) }
    }
    @Test
    fun `ListeningStarted for unowned mode does not trigger cue player`() = runTest(dispatcher) {
        // ChatViewModel is Idle — it owns nothing. An event from an AlertCommand
        // session started elsewhere should be silently ignored.
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.AlertCommand, captureSessionId = 1L))
        advanceUntilIdle()
        verify(exactly = 0) { startListeningCuePlayer.playCue(any()) }
    }

    @Test
    fun `transcript event does not trigger cue`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started(1L)
        viewModel.startVoiceInput()
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "hello", captureSessionId = 1L))
        advanceUntilIdle()
        verify(exactly = 0) { startListeningCuePlayer.playCue(any()) }
    }

    @Test
    fun `error event does not trigger cue`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started(1L)
        viewModel.startVoiceInput()
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.Error(VoiceCaptureMode.Command, "error", captureSessionId = 1L))
        advanceUntilIdle()
        verify(exactly = 0) { startListeningCuePlayer.playCue(any()) }
    }

    @Test
    fun `stopped event does not trigger cue`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started(1L)
        viewModel.startVoiceInput()
        runCurrent()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command, captureSessionId = 1L))
        advanceUntilIdle()
        verify(exactly = 0) { startListeningCuePlayer.playCue(any()) }
    }
    @Test
    fun `stop phrase in one-shot mode falls through normally`() = runTest(dispatcher) {
        every { quickIntentRouter.route("stop") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "stop")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "stop", captureSessionId = 1L))
        advanceUntilIdle()

        // In one-shot mode "stop" is treated as a regular message
        assertTrue(viewModel.getConversationAsText().contains("You: stop"))
    }

    @Test
    fun `non-stop phrase in back-and-forth mode submits normally`() = runTest(dispatcher) {
        every { quickIntentRouter.route("what time is it") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "what time is it")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "what time is it", captureSessionId = 1L))
        advanceUntilIdle()

        assertTrue(viewModel.getConversationAsText().contains("You: what time is it"))
    }

    @Test
    fun `meal planner activity clears processing transcript and switches to planner status`() = runTest(dispatcher) {
        val workingActivity = MealPlannerActivity(
            title = "Generating meal plan",
            subtitle = "Drafting 5 dinners for 2 people.",
            state = MealPlannerActivityState.WORKING,
        )
        val waitingActivity = MealPlannerActivity(
            title = "Meal plan ready",
            subtitle = "Say 'show current plan' or 'done meal planning'.",
            state = MealPlannerActivityState.WAITING,
        )
        val releaseReply = CompletableDeferred<Unit>()
        every { quickIntentRouter.route("no protein preferences") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "no protein preferences")
        coEvery { mealPlanSessionRepository.hasActiveSessionForConversation("conv-existing") } returns true
        coEvery { mealPlannerCoordinator.activeSessionActivity("conv-existing") } returnsMany listOf(null, waitingActivity)
        coEvery {
            mealPlannerCoordinator.ingestUserMessage(
                conversationId = "conv-existing",
                text = "no protein preferences",
                onPlannerMessage = any(),
                onPlannerActivityChanged = any(),
            )
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val onPlannerMessage = invocation.args[2] as (suspend (String) -> Unit)
            @Suppress("UNCHECKED_CAST")
            val onPlannerActivityChanged = invocation.args[3] as (suspend (MealPlannerActivity) -> Unit)
            onPlannerActivityChanged(workingActivity)
            onPlannerMessage("Generating meal plan…")
            releaseReply.await()
            MealPlannerReply("Your meal plan is ready.")
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "no protein preferences", captureSessionId = 1L))
        runCurrent()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        assertEquals(workingActivity, viewModel.mealPlannerActivity.value)

        releaseReply.complete(Unit)
        advanceUntilIdle()

        assertEquals(waitingActivity, viewModel.mealPlannerActivity.value)
    }

    @Test
    fun `replace smart reply selection prefills the composer`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSmartReplySelected(MealPlannerSuggestion("Generate recipes", "generate recipes"))

        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals("generate recipes", state.inputText)
            assertEquals(1L, state.inputTextReplacementRevision)
            coVerify(exactly = 0) { conversationRepository.addMessage(any(), any(), any(), any(), any()) }
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `typed and replaced drafts are saved and restored after process death`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("conversationId" to "conv-existing"))
        val viewModel = createViewModel(handle)
        advanceUntilIdle()
        try {
            viewModel.onInputChanged("hello")
            assertEquals("hello", handle.get<String>("inputText"))
            viewModel.replaceInputText("Smart reply")
            assertEquals("Smart reply", handle.get<String>("inputText"))
            assertEquals("Smart reply", handle.get<String>("inputTextReplacementText"))
            assertEquals(1L, handle.get<Long>("inputTextReplacementRevision"))
        } finally {
            clearViewModel(viewModel)
        }

        // A new ViewModel built from the saved state (process death) sees the same draft, so
        // the restored composer and sendMessage agree instead of Send silently doing nothing.
        val restored = createViewModel(
            SavedStateHandle(
                mapOf(
                    "conversationId" to "conv-existing",
                    "inputText" to "hello again",
                    "inputTextReplacementRevision" to 3L,
                    "inputTextReplacementText" to "",
                ),
            ),
        )
        advanceUntilIdle()
        try {
            val state = restored.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals("hello again", state.inputText)
            assertEquals(3L, state.inputTextReplacementRevision)
        } finally {
            clearViewModel(restored)
        }
    }

    @Test
    fun `explicit draft replacement advances revision but typing echoes do not`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        try {
            viewModel.replaceInputText("External draft")
            advanceUntilIdle()
            var state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals("External draft", state.inputText)
            assertEquals(1L, state.inputTextReplacementRevision)

            viewModel.onInputChanged("Typed text")
            state = viewModel.uiState.first {
                it is ChatUiState.Ready && it.inputText == "Typed text"
            } as ChatUiState.Ready
            assertEquals(1L, state.inputTextReplacementRevision)
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `append smart reply selection adds comma separated values`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSmartReplySelected(
            MealPlannerSuggestion("Gluten free", "gluten free", MealPlannerSuggestionComposeMode.APPEND_COMMA),
        )
        viewModel.onSmartReplySelected(
            MealPlannerSuggestion("Nut free", "nut free", MealPlannerSuggestionComposeMode.APPEND_COMMA),
        )

        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals("gluten free, nut free", state.inputText)
            assertEquals(2L, state.inputTextReplacementRevision)
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `append smart reply selection preserves existing typed text`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onInputChanged("beef")

        viewModel.onSmartReplySelected(
            MealPlannerSuggestion("Chicken", "chicken", MealPlannerSuggestionComposeMode.APPEND_COMMA),
        )

        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals("beef, chicken", state.inputText)
        } finally {
            clearViewModel(viewModel)
        }
    }

    @Test
    fun `planner reply syncs placeholder conversation title to canonical meal plan name`() = runTest(dispatcher) {
        val snapshot = MealPlanSnapshot(
            sessionId = "session-1",
            conversationId = "conv-existing",
            displayName = "Meal Plan 2026-05-17 (MP-001)",
            status = MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY,
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
                    status = MealPlanDayStatus.PERSISTED,
                    currentRecipeVersion = 1,
                    attemptCount = 1,
                    lastErrorCode = null,
                    lastErrorMessage = null,
                    currentRecipe = null,
                    recipeKey = null,
                    isFavouriteRecipe = false,
                ),
            ),
        )
        coEvery { conversationRepository.getConversation("conv-existing") } returns
            ConversationEntity(id = "conv-existing", title = "plan meals…", createdAt = 1L, updatedAt = 1L)
        every { quickIntentRouter.route("generate recipes") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "generate recipes")
        coEvery { mealPlanSessionRepository.hasActiveSessionForConversation("conv-existing") } returns true
        coEvery { mealPlanSessionRepository.getActiveSession("conv-existing") } returnsMany listOf(null, snapshot)
        coEvery { mealPlannerCoordinator.activeSessionActivity("conv-existing") } returns null
        coEvery {
            mealPlannerCoordinator.ingestUserMessage(
                conversationId = "conv-existing",
                text = "generate recipes",
                onPlannerMessage = any(),
                onPlannerActivityChanged = any(),
            )
        } returns MealPlannerReply("Your meal plan is ready.")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("generate recipes")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) { conversationRepository.renameConversation("conv-existing", snapshot.displayName) }
        try {
            val state = viewModel.uiState.first { it is ChatUiState.Ready } as ChatUiState.Ready
            assertEquals(snapshot.displayName, state.conversationTitle)
        } finally {
            clearViewModel(viewModel)
        }
    }
    
    // ── Microphone repair & pending-action preservation ─────────────────────
    
    @Test
    fun `permanent mic denial stores voice mode and shows blocked state`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "loop")
        advanceUntilIdle()
        
        assertTrue(viewModel.microphoneState.value?.isPermanentlyDenied == true)
    }
    
    @Test
    fun `non-permanent mic denial clears pending mode`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "loop")
        advanceUntilIdle()
        // Open settings — should NOT clear pending mode.
        viewModel.onChatMicrophoneOpenAppPermissions()
        advanceUntilIdle()
        // Clear with non-permanent denial — should clear everything.
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        
        assertNull(viewModel.microphoneState.value)
    }
    
    @Test
    fun `mic repair resume with grant restarts one shot voice input`() = runTest(dispatcher) {
        coEvery { voiceInputController.startListening(VoiceCaptureMode.Command) } returns
            VoiceInputStartResult.Started(1L)
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "ptt")
        advanceUntilIdle()
        viewModel.onChatMicrophoneOpenAppPermissions()
        advanceUntilIdle()
        viewModel.onChatMicRepairResumeCheck(hasPermission = true)
        advanceUntilIdle()
        
        coVerify { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }
    
    @Test
    fun `mic repair resume with grant restarts back and forth voice`() = runTest(dispatcher) {
        coEvery { voiceInputController.startListening(VoiceCaptureMode.Command) } returns
            VoiceInputStartResult.Started(1L)
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "loop")
        advanceUntilIdle()
        viewModel.onChatMicrophoneOpenAppPermissions()
        advanceUntilIdle()
        viewModel.onChatMicRepairResumeCheck(hasPermission = true)
        advanceUntilIdle()
        
        coVerify { voiceInputController.startListening(VoiceCaptureMode.Command) }
    }
    
    @Test
    fun `mic repair resume without grant shows blocked state`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "ptt")
        advanceUntilIdle()
        viewModel.onChatMicrophoneOpenAppPermissions()
        advanceUntilIdle()
        viewModel.onChatMicRepairResumeCheck(hasPermission = false)
        advanceUntilIdle()
        
        assertTrue(viewModel.microphoneState.value?.isPermanentlyDenied == true)
    }
    
    @Test
    fun `mic keep typing clears all state`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "ptt")
        advanceUntilIdle()
        viewModel.onChatMicrophoneKeepTyping()
        advanceUntilIdle()
        
        assertNull(viewModel.microphoneState.value)
    }
    
    @Test
    fun `mic dismiss clears all state`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "ptt")
        advanceUntilIdle()
        viewModel.dismissMicrophoneRepairDialog()
        advanceUntilIdle()
        
        assertNull(viewModel.microphoneState.value)
    }
    
    @Test
    fun `mic open app permissions keeps microphone state`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        
        // First denial primes classifier to retryable
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = true)
        advanceUntilIdle()
        viewModel.onMicrophonePermissionDenied(shouldShowRationale = false, requestedAction = "ptt")
        advanceUntilIdle()
        viewModel.onChatMicrophoneOpenAppPermissions()
        advanceUntilIdle()
        
        // State should still be set — not cleared — because we need it for resume check.
        assertTrue(viewModel.microphoneState.value?.isPermanentlyDenied == true)
    }


    private fun clearViewModel(viewModel: ChatViewModel) {
        androidx.lifecycle.ViewModelStore()
            .apply { put("chat-view-model", viewModel) }
            .clear()
    }

    @Test
    fun `calendar permission reply is spoken on a voice-initiated turn`() = runTest(dispatcher) {
        val input = "add dentist to my calendar tomorrow at 9am"
        every { quickIntentRouter.route(input) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = input,
            bestGuess = QuickIntentRouter.MatchedIntent(
                intentName = "create_calendar_event",
                params = emptyMap(),
                source = "classifier",
            ),
            bestConfidence = 0.72f,
        )
        every { intentRecoveryOrchestrator.recover(any(), any(), any()) } returns RecoveryResult.Execute(
            intentName = "create_calendar_event",
            params = emptyMap(),
        )
        val calendarSkill = object : Skill {
            override val name = "create_calendar_event"
            override val description = "Create calendar event"
            override val schema = SkillSchema()
            override suspend fun execute(call: SkillCall): SkillResult = SkillResult.CapabilityRequired(
                capabilityKey = CapabilityKey.CalendarWrite,
                skillName = "create_calendar_event",
            )
        }
        every { skillRegistry.get("create_calendar_event") } returns calendarSkill

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, input, captureSessionId = 1L))
        advanceUntilIdle()

        coVerify {
            voiceOutputController.openStreamingSession(
                match<VoiceSpeakRequest> { it.text.contains("Allow it when prompted") },
            )
        }
        coVerify {
            conversationRepository.addMessage(
                "conv-existing",
                "assistant",
                match { it.contains("Allow it when prompted") },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `calendar line on a voice model turn is spoken once with the turn's spoken summary`() = runTest(dispatcher) {
        var nativeToolRan = false
        every { quickIntentRouter.route("Add dentist to my calendar") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Add dentist to my calendar")
        every { kernelAIToolSet.terminalToolCalledInCurrentAttempt() } answers { nativeToolRan }
        every { kernelAIToolSet.terminalToolName() } answers { if (nativeToolRan) "create_calendar_event" else null }
        every { kernelAIToolSet.terminalToolRequest() } returns "{}"
        every { kernelAIToolSet.terminalToolDetail() } returns ""
        every { kernelAIToolSet.terminalToolResult() } returns "Calendar permission needed."
        every { kernelAIToolSet.terminalToolSucceeded() } returns false
        every { kernelAIToolSet.terminalToolFailed() } returns true
        every { kernelAIToolSet.lastToolCapabilityRequired() } returns CapabilityKey.CalendarWrite
        every { inferenceEngine.generate(any()) } returns kotlinx.coroutines.flow.flow {
            emit(GenerationResult.Token("Sure, one moment. "))
            nativeToolRan = true
            emit(GenerationResult.Token("I can add that once calendar access is on."))
            emit(GenerationResult.Complete(durationMs = 1L))
        }

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Add dentist to my calendar", captureSessionId = 1L))
        advanceUntilIdle()

        coVerify(exactly = 1) { voiceOutputController.openStreamingSession(any()) }
        val spoken = streamedChunks.joinToString(" ") { (chunk, _) -> chunk }
        assertTrue(spoken.contains("Calendar permission needed."), "tool summary not spoken: $spoken")
        assertTrue(
            spoken.contains("Allow it when prompted, or turn on Calendar in App info"),
            "calendar line not spoken: $spoken",
        )
    }

    @Test
    fun `calendar reply on a voice turn with auto-speak off does not leave voice processing`() = runTest(dispatcher) {
        every { voiceOutputPreferences.autoSpeak } returns MutableStateFlow(false)
        val input = "add dentist to my calendar tomorrow at 9am"
        every { quickIntentRouter.route(input) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = input,
            bestGuess = QuickIntentRouter.MatchedIntent(
                intentName = "create_calendar_event",
                params = emptyMap(),
                source = "classifier",
            ),
            bestConfidence = 0.72f,
        )
        every { intentRecoveryOrchestrator.recover(any(), any(), any()) } returns RecoveryResult.Execute(
            intentName = "create_calendar_event",
            params = emptyMap(),
        )
        val calendarSkill = object : Skill {
            override val name = "create_calendar_event"
            override val description = "Create calendar event"
            override val schema = SkillSchema()
            override suspend fun execute(call: SkillCall): SkillResult = SkillResult.CapabilityRequired(
                capabilityKey = CapabilityKey.CalendarWrite,
                skillName = "create_calendar_event",
            )
        }
        every { skillRegistry.get("create_calendar_event") } returns calendarSkill

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, input, captureSessionId = 1L))
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 0) { voiceOutputController.openStreamingSession(any()) }
    }
    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
        speechStopCoordinator: SpeechStopCoordinator = SpeechStopCoordinator(voiceOutputController),
    ): ChatViewModel = ChatViewModel(savedStateHandle = savedStateHandle, chatPreferences = chatPreferences, authRepository = authRepository,
    speechStopCoordinator = speechStopCoordinator,
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
    voiceInputPreferences = voiceInputPreferences,
    mealPlanSessionRepository = mealPlanSessionRepository,
    mealPlannerCoordinator = mealPlannerCoordinator,
)
}
