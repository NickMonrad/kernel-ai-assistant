package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.EmbeddingEngine
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
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
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
            jandalPersona = jandalPersona,
            nzTruthSeedingService = nzTruthSeedingService,
            verboseLoggingPreferenceUseCase = verboseLoggingPreferenceUseCase,
        )

        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.createConversation() }
        coVerify(exactly = 0) { inferenceEngine.resetConversation() }
    }
}
