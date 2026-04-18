package com.kernel.ai.feature.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AlarmTimerFilter { ALL, ALARMS, TIMERS }

@HiltViewModel
class SidePanelViewModel @Inject constructor(
    private val dao: ScheduledAlarmDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** All unfired ALARM-type entries, ordered by trigger time. */
    val alarms: StateFlow<List<ScheduledAlarmEntity>> =
        dao.observeActiveAlarms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All unfired TIMER-type entries, ordered by start time. */
    val timers: StateFlow<List<ScheduledAlarmEntity>> =
        dao.observeActiveTimers()
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
        _selectedIds.value = allIds.toSet()
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

    /** Delete all selected items, cancelling AlarmManager broadcasts for alarm-type entries. */
    fun deleteSelected() {
        val ids = _selectedIds.value.toSet()
        val toDelete = (alarms.value + timers.value).filter { it.id in ids }
        viewModelScope.launch {
            try {
                toDelete.forEach { item ->
                    if (item.entryType == "ALARM") cancelAlarmBroadcast(item)
                    dao.delete(item.id)
                }
            } finally {
                _showBulkDeleteConfirmation.value = false
                _isInSelectionMode.value = false
                _selectedIds.value = emptySet()
            }
        }
    }

    /** Dismiss an alarm: cancel any pending AlarmManager broadcast and delete from DB. */
    fun dismissAlarm(alarm: ScheduledAlarmEntity) {
        viewModelScope.launch {
            cancelAlarmBroadcast(alarm)
            dao.delete(alarm.id)
        }
    }

    /** Cancel a running timer: delete from DB (timers don't use AlarmManager broadcasts). */
    fun cancelTimer(timer: ScheduledAlarmEntity) {
        viewModelScope.launch {
            dao.delete(timer.id)
        }
    }

    private fun cancelAlarmBroadcast(alarm: ScheduledAlarmEntity) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val broadcastIntent = Intent().apply {
            component = android.content.ComponentName(
                context.packageName,
                "com.kernel.ai.alarm.AlarmBroadcastReceiver",
            )
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            broadcastIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}
