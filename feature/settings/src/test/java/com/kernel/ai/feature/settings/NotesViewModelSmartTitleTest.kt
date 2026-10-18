package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.usecase.NoteSmartTitleUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class NotesViewModelSmartTitleTest {
    private val dispatcher = StandardTestDispatcher()
    private val noteDao = mockk<NoteDao>()
    private val noteSmartTitleUseCase = mockk<NoteSmartTitleUseCase>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { noteDao.observeAllActiveNotes() } returns flowOf(emptyList())
        every { noteDao.observeArchivedNotes() } returns flowOf(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `untitled note created in standalone Notes view schedules title generation`() = runTest {
        coEvery { noteDao.getMaxActiveDisplayOrder() } returns 0.0
        coEvery { noteDao.insertNote(any()) } returns NOTE_ID

        val viewModel = NotesViewModel(noteDao, noteSmartTitleUseCase)
        viewModel.createNote(title = null, content = "  $NOTE_CONTENT  ")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            noteDao.insertNote(match { it.title == null && it.content == NOTE_CONTENT })
        }
        verify(exactly = 1) { noteSmartTitleUseCase.schedule(NOTE_ID) }
    }

    private companion object {
        const val NOTE_ID = 1638L
        const val NOTE_CONTENT = "Standalone Notes cold-start content"
    }
}
