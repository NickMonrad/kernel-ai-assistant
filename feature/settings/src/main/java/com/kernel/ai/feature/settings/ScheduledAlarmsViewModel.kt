package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.clock.AlarmDraft
import com.kernel.ai.core.memory.clock.AlarmRepeatRule
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ScheduledAlarmsViewModel @Inject constructor(
    private val clockRepository: ClockRepository,
) : ViewModel() {

    val alarms: StateFlow<List<ClockAlarm>> =
        clockRepository.observeUpcomingAlarms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun tryScheduleAlarm(triggerAtMillis: Long, label: String?): Boolean =
        clockRepository.createAlarm(triggerAtMillis.toOneOffAlarmDraft(label)) != null

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
        clockRepository.updateAlarm(alarm.id, newTriggerAtMillis.toOneOffAlarmDraft(newLabel)) != null

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

    fun cancelAlarm(alarm: ClockAlarm) {
        viewModelScope.launch {
            clockRepository.cancelAlarm(alarm.id)
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