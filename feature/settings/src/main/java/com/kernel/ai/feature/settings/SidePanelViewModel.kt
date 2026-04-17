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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
