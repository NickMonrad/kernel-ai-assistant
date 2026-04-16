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

    fun cancelAlarm(alarm: ScheduledAlarmEntity) {
        viewModelScope.launch {
            // Cancel the pending AlarmManager broadcast.
            // Intent must include the same extras used when the alarm was scheduled
            // so that PendingIntent.filterEquals() finds a match.
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

            // Remove from DB
            dao.delete(alarm.id)
        }
    }
}
