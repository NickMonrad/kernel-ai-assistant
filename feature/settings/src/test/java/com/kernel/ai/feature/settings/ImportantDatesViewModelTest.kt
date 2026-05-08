package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.ImportantDateRepository
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import com.kernel.ai.core.skills.natives.CalendarBirthdayLookup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ImportantDatesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository: ImportantDateRepository = mockk()
    private val calendarBirthdayLookup: CalendarBirthdayLookup = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeAll() } returns flowOf(emptyList())
        every { calendarBirthdayLookup.hasPermission() } returns false
        every { calendarBirthdayLookup.getAllBirthdays() } returns emptyList()
        every { calendarBirthdayLookup.invalidateCache() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refreshCalendarBirthdays loads read only birthdays when permission is granted`() = runTest {
        every { calendarBirthdayLookup.hasPermission() } returns true
        every { calendarBirthdayLookup.getAllBirthdays() } returns listOf(
            CalendarBirthdayLookup.BirthdayEntry(
                label = "Jane Smith",
                normalizedLabel = "jane smith",
                month = 4,
                day = 5,
            ),
        )

        val viewModel = ImportantDatesViewModel(repository, calendarBirthdayLookup)
        val collectionJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.calendarPermissionGranted)
        val allDates = viewModel.uiState.value.upcomingDates + viewModel.uiState.value.laterDates
        assertEquals(listOf("Jane Smith"), allDates.map { it.label })
        assertTrue(allDates.all { it.isReadOnly })
        collectionJob.cancel()
    }

    @Test
    fun `buildUiState hides calendar duplicates behind taught dates and groups later dates`() {
        val today = LocalDate.of(2026, 1, 10)
        val taught = listOf(
            ImportantDateListItem(
                label = "Mum's birthday",
                normalizedLabel = "mum birthday",
                month = 1,
                day = 12,
                year = null,
                source = ImportantDateSource.TAUGHT,
                nextOccurrence = LocalDate.of(2026, 1, 12),
            ),
        )
        val synced = listOf(
            ImportantDateListItem(
                label = "Mum",
                normalizedLabel = "mum birthday",
                month = 1,
                day = 12,
                year = null,
                source = ImportantDateSource.CALENDAR,
                nextOccurrence = LocalDate.of(2026, 1, 12),
            ),
            ImportantDateListItem(
                label = "Dad",
                normalizedLabel = "dad",
                month = 2,
                day = 1,
                year = null,
                source = ImportantDateSource.CALENDAR,
                nextOccurrence = LocalDate.of(2026, 2, 1),
            ),
            ImportantDateListItem(
                label = "Our anniversary",
                normalizedLabel = "our anniversary",
                month = 7,
                day = 1,
                year = 2018,
                source = ImportantDateSource.TAUGHT,
                nextOccurrence = LocalDate.of(2026, 7, 1),
            ),
        )

        val state = ImportantDatesViewModel.buildUiState(
            taughtDates = taught,
            calendarBirthdays = synced,
            hasCalendarPermission = true,
            isRefreshing = false,
            today = today,
        )

        assertEquals(listOf("Mum's birthday", "Dad"), state.upcomingDates.map { it.label })
        assertEquals(listOf("Our anniversary"), state.laterDates.map { it.label })
    }

    @Test
    fun `buildUiState hides calendar birthdays when taught label differs only by missing apostrophe`() {
        val today = LocalDate.of(2026, 1, 10)
        val taught = listOf(
            ImportantDateListItem(
                label = "Lachlans birthday",
                normalizedLabel = "lachlans birthday",
                month = 8,
                day = 22,
                year = null,
                source = ImportantDateSource.TAUGHT,
                nextOccurrence = LocalDate.of(2026, 8, 22),
            ),
        )
        val synced = listOf(
            ImportantDateListItem(
                label = "Lachlan",
                normalizedLabel = "lachlan",
                month = 8,
                day = 22,
                year = null,
                source = ImportantDateSource.CALENDAR,
                nextOccurrence = LocalDate.of(2026, 8, 22),
            ),
        )

        val state = ImportantDatesViewModel.buildUiState(
            taughtDates = taught,
            calendarBirthdays = synced,
            hasCalendarPermission = true,
            isRefreshing = false,
            today = today,
        )

        assertEquals(listOf("Lachlans birthday"), state.laterDates.map { it.label })
    }

    @Test
    fun `saveTaughtDate renames existing entry without keeping the old label`() = runTest {
        val viewModel = ImportantDatesViewModel(repository, calendarBirthdayLookup)
        advanceUntilIdle()
        coEvery { repository.findByLabel("Parents anniversary") } returns null
        coEvery { repository.deleteByLabel("Our anniversary") } returns 1
        coEvery { repository.save("Parents anniversary", 6, 22, 2018) } returns Unit

        val saved = viewModel.saveTaughtDate(
            existingLabel = "Our anniversary",
            label = "Parents anniversary",
            month = 6,
            day = 22,
            year = 2018,
        )

        assertTrue(saved)
        coVerify(exactly = 1) { repository.deleteByLabel("Our anniversary") }
        coVerify(exactly = 1) { repository.save("Parents anniversary", 6, 22, 2018) }
    }

    @Test
    fun `saveTaughtDate rejects conflicting rename`() = runTest {
        val viewModel = ImportantDatesViewModel(repository, calendarBirthdayLookup)
        advanceUntilIdle()
        coEvery { repository.findByLabel("Mum's birthday") } returns ImportantDateEntity(
            label = "Mum's birthday",
            normalizedLabel = "mum birthday",
            month = 3,
            day = 15,
        )

        val saved = viewModel.saveTaughtDate(
            existingLabel = "Dad's birthday",
            label = "Mum's birthday",
            month = 3,
            day = 15,
            year = null,
        )

        assertFalse(saved)
        coVerify(exactly = 0) { repository.save(any(), any(), any(), any()) }
    }
}
