package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.ImportantDateRepository
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import com.kernel.ai.core.skills.natives.CalendarBirthdayLookup
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ImportantDateSource {
    TAUGHT,
    CALENDAR,
}

data class ImportantDateListItem(
    val label: String,
    val normalizedLabel: String,
    val month: Int,
    val day: Int,
    val year: Int?,
    val source: ImportantDateSource,
    val nextOccurrence: LocalDate,
) {
    val stableKey: String = "${source.name}:$normalizedLabel"
    val isReadOnly: Boolean = source == ImportantDateSource.CALENDAR
}

data class ImportantDatesUiState(
    val upcomingDates: List<ImportantDateListItem> = emptyList(),
    val laterDates: List<ImportantDateListItem> = emptyList(),
    val calendarPermissionGranted: Boolean = false,
    val isRefreshingCalendarBirthdays: Boolean = false,
) {
    val hasAnyDates: Boolean = upcomingDates.isNotEmpty() || laterDates.isNotEmpty()
}

@HiltViewModel
class ImportantDatesViewModel @Inject constructor(
    private val repository: ImportantDateRepository,
    private val calendarBirthdayLookup: CalendarBirthdayLookup,
) : ViewModel() {
    private val calendarBirthdays = MutableStateFlow<List<ImportantDateListItem>>(emptyList())
    private val calendarPermissionGranted = MutableStateFlow(calendarBirthdayLookup.hasPermission())
    private val isRefreshingCalendarBirthdays = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    val messages = _messages.asSharedFlow()

    val uiState: StateFlow<ImportantDatesUiState> = combine(
        repository.observeAll().map { dates ->
            dates.map { it.toUiItem() }
        },
        calendarBirthdays,
        calendarPermissionGranted,
        isRefreshingCalendarBirthdays,
    ) { taughtDates, syncedBirthdays, hasCalendarPermission, isRefreshing ->
        buildUiState(
            taughtDates = taughtDates,
            calendarBirthdays = syncedBirthdays,
            hasCalendarPermission = hasCalendarPermission,
            isRefreshing = isRefreshing,
            today = LocalDate.now(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImportantDatesUiState(
            calendarPermissionGranted = calendarBirthdayLookup.hasPermission(),
        ),
    )

    init {
        refreshCalendarBirthdays()
    }

    fun refreshCalendarBirthdays() {
        viewModelScope.launch {
            isRefreshingCalendarBirthdays.value = true
            val granted = calendarBirthdayLookup.hasPermission()
            calendarPermissionGranted.value = granted
            calendarBirthdays.value = if (granted) {
                calendarBirthdayLookup.invalidateCache()
                calendarBirthdayLookup.getAllBirthdays().map { it.toUiItem() }
            } else {
                emptyList()
            }
            isRefreshingCalendarBirthdays.value = false
        }
    }

    suspend fun saveTaughtDate(
        existingLabel: String?,
        label: String,
        month: Int,
        day: Int,
        year: Int?,
    ): Boolean {
        val trimmedLabel = label.trim()
        if (trimmedLabel.isBlank()) {
            _messages.emit("Label is required.")
            return false
        }
        if (month !in 1..12 || day !in 1..31) {
            _messages.emit("Pick a valid month and day.")
            return false
        }

        val normalizedLabel = ImportantDateRepository.normalizeLabel(trimmedLabel)
        val originalNormalizedLabel = existingLabel?.let(ImportantDateRepository::normalizeLabel)
        val existing = repository.findByLabel(trimmedLabel)
        if (existing != null && existing.normalizedLabel != originalNormalizedLabel) {
            _messages.emit("An important date named $trimmedLabel already exists.")
            return false
        }

        if (existingLabel != null && originalNormalizedLabel != normalizedLabel) {
            repository.deleteByLabel(existingLabel)
        }
        repository.save(trimmedLabel, month, day, year)
        _messages.emit(if (existingLabel == null) "Saved $trimmedLabel." else "Updated $trimmedLabel.")
        return true
    }

    suspend fun deleteTaughtDate(label: String): Boolean {
        val deleted = repository.deleteByLabel(label)
        _messages.emit(
            if (deleted > 0) {
                "Removed $label."
            } else {
                "I couldn't find $label anymore."
            },
        )
        return deleted > 0
    }

    companion object {
        internal fun buildUiState(
            taughtDates: List<ImportantDateListItem>,
            calendarBirthdays: List<ImportantDateListItem>,
            hasCalendarPermission: Boolean,
            isRefreshing: Boolean,
            today: LocalDate,
        ): ImportantDatesUiState {
            val merged = (taughtDates + calendarBirthdays.filter { calendarBirthday ->
                taughtDates.none { taughtDate ->
                    taughtDate.normalizedLabel == calendarBirthday.normalizedLabel ||
                        CalendarBirthdayLookup.labelsPotentiallyMatch(taughtDate.label, calendarBirthday.label)
                }
            })
                .sortedWith(
                    compareBy<ImportantDateListItem> { it.nextOccurrence }
                        .thenBy { it.label.lowercase() },
                )
            val upcomingCutoff = today.plusDays(90)
            return ImportantDatesUiState(
                upcomingDates = merged.filter { !it.nextOccurrence.isAfter(upcomingCutoff) },
                laterDates = merged.filter { it.nextOccurrence.isAfter(upcomingCutoff) },
                calendarPermissionGranted = hasCalendarPermission,
                isRefreshingCalendarBirthdays = isRefreshing,
            )
        }

        internal fun ImportantDateEntity.toUiItem(today: LocalDate = LocalDate.now()): ImportantDateListItem =
            ImportantDateListItem(
                label = label,
                normalizedLabel = normalizedLabel,
                month = month,
                day = day,
                year = year,
                source = ImportantDateSource.TAUGHT,
                nextOccurrence = nextOccurrence(month, day, today),
            )

        internal fun CalendarBirthdayLookup.BirthdayEntry.toUiItem(today: LocalDate = LocalDate.now()): ImportantDateListItem =
            ImportantDateListItem(
                label = label,
                normalizedLabel = normalizedLabel,
                month = month,
                day = day,
                year = null,
                source = ImportantDateSource.CALENDAR,
                nextOccurrence = nextOccurrence(month, day, today),
            )

        internal fun nextOccurrence(month: Int, day: Int, today: LocalDate): LocalDate {
            val candidate = LocalDate.of(today.year, month, day)
            return if (!candidate.isBefore(today)) candidate else LocalDate.of(today.year + 1, month, day)
        }
    }
}
