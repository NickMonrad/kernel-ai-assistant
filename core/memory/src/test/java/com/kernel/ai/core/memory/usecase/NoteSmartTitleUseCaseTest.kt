package com.kernel.ai.core.memory.usecase

import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.ModelConfig
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.entity.NoteEntity
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteSmartTitleUseCaseTest {
    private val noteDao = mockk<NoteDao>()
    private val inferenceEngine = mockk<InferenceEngine>()
    private val modelDownloadManager = mockk<ModelDownloadManager>()
    private val modelSettingsRepository = mockk<ModelSettingsRepository>()
    private val toolProvider = mockk<ToolProvider>()
    private val settings = ModelSettingsEntity(
        modelId = MODEL.modelId,
        contextWindowSize = 3072,
        temperature = 0.7f,
        topP = 0.8f,
        topK = 32,
        showThinkingProcess = false,
        speculativeDecodingEnabled = false,
    )

    @Test
    fun `cold standalone Notes startup initializes model and waits for delayed readiness`() = runBlocking {
        val ready = MutableStateFlow(false)
        val note = NoteEntity(id = NOTE_ID, title = null, content = NOTE_CONTENT)
        stubModelStartup(ready, initializationDelayMs = INITIALIZATION_DELAY_MS)
        coEvery { noteDao.getNoteById(NOTE_ID) } returns note
        coEvery { inferenceEngine.generateOnce(any(), any(), any(), any()) } returns GENERATED_TITLE
        coEvery { noteDao.updateNoteTitleConditionally(NOTE_ID, GENERATED_TITLE) } returns 1

        NoteSmartTitleUseCase(
            noteDao,
            inferenceEngine,
            modelDownloadManager,
            modelSettingsRepository,
            toolProvider,
        ).schedule(NOTE_ID)

        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) {
            inferenceEngine.initialize(match<ModelConfig> {
                it.modelPath == MODEL_PATH &&
                    it.maxTokens == settings.contextWindowSize &&
                    it.temperature == settings.temperature &&
                    it.topP == settings.topP &&
                    it.topK == settings.topK &&
                    !it.thinkingEnabled &&
                    !it.speculativeDecodingEnabled &&
                    it.toolProvider === toolProvider
            })
        }
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) {
            inferenceEngine.generateOnce(match { it.contains(NOTE_CONTENT) }, any(), false, false)
        }
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) {
            noteDao.updateNoteTitleConditionally(NOTE_ID, GENERATED_TITLE)
        }
        assertTrue(ready.value)
        coVerify(exactly = 0) { noteDao.insertNote(any()) }
    }

    @Test
    fun `concurrent untitled notes share one model initialization`() = runBlocking {
        val ready = MutableStateFlow(false)
        val noteIds = listOf(NOTE_ID, OTHER_NOTE_ID)
        val notes = noteIds.associateWith { id ->
            NoteEntity(id = id, title = null, content = "$NOTE_CONTENT $id")
        }
        stubModelStartup(ready, initializationDelayMs = INITIALIZATION_DELAY_MS)
        val requestedNoteId = slot<Long>()
        coEvery { noteDao.getNoteById(capture(requestedNoteId)) } answers { notes[requestedNoteId.captured] }
        coEvery { inferenceEngine.generateOnce(any(), any(), any(), any()) } returns GENERATED_TITLE
        coEvery { noteDao.updateNoteTitleConditionally(any(), GENERATED_TITLE) } returns 1

        val useCase = NoteSmartTitleUseCase(
            noteDao,
            inferenceEngine,
            modelDownloadManager,
            modelSettingsRepository,
            toolProvider,
        )
        noteIds.forEach(useCase::schedule)

        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) { inferenceEngine.initialize(any()) }
        noteIds.forEach { noteId ->
            coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) {
                noteDao.updateNoteTitleConditionally(noteId, GENERATED_TITLE)
            }
        }
    }

    @Test
    fun `explicitly titled note does not initialize model or replace manual title`() = runBlocking {
        val ready = MutableStateFlow(false)
        every { inferenceEngine.isReady } returns ready
        coEvery { noteDao.getNoteById(NOTE_ID) } returns
            NoteEntity(id = NOTE_ID, title = "My manual title", content = NOTE_CONTENT)

        NoteSmartTitleUseCase(
            noteDao,
            inferenceEngine,
            modelDownloadManager,
            modelSettingsRepository,
            toolProvider,
        ).schedule(NOTE_ID)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { noteDao.getNoteById(NOTE_ID) }
        coVerify(exactly = 0) { modelDownloadManager.preferredConversationModel() }
        coVerify(exactly = 0) { inferenceEngine.initialize(any()) }
        coVerify(exactly = 0) { inferenceEngine.generateOnce(any(), any(), any(), any()) }
        coVerify(exactly = 0) { noteDao.updateNoteTitleConditionally(any(), any()) }
    }

    @Test
    fun `initialization failure retains note without generation or title update`() = runBlocking {
        val ready = MutableStateFlow(false)
        val note = NoteEntity(id = NOTE_ID, title = null, content = NOTE_CONTENT)
        stubModelStartup(ready)
        coEvery { noteDao.getNoteById(NOTE_ID) } returns note
        coEvery { inferenceEngine.initialize(any()) } throws IllegalStateException("startup failed")

        NoteSmartTitleUseCase(
            noteDao,
            inferenceEngine,
            modelDownloadManager,
            modelSettingsRepository,
            toolProvider,
        ).schedule(NOTE_ID)

        coVerify(timeout = VERIFY_TIMEOUT_MS) { inferenceEngine.initialize(any()) }
        coVerify(exactly = 0) { inferenceEngine.generateOnce(any(), any(), any(), any()) }
        coVerify(exactly = 0) { noteDao.updateNoteTitleConditionally(any(), any()) }
        assertFalse(ready.value)
    }

    @Test
    fun `readiness wait is bounded when initialization never becomes ready`() = runBlocking {
        val ready = MutableStateFlow(false)
        var initializationAttempted = false

        val becameReady = awaitInferenceEngineReady(ready, timeoutMs = 50L) {
            initializationAttempted = true
        }

        assertTrue(initializationAttempted)
        assertFalse(becameReady)
    }

    private fun stubModelStartup(
        ready: MutableStateFlow<Boolean>,
        initializationDelayMs: Long = 0L,
    ) {
        every { inferenceEngine.isReady } returns ready
        coEvery { modelDownloadManager.preferredConversationModel() } returns MODEL
        every { modelDownloadManager.getModelPath(MODEL) } returns MODEL_PATH
        coEvery { modelSettingsRepository.getSettings(MODEL.modelId) } returns settings
        coEvery { inferenceEngine.initialize(any()) } coAnswers {
            if (initializationDelayMs > 0) delay(initializationDelayMs)
            ready.value = true
        }
    }

    private companion object {
        val MODEL = KernelModel.GEMMA_4_E2B
        const val MODEL_PATH = "/models/gemma-4-e2b.litertlm"
        const val NOTE_ID = 42L
        const val OTHER_NOTE_ID = 43L
        const val NOTE_CONTENT = "Notes about the garden renovation"
        const val GENERATED_TITLE = "Garden Renovation Notes"
        const val INITIALIZATION_DELAY_MS = 100L
        const val VERIFY_TIMEOUT_MS = 5_000L
    }
}
