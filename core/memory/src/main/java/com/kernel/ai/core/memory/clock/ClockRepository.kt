package com.kernel.ai.core.memory.clock

import kotlinx.coroutines.flow.Flow

interface ClockRepository {
    fun observeManageableAlarms(): Flow<List<ClockAlarm>>
    fun observeActiveAlarms(): Flow<List<ClockAlarm>>
    fun observeUpcomingAlarms(): Flow<List<ClockAlarm>>
    fun observeActiveTimers(): Flow<List<ClockTimer>>
    fun observeRecentCompletedTimers(): Flow<List<ClockTimer>>

    fun getPlatformState(): ClockPlatformState

    suspend fun createAlarm(draft: AlarmDraft): ClockAlarm?
    suspend fun updateAlarm(alarmId: String, draft: AlarmDraft): ClockAlarm?
    suspend fun setAlarmEnabled(alarmId: String, enabled: Boolean): Boolean
    suspend fun cancelAlarm(alarmId: String)
    suspend fun cancelAlarms(alarmIds: Collection<String>)
    suspend fun cancelNextAlarm(): ClockAlarm?
    suspend fun cancelAlarmsByLabel(label: String): Int
    suspend fun skipAlarmOccurrence(alarmId: String, occurrenceTriggerAtMillis: Long): Boolean

    suspend fun scheduleTimer(durationMs: Long, label: String?): ClockTimer?
    suspend fun cancelTimer(timerId: String)
    suspend fun deleteCompletedTimer(timerId: String): Boolean
    suspend fun clearCompletedTimers(): Int
    suspend fun handleScheduledEvent(
        ownerId: String,
        type: ClockEventType,
        occurrenceTriggerAtMillis: Long? = null,
    )
    suspend fun cancelTimers(timerIds: Collection<String>)
    suspend fun cancelAllTimers(): Int
    suspend fun cancelTimersMatching(name: String?, durationMs: Long?): Int
    suspend fun getAllTimers(): List<ClockTimer>
    suspend fun restoreScheduledEntries(nowMillis: Long = System.currentTimeMillis()): ClockRestoreReport
}