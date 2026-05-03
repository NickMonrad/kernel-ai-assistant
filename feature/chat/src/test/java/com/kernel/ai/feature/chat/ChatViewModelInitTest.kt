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
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
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
        coEvery { inferenceEngine.resetConversation() } just runs

        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

        coEvery { conversationRepository.createConversation() } returns "conv-new"
        coEvery { conversationRepository.getConversation(any()) } answers {
            val id = firstArg<String>()
            ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L)
        }
        coEvery { conversationRepository.getMessagesOnce(any()) } returns emptyList()

        every { jandalPersona.personaMode } returns MutableStateFlow(PersonaMode.FULL)
        every { jandalPersona.currentPersonaMode } returns PersonaMode.FULL
        every { voiceOutputPreferences.spokenResponsesEnabled } returns MutableStateFlow(false)
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
    fun `fresh chat initialization resets inherited inference session`() = runTest(dispatcher) {
        ChatViewModel(
            savedStateHandle = SavedStateHandle(),
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

        advanceUntilIdle()

        coVerify(exactly = 1) { conversationRepository.createConversation() }
        coVerify(exactly = 1) { inferenceEngine.resetConversation() }
    }

    @Test
    fun `restored chat initialization does not reset current inference session`() = runTest(dispatcher) {
        ChatViewModel(
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

        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.createConversation() }
        coVerify(exactly = 0) { inferenceEngine.resetConversation() }
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

        advanceUntilIdle()
        viewModel.submitInitialQueryIfNeeded("and ice cream to my last")
        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.addMessage(any(), any(), any(), any(), any()) }
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
}
