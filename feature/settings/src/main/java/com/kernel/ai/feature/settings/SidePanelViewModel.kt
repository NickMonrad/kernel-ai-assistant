package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.clock.AlarmDraft
import com.kernel.ai.core.memory.clock.AlarmRepeatRule
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockTimer
import com.kernel.ai.core.memory.clock.WorldClock
import com.kernel.ai.core.memory.clock.WorldClockCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
enum class ClockSurfaceTab { TIMERS, ALARMS, WORLD_CLOCK }

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

    val recentCompletedTimers: StateFlow<List<ClockTimer>> =
        clockRepository.observeRecentCompletedTimers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val worldClocks: StateFlow<List<WorldClock>> =
        clockRepository.observeWorldClocks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedTab = MutableStateFlow(ClockSurfaceTab.TIMERS)
    val selectedTab: StateFlow<ClockSurfaceTab> = _selectedTab.asStateFlow()
    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow(emptySet<String>())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _showBulkDeleteConfirmation = MutableStateFlow(false)
    val showBulkDeleteConfirmation: StateFlow<Boolean> = _showBulkDeleteConfirmation.asStateFlow()

    fun setTab(tab: ClockSurfaceTab) {
        _selectedTab.value = tab
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

    fun restartTimer(timer: ClockTimer, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(tryScheduleTimer(timer.durationMs, timer.label))
        }
    }

    fun deleteCompletedTimer(timer: ClockTimer) {
        viewModelScope.launch {
            clockRepository.deleteCompletedTimer(timer.id)
        }
    }

    fun clearCompletedTimers(onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            onResult(clockRepository.clearCompletedTimers())
        }
    }

    suspend fun tryScheduleAlarm(draft: AlarmDraft): Boolean =
        clockRepository.createAlarm(draft) != null

    suspend fun tryScheduleAlarm(triggerAtMillis: Long, label: String?): Boolean =
        tryScheduleAlarm(triggerAtMillis.toOneOffAlarmDraft(label))

    fun scheduleAlarm(draft: AlarmDraft, onResult: (AlarmSaveResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = if (tryScheduleAlarm(draft)) {
                AlarmSaveResult.STORED
            } else {
                AlarmSaveResult.FAILED
            }
            onResult(result)
        }
    }

    fun scheduleAlarm(triggerAtMillis: Long, label: String?, onResult: (AlarmSaveResult) -> Unit = {}) {
        scheduleAlarm(triggerAtMillis.toOneOffAlarmDraft(label), onResult)
    }

    suspend fun tryEditAlarm(alarm: ClockAlarm, draft: AlarmDraft): Boolean =
        clockRepository.updateAlarm(alarm.id, draft) != null

    suspend fun tryEditAlarm(alarm: ClockAlarm, newTriggerAtMillis: Long, newLabel: String?): Boolean =
        tryEditAlarm(alarm, newTriggerAtMillis.toOneOffAlarmDraft(newLabel))

    fun editAlarm(
        alarm: ClockAlarm,
        draft: AlarmDraft,
        onResult: (AlarmSaveResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = if (tryEditAlarm(alarm, draft)) {
                AlarmSaveResult.STORED
            } else {
                AlarmSaveResult.FAILED
            }
            onResult(result)
        }
    }

    fun editAlarm(
        alarm: ClockAlarm,
        newTriggerAtMillis: Long,
        newLabel: String?,
        onResult: (AlarmSaveResult) -> Unit = {},
    ) {
        editAlarm(alarm, newTriggerAtMillis.toOneOffAlarmDraft(newLabel), onResult)
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

    fun addWorldClock(candidate: WorldClockCandidate, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(clockRepository.addWorldClock(candidate.zoneId, candidate.displayName) != null)
        }
    }

    fun removeWorldClock(worldClock: WorldClock, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(clockRepository.removeWorldClock(worldClock.id))
        }
    }

    fun moveWorldClock(worldClock: WorldClock, direction: Int, onResult: (Boolean) -> Unit = {}) {
        val clocks = worldClocks.value
        val currentIndex = clocks.indexOfFirst { it.id == worldClock.id }
        if (currentIndex == -1) {
            onResult(false)
            return
        }
        val targetIndex = currentIndex + direction
        if (targetIndex !in clocks.indices) {
            onResult(false)
            return
        }
        val reordered = clocks.map { it.id }.toMutableList()
        val moved = reordered.removeAt(currentIndex)
        reordered.add(targetIndex, moved)
        viewModelScope.launch {
            onResult(clockRepository.reorderWorldClocks(reordered))
        }
    }
}

private fun Long.toOneOffAlarmDraft(label: String?): AlarmDraft {
    val zone = ZoneId.systemDefault()
    val trigger = Instant.ofEpochMilli(this).atZone(zone)
    return AlarmDraft(
        label = label,
        hour = trigger.hour,
        minute = trigger.minute,
        repeatRule = AlarmRepeatRule.OneOff(trigger.toLocalDate().toEpochDay()),
        timeZoneId = zone.id,
    )
}
