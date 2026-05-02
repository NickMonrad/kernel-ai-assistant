package com.kernel.ai.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ScheduledAlarmsViewModel @Inject constructor(
    private val clockRepository: ClockRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val alarms: StateFlow<List<ClockAlarm>> =
        clockRepository.observeUpcomingAlarms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun cancelAlarm(alarm: ClockAlarm) {
        viewModelScope.launch {
            clockRepository.cancelAlarm(alarm.id)
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