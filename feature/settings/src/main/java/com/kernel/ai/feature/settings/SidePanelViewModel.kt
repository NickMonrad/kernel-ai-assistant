package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockTimer
import dagger.hilt.android.lifecycle.HiltViewModel
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
            val result = if (tryScheduleAlarm(triggerAtMillis, label)) {
                AlarmSaveResult.STORED
            } else {
                AlarmSaveResult.FAILED
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
            val result = if (tryEditAlarm(alarm, newTriggerAtMillis, newLabel)) {
                AlarmSaveResult.STORED
            } else {
                AlarmSaveResult.FAILED
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
}
