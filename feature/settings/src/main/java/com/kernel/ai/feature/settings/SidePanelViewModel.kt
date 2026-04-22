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
import java.util.UUID
import javax.inject.Inject

private const val ALARM_RECEIVER_CLASS = "com.kernel.ai.alarm.AlarmBroadcastReceiver"
private const val EXTRA_ALARM_LABEL = "alarm_label"
private const val EXTRA_ALARM_ID = "alarm_id"
private const val EXTRA_ALARM_TITLE = "alarm_title"

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

    /** Delete all selected items, cancelling AlarmManager broadcasts for alarm/timer entries. */
    fun deleteSelected() {
        val ids = _selectedIds.value
        val toDelete = (alarms.value + timers.value).filter { it.id in ids }
        viewModelScope.launch {
            try {
                toDelete.forEach { item ->
                    if (item.entryType == "ALARM") cancelAlarmBroadcast(item) else cancelTimerBroadcast(item)
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

    /** Cancel a running timer: cancel its pending broadcast and delete from DB. */
    fun cancelTimer(timer: ScheduledAlarmEntity) {
        viewModelScope.launch {
            cancelTimerBroadcast(timer)
            dao.delete(timer.id)
        }
    }

    /** Schedule a new alarm and persist it. */
    fun scheduleAlarm(triggerAtMillis: Long, label: String?) {
        viewModelScope.launch {
            val alarmId = UUID.randomUUID().toString()
            val entity = ScheduledAlarmEntity(
                id = alarmId,
                triggerAtMillis = triggerAtMillis,
                label = label?.takeIf { it.isNotBlank() },
                createdAt = System.currentTimeMillis(),
                enabled = true,
            )
            dao.insert(entity)
            scheduleAlarmBroadcast(entity)
        }
    }

    /** Edit an existing alarm's time and label, rescheduling the broadcast. */
    fun editAlarm(alarm: ScheduledAlarmEntity, newTriggerAtMillis: Long, newLabel: String?) {
        viewModelScope.launch {
            cancelAlarmBroadcast(alarm)
            val updated = alarm.copy(
                triggerAtMillis = newTriggerAtMillis,
                label = newLabel?.takeIf { it.isNotBlank() },
            )
            dao.insert(updated)
            if (updated.enabled) scheduleAlarmBroadcast(updated)
        }
    }

    /** Toggle an alarm enabled/disabled, cancelling or rescheduling its broadcast accordingly. */
    fun toggleEnabled(alarm: ScheduledAlarmEntity) {
        viewModelScope.launch {
            val newEnabled = !alarm.enabled
            dao.setEnabled(alarm.id, newEnabled)
            if (newEnabled) {
                scheduleAlarmBroadcast(alarm.copy(enabled = true))
            } else {
                cancelAlarmBroadcast(alarm)
            }
        }
    }

    /** Create a new built-in timer — persists to DB and schedules a local broadcast. */
    fun scheduleTimer(durationMs: Long, label: String?) {
        viewModelScope.launch {
            val timerId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = ScheduledAlarmEntity(
                id = timerId,
                triggerAtMillis = now + durationMs,
                label = label?.takeIf { it.isNotBlank() },
                createdAt = now,
                entryType = "TIMER",
                durationMs = durationMs,
                startedAtMs = now,
            )
            dao.insert(entity)
            scheduleTimerBroadcast(entity)
        }
    }

    private fun scheduleAlarmBroadcast(alarm: ScheduledAlarmEntity) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val broadcastIntent = Intent().apply {
            component = android.content.ComponentName(
                context.packageName,
                "com.kernel.ai.alarm.AlarmBroadcastReceiver",
            )
            putExtra("alarm_label", alarm.label ?: "Alarm")
            putExtra("alarm_id", alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pendingIntent)
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

    private fun scheduleTimerBroadcast(timer: ScheduledAlarmEntity) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val broadcastIntent = Intent().apply {
            component = android.content.ComponentName(
                context.packageName,
                ALARM_RECEIVER_CLASS,
            )
            putExtra(EXTRA_ALARM_LABEL, timer.label ?: "Timer")
            putExtra(EXTRA_ALARM_ID, timer.id)
            putExtra(EXTRA_ALARM_TITLE, "Timer")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            timer.id.hashCode(),
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.triggerAtMillis, pendingIntent)
    }

    private fun cancelTimerBroadcast(timer: ScheduledAlarmEntity) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val broadcastIntent = Intent().apply {
            component = android.content.ComponentName(
                context.packageName,
                ALARM_RECEIVER_CLASS,
            )
            putExtra(EXTRA_ALARM_LABEL, timer.label ?: "Timer")
            putExtra(EXTRA_ALARM_ID, timer.id)
            putExtra(EXTRA_ALARM_TITLE, "Timer")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            timer.id.hashCode(),
            broadcastIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}
