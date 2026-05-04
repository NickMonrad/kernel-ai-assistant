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
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    private val episodicDistillationUseCase: EpisodicDistillationUseCase = mockk(relaxed = true)
    private val modelSettingsRepository: ModelSettingsRepository = mockk(relaxed = true)
    private val skillRegistry: SkillRegistry = mockk(relaxed = true)
    private val skillExecutor: SkillExecutor = mockk(relaxed = true)
    private val quickIntentRouter: QuickIntentRouter = mockk(relaxed = true)
    private val slotFillerManager: SlotFillerManager = mockk(relaxed = true)
    private val kernelAIToolSet: KernelAIToolSet = mockk(relaxed = true)
    private val toolProvider: ToolProvider = mockk(relaxed = true)
    private val embeddingEngine: EmbeddingEngine = mockk(relaxed = true)
    private val voiceInputController: VoiceInputController = mockk(relaxed = true)
    private val voiceOutputController: VoiceOutputController = mockk(relaxed = true)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk(relaxed = true)
    private val jandalPersona: JandalPersona = mockk(relaxed = true)
    private val nzTruthSeedingService: NzTruthSeedingService = mockk(relaxed = true)
    private val verboseLoggingPreferenceUseCase: VerboseLoggingPreferenceUseCase = mockk(relaxed = true)

    private val voiceInputEvents = MutableSharedFlow<VoiceInputEvent>()
    private val voiceOutputEvents = MutableSharedFlow<VoiceOutputEvent>()
    private val spokenResponsesEnabled = MutableStateFlow(true)

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
        every { inferenceEngine.generate(any()) } returns
            flowOf(GenerationResult.Token("Hi back"), GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } just runs
        coEvery { inferenceEngine.resetConversation() } just runs

        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

        coEvery { conversationRepository.getConversation(any()) } answers {
            val id = firstArg<String>()
            ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L)
        }
        coEvery { conversationRepository.getMessagesOnce(any()) } returns emptyList()
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany
            listOf("user-msg", "assistant-msg")

        every { jandalPersona.personaMode } returns MutableStateFlow(PersonaMode.FULL)
        every { jandalPersona.currentPersonaMode } returns PersonaMode.FULL
        every { voiceInputController.events } returns voiceInputEvents
        coEvery { voiceInputController.startListening(VoiceCaptureMode.Command) } returns VoiceInputStartResult.Started
        every { voiceInputController.stopListening() } just runs
        every { voiceOutputController.events } returns voiceOutputEvents
        coEvery { voiceOutputController.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken
        every { voiceOutputController.stop() } just runs
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
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
    fun `alert command transcript does not reach chat llm flow`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.AlertCommand, "stop"))
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
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello there"))
        advanceUntilIdle()

        assertEquals("You: Hello there\nJandal: Hi back", viewModel.getConversationAsText())
        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify {
            voiceOutputController.speak(
                match<VoiceSpeakRequest> { it.text == "Hi back" }
            )
        }
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
        coVerify(exactly = 0) { voiceOutputController.speak(any()) }
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
        coVerify(exactly = 0) { voiceOutputController.speak(any()) }
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
    fun `one-shot voice stays idle after spoken reply playback stops`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Hello one shot") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Hello one shot")
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello one shot"))
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
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Hello loop"))
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }

        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        assertEquals("You: Hello loop\nJandal: Hi back", viewModel.getConversationAsText())
        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), viewModel.voiceCaptureState.value)
        coVerify(exactly = 2) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        coVerify(exactly = 1) {
            voiceOutputController.speak(match<VoiceSpeakRequest> { it.text == "Hi back" })
        }
    }

    @Test
    fun `stopVoiceOutput prevents speaking stopped from restarting back-and-forth capture`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Stop speaking") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Stop speaking")
        val speakGate = CompletableDeferred<Unit>()
        coEvery { voiceOutputController.speak(any()) } coAnswers {
            voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
            speakGate.await()
            VoiceOutputResult.Spoken
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Stop speaking"))
        runCurrent()

        viewModel.stopVoiceOutput()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        speakGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        verify(exactly = 1) { voiceOutputController.stop() }
    }

    @Test
    fun `stopVoiceInput clears the back-and-forth loop before a pending re-arm`() = runTest(dispatcher) {
        every { quickIntentRouter.route("Stop listening") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "Stop listening")
        val speakGate = CompletableDeferred<Unit>()
        coEvery { voiceOutputController.speak(any()) } coAnswers {
            voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Hi back"))
            speakGate.await()
            VoiceOutputResult.Spoken
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startBackAndForthVoiceInput()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "Stop listening"))
        runCurrent()

        viewModel.stopVoiceInput()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        speakGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ChatViewModel.VoiceCaptureState.Idle, viewModel.voiceCaptureState.value)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        verify(exactly = 1) { voiceInputController.stopListening() }
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
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
        slotFillerManager = slotFillerManager,
        kernelAIToolSet = kernelAIToolSet,
        toolProvider = toolProvider,
        embeddingEngine = embeddingEngine,
        voiceInputController = voiceInputController,
        voiceOutputController = voiceOutputController,
        voiceOutputPreferences = voiceOutputPreferences,
        jandalPersona = jandalPersona,
        nzTruthSeedingService = nzTruthSeedingService,
        verboseLoggingPreferenceUseCase = verboseLoggingPreferenceUseCase,
    )
}
