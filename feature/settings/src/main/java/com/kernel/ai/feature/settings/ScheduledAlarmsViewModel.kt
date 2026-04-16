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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ScheduledAlarmsViewModel @Inject constructor(
    private val dao: ScheduledAlarmDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Filter by current time on every emission so past-due alarms disappear immediately
    val alarms: StateFlow<List<ScheduledAlarmEntity>> =
        dao.observeAllUnfired()
            .map { list -> list.filter { it.triggerAtMillis > System.currentTimeMillis() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun cancelAlarm(alarm: ScheduledAlarmEntity) {
        viewModelScope.launch {
            cancelAlarmBroadcast(alarm)
            dao.delete(alarm.id)
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
            putExtra("alarm_label", alarm.label ?: "Alarm")
            putExtra("alarm_id", alarm.id)
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

