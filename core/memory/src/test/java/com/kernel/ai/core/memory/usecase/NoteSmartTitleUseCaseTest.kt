package com.kernel.ai.core.memory.usecase

import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.entity.NoteEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class NoteSmartTitleUseCaseTest {
    private val noteDao = mockk<NoteDao>()
    private val inferenceEngine = mockk<InferenceEngine>()

    @Test
    fun `schedule waits for inference readiness before generating and saving title`() = runBlocking {
        val ready = MutableStateFlow(false)
        val note = NoteEntity(id = NOTE_ID, title = null, content = NOTE_CONTENT)
        every { inferenceEngine.isReady } returns ready
        coEvery { noteDao.getNoteById(NOTE_ID) } returns note
        coEvery { inferenceEngine.generateOnce(any(), any(), any(), any()) } returns GENERATED_TITLE
        coEvery { noteDao.updateNoteTitleConditionally(NOTE_ID, GENERATED_TITLE) } returns 1

        NoteSmartTitleUseCase(noteDao, inferenceEngine).schedule(NOTE_ID)

        verify(timeout = VERIFY_TIMEOUT_MS) { inferenceEngine.isReady }
        coVerify(exactly = 0) { inferenceEngine.generateOnce(any(), any(), any(), any()) }
        coVerify(exactly = 0) { noteDao.updateNoteTitleConditionally(any(), any()) }

        ready.value = true

        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) {
            inferenceEngine.generateOnce(match { it.contains(NOTE_CONTENT) }, any(), false, false)
        }
        coVerify(timeout = VERIFY_TIMEOUT_MS, exactly = 1) {
            noteDao.updateNoteTitleConditionally(NOTE_ID, GENERATED_TITLE)
        }
        coVerify(exactly = 0) { noteDao.insertNote(any()) }
    }

    private companion object {
        const val NOTE_ID = 42L
        const val NOTE_CONTENT = "Notes about the garden renovation"
        const val GENERATED_TITLE = "Garden Renovation Notes"
        const val VERIFY_TIMEOUT_MS = 5_000L
    }
}
