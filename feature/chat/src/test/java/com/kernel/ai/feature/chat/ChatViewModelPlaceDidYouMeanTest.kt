package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
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
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.PlaceDidYouMeanSuggester
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.skills.slot.SlotValidationResult
import com.kernel.ai.core.voice.SpeechStopCoordinator
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputResult
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #2079 typed chat path for the "Did you mean <place>?" question. The suggester is mocked: these tests
 * cover the ViewModel's ask, yes, no, drop and fall-through logic. Matcher behaviour is in core/skills.
 *
 * The matcher runs on Dispatchers.Default (real time), so the helpers pump the test scheduler without
 * advancing virtual time; the question's bound is virtual and only the fall-through tests advance it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPlaceDidYouMeanTest {

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
    private val chatPreferences: ChatPreferences = mockk(relaxed = true)
    private val voiceInputPreferences: com.kernel.ai.core.voice.VoiceInputPreferences = mockk(relaxed = true)
    private val intentRecoveryOrchestrator: IntentRecoveryOrchestrator = mockk(relaxed = true)
    private val authRepository: HuggingFaceAuthRepository = mockk(relaxed = true)
    private val suggester: PlaceDidYouMeanSuggester = mockk()
    private val voiceInputEvents = MutableSharedFlow<VoiceInputEvent>()
    private val voiceOutputEvents = MutableSharedFlow<VoiceOutputEvent>()
    private val placeDidYouMeanOn = MutableStateFlow(true)

    private val navigateRuns = mutableListOf<SkillCall>()
    private val navigateSkill = object : Skill {
        override val name = "navigate_to"
        override val description = "Navigate"
        override val schema = SkillSchema()

        override suspend fun execute(call: SkillCall): SkillResult {
            navigateRuns += call
            return SkillResult.DirectReply("Navigating.")
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        navigateRuns.clear()
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.isGenerating } returns MutableStateFlow(false)
        every { inferenceEngine.activeBackend } returns MutableStateFlow<BackendType?>(null)
        every { inferenceEngine.resolvedMaxTokens } returns MutableStateFlow(0)
        every { inferenceEngine.evictionEvents } returns emptyFlow()
        every { inferenceEngine.generateWithDebugTrace(any(), any()) } answers {
            inferenceEngine.generate(firstArg())
        }
        coEvery { inferenceEngine.updateSystemPrompt(any()) } just runs
        coEvery { inferenceEngine.resetConversation() } just runs

        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns true
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

        coEvery { conversationRepository.getConversation(any()) } answers {
            ConversationEntity(id = firstArg(), title = null, createdAt = 1L, updatedAt = 1L)
        }
        coEvery { conversationRepository.getMessagesOnce(any()) } returns emptyList()
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returns "msg"
        every { conversationRepository.observeConversationById(any()) } answers {
            flowOf(ConversationEntity(id = firstArg(), title = null, createdAt = 1L, updatedAt = 1L))
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

        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()

        every { skillRegistry.get("navigate_to") } returns navigateSkill

        every { nzTruthSeedingService.isSeeding } returns MutableStateFlow(false)
        every { nzTruthSeedingService.seedIfNeeded() } just runs
        coEvery { verboseLoggingPreferenceUseCase.loadAndApplyVerboseLoggingPreference() } just runs

        every { voiceOutputPreferences.spokenResponsesEnabled } returns MutableStateFlow(false)
        every { voiceOutputPreferences.autoSpeak } returns MutableStateFlow(false)
        every { voiceOutputPreferences.maxSpokenSentences } returns flowOf(0)
        every { voiceOutputPreferences.placeDidYouMeanEnabled } returns placeDidYouMeanOn
        every { voiceInputController.events } returns voiceInputEvents
        coEvery { voiceInputController.startListening(any()) } returns VoiceInputStartResult.Started(1L)
        every { voiceOutputController.events } returns voiceOutputEvents
        coEvery { voiceOutputController.warmUp() } returns VoiceOutputResult.Spoken

        // Only "Porirwa" is a near miss; everything else gets no question.
        every { suggester.suggest(any()) } returns null
        every { suggester.suggest("Porirwa") } returns "Porirua"
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv-existing")),
        speechStopCoordinator = SpeechStopCoordinator(mockk(relaxed = true)),
        chatPreferences = chatPreferences,
        voiceInputPreferences = voiceInputPreferences,
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
        quickIntentRouter = QuickIntentRouter(),
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
        placeDidYouMeanSuggester = suggester,
    )

    /** Sends [text] and lets the ViewModel's coroutine run to its next suspension (no virtual time). */
    private fun TestScope.send(vm: ChatViewModel, text: String) {
        vm.onInputChanged(text)
        vm.sendMessage()
        runCurrent()
    }

    /** Waits for the matcher's real-time work to deliver [expected] into the conversation, up to [timeoutMs]. */
    private fun TestScope.awaitConversation(vm: ChatViewModel, expected: String, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            runCurrent()
            if (vm.getConversationAsText().contains(expected)) return
            Thread.sleep(5)
        }
        fail<Unit>("no reply containing '$expected'; conversation: ${vm.getConversationAsText()}")
    }

    private fun destinations() = navigateRuns.map { it.arguments["destination"] }

    @Test
    fun `typed near miss asks, yes runs the suggested place`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        send(vm, "navigate to Porirwa")
        awaitConversation(vm, "Did you mean Porirua?")
        assertEquals(emptyList<Any?>(), destinations())

        send(vm, "yes")
        assertEquals(listOf<Any?>("Porirua"), destinations())
        verify(exactly = 0) { inferenceEngine.generate(any()) }
    }

    @Test
    fun `typed no runs the heard place`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        send(vm, "navigate to Porirwa")
        awaitConversation(vm, "Did you mean Porirua?")
        send(vm, "no")

        assertEquals(listOf<Any?>("Porirwa"), destinations())
    }

    @Test
    fun `typed reply other than yes or no drops the question`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        send(vm, "navigate to Porirwa")
        awaitConversation(vm, "Did you mean Porirua?")
        send(vm, "what is the time")
        // The question is gone: a later "yes" is not a confirmation of anything.
        send(vm, "yes")

        assertEquals(emptyList<Any?>(), destinations())
    }

    @Test
    fun `setting off never asks and runs the heard place`() = runTest(dispatcher) {
        placeDidYouMeanOn.value = false
        val vm = createViewModel()
        advanceUntilIdle()

        send(vm, "navigate to Porirwa")
        advanceUntilIdle()

        assertFalse(vm.getConversationAsText().contains("Did you mean"))
        assertEquals(listOf<Any?>("Porirwa"), destinations())
    }

    @Test
    fun `hung matcher gives the normal path, not silence`() = runTest(dispatcher) {
        // Blocks on a real thread until released; the real-time bound (PlaceDidYouMeanConfig.TIMEOUT_MS, 3 s) fires first.
        // Released in finally so no matcher thread outlives the test.
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        every { suggester.suggest("Porirwa") } answers {
            release.await(15, java.util.concurrent.TimeUnit.SECONDS)
            returned.countDown()
            "Porirua"
        }
        try {
            val vm = createViewModel()
            advanceUntilIdle()

            send(vm, "navigate to Porirwa")
            awaitConversation(vm, "Navigating.", timeoutMs = 10_000)

            assertEquals(listOf<Any?>("Porirwa"), destinations())
            assertFalse(vm.getConversationAsText().contains("Did you mean"))
        } finally {
            // Let the matcher thread finish before the test ends, so no coroutine resumes after resetMain.
            release.countDown()
            returned.await(15, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun `failing matcher gives the normal path, not silence`() = runTest(dispatcher) {
        every { suggester.suggest("Porirwa") } throws IllegalStateException("asset missing")
        val vm = createViewModel()
        advanceUntilIdle()

        send(vm, "navigate to Porirwa")
        awaitConversation(vm, "Navigating.")

        assertEquals(listOf<Any?>("Porirwa"), destinations())
    }

    @Test
    fun `one-shot voice place question reopens the mic for the yes reply`() = runTest(dispatcher) {
        // A relaxed mock returns a non-null pending request, which forces Command capture. Stub it null so the first start is a dictation.
        every { slotFillerManager.pendingRequestFor(any()) } returns null
        every { voiceOutputPreferences.autoSpeak } returns MutableStateFlow(true)
        every { voiceOutputPreferences.spokenResponsesEnabled } returns MutableStateFlow(true)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startVoiceInput()
        advanceUntilIdle()
        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Dictation, "navigate to Porirwa", captureSessionId = 1L))
        awaitConversation(vm, "Did you mean Porirua?")

        // The question is spoken; the one-shot session ends with playback and must reopen the reply mic.
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted("Did you mean Porirua?"))
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()

        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.Command) }
        assertEquals(ChatViewModel.VoiceCaptureState.Listening(), vm.voiceCaptureState.value)

        voiceInputEvents.emit(VoiceInputEvent.Transcript(VoiceCaptureMode.Command, "yes", captureSessionId = 1L))
        advanceUntilIdle()
        assertEquals(listOf<Any?>("Porirua"), destinations())
    }
}
