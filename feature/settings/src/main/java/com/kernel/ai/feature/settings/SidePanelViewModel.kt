package com.kernel.ai.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AlarmTimerFilter { ALL, ALARMS, TIMERS }

@HiltViewModel
class SidePanelViewModel @Inject constructor(
    private val clockRepository: ClockRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val alarms: StateFlow<List<ClockAlarm>> =
        clockRepository.observeManageableAlarms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val timers: StateFlow<List<ClockTimer>> =
        clockRepository.observeActiveTimers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filterType = MutableStateFlow(AlarmTimerFilter.ALL)
    val filterType: StateFlow<AlarmTimerFilter> = _filterType.asStateFlow()

    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow(emptySet<String>())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _showBulkDeleteConfirmation = MutableStateFlow(false)
    val showBulkDeleteConfirmation: StateFlow<Boolean> = _showBulkDeleteConfirmation.asStateFlow()

    fun setFilter(filter: AlarmTimerFilter) {
        _filterType.value = filter
        clearSelection()
    }

    fun enterSelectionMode(id: String) {
        _isInSelectionMode.value = true
        _selectedIds.update { it + id }
    }

    fun toggleSelection(id: String) {
        _selectedIds.update { current ->
            if (id in current) current - id else current + id
        }
        if (_selectedIds.value.isEmpty()) {
            _isInSelectionMode.value = false
        }
    }

    fun selectAll(allIds: List<String>) {
        if (allIds.isNotEmpty()) {
            _isInSelectionMode.value = true
            _selectedIds.value = allIds.toSet()
        }
    }

    fun clearSelection() {
        _isInSelectionMode.value = false
        _selectedIds.value = emptySet()
        _showBulkDeleteConfirmation.value = false
    }

    fun requestBulkDelete() {
        if (_selectedIds.value.isNotEmpty()) {
            _showBulkDeleteConfirmation.value = true
        }
    }

    fun dismissBulkDeleteConfirmation() {
        _showBulkDeleteConfirmation.value = false
    }

    fun deleteSelected() {
        val ids = _selectedIds.value
        val alarmIds = alarms.value.filter { it.id in ids }.map { it.id }
        val timerIds = timers.value.filter { it.id in ids }.map { it.id }
        viewModelScope.launch {
            try {
                clockRepository.cancelAlarms(alarmIds)
                clockRepository.cancelTimers(timerIds)
            } finally {
                _showBulkDeleteConfirmation.value = false
                _isInSelectionMode.value = false
                _selectedIds.value = emptySet()
            }
        }
    }

    fun dismissAlarm(alarm: ClockAlarm) {
        viewModelScope.launch {
            clockRepository.cancelAlarm(alarm.id)
        }
    }

    fun cancelTimer(timer: ClockTimer) {
        viewModelScope.launch {
            clockRepository.cancelTimer(timer.id)
        }
    }

    suspend fun tryScheduleAlarm(triggerAtMillis: Long, label: String?): Boolean =
        clockRepository.scheduleAlarm(triggerAtMillis, label) != null

    fun scheduleAlarm(triggerAtMillis: Long, label: String?, onResult: (AlarmSaveResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = when {
                tryScheduleAlarm(triggerAtMillis, label) -> AlarmSaveResult.STORED
                !clockRepository.getPlatformState().canScheduleExactAlarms &&
                    openClockAppAlarm(triggerAtMillis, label) -> AlarmSaveResult.CLOCK_APP_FALLBACK
                else -> AlarmSaveResult.FAILED
            }
            onResult(result)
        }
    }

    suspend fun tryEditAlarm(alarm: ClockAlarm, newTriggerAtMillis: Long, newLabel: String?): Boolean =
        clockRepository.editAlarm(alarm.id, newTriggerAtMillis, newLabel) != null

    fun editAlarm(
        alarm: ClockAlarm,
        newTriggerAtMillis: Long,
        newLabel: String?,
        onResult: (AlarmSaveResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = when {
                tryEditAlarm(alarm, newTriggerAtMillis, newLabel) -> AlarmSaveResult.STORED
                !clockRepository.getPlatformState().canScheduleExactAlarms &&
                    openClockAppAlarm(newTriggerAtMillis, newLabel) -> AlarmSaveResult.CLOCK_APP_FALLBACK
                else -> AlarmSaveResult.FAILED
            }
            onResult(result)
        }
    }

    suspend fun tryToggleEnabled(alarm: ClockAlarm): Boolean =
        clockRepository.setAlarmEnabled(alarm.id, !alarm.enabled)

    fun toggleEnabled(alarm: ClockAlarm, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(tryToggleEnabled(alarm))
        }
    }

    suspend fun tryScheduleTimer(durationMs: Long, label: String?): Boolean =
        clockRepository.scheduleTimer(durationMs, label) != null

    fun scheduleTimer(durationMs: Long, label: String?, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(tryScheduleTimer(durationMs, label))
        }
    }

    private fun openClockAppAlarm(triggerAtMillis: Long, label: String?): Boolean {
        val scheduledTime = Instant.ofEpochMilli(triggerAtMillis).atZone(ZoneId.systemDefault())
        val datePrefix = scheduledTime.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM"))
        val message = label?.let { "$datePrefix: $it" } ?: datePrefix
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, scheduledTime.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, scheduledTime.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
