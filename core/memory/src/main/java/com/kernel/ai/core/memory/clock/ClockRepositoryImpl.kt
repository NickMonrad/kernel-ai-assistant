package com.kernel.ai.core.memory.clock

import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID
@Singleton
class ClockRepositoryImpl @Inject constructor(
    private val scheduledAlarmDao: ScheduledAlarmDao,
    private val scheduler: ClockScheduler,
) : ClockRepository {
    override fun observeManageableAlarms(): Flow<List<ClockAlarm>> =
        scheduledAlarmDao.observeAllAlarmSchedules().map { schedules ->
            schedules.map { it.toClockAlarm() }
        }

    override fun observeActiveAlarms(): Flow<List<ClockAlarm>> =
        scheduledAlarmDao.observeAllAlarmSchedules().map { schedules ->
            schedules.filter { it.enabled }.map { it.toClockAlarm() }
        }

    override fun observeUpcomingAlarms(): Flow<List<ClockAlarm>> =
        combine(scheduledAlarmDao.observeAllAlarmSchedules(), clockNowFlow()) { alarms, now ->
            alarms
                .filter { it.triggerAtMillis > now }
                .map { it.toClockAlarm() }
        }

    override fun observeActiveTimers(): Flow<List<ClockTimer>> =
        combine(scheduledAlarmDao.observeActiveTimers(), clockNowFlow()) { schedules, now ->
            schedules
                .filter { it.triggerAtMillis > now }
                .mapNotNull { it.toClockTimer() }
        }

    override fun observeRecentCompletedTimers(): Flow<List<ClockTimer>> =
        scheduledAlarmDao.observeRecentCompletedTimers().map { schedules ->
            schedules.mapNotNull { it.toClockTimer() }
        }

    override fun getPlatformState(): ClockPlatformState = scheduler.getPlatformState()

    override suspend fun scheduleAlarm(triggerAtMillis: Long, label: String?): ClockAlarm? {
        if (!scheduler.getPlatformState().canScheduleExactAlarms) return null
        val entity = ScheduledAlarmEntity(
            id = UUID.randomUUID().toString(),
            ownerId = null,
            triggerAtMillis = triggerAtMillis,
            label = label?.takeIf { it.isNotBlank() },
            createdAt = System.currentTimeMillis(),
            enabled = true,
            entryType = ClockEventType.ALARM.name,
        ).withDefaultOwnerId()
        scheduler.schedule(entity.toScheduledEvent())
        return try {
            scheduledAlarmDao.insert(entity)
            entity.toClockAlarm()
        } catch (_: Exception) {
            scheduler.cancel(entity.toScheduledEvent())
            null
        }
    }

    override suspend fun editAlarm(alarmId: String, newTriggerAtMillis: Long, newLabel: String?): ClockAlarm? {
        val existing = scheduledAlarmDao.getById(alarmId)?.withDefaultOwnerId() ?: return null
        val updated = existing.copy(
            triggerAtMillis = newTriggerAtMillis,
            label = newLabel?.takeIf { it.isNotBlank() },
        )
        if (updated.enabled) {
            if (!scheduler.getPlatformState().canScheduleExactAlarms) return null
            scheduler.schedule(updated.toScheduledEvent())
        } else if (existing.enabled) {
            scheduler.cancel(existing.toScheduledEvent())
        }
        return try {
            scheduledAlarmDao.insert(updated)
            updated.toClockAlarm()
        } catch (_: Exception) {
            if (existing.enabled) {
                scheduler.schedule(existing.toScheduledEvent())
            } else if (updated.enabled) {
                scheduler.cancel(updated.toScheduledEvent())
            }
            null
        }
    }

    override suspend fun setAlarmEnabled(alarmId: String, enabled: Boolean): Boolean {
        val existing = scheduledAlarmDao.getById(alarmId)?.withDefaultOwnerId() ?: return false
        val updated = existing.copy(enabled = enabled)
        if (enabled) {
            if (!scheduler.getPlatformState().canScheduleExactAlarms) return false
            if (updated.triggerAtMillis <= System.currentTimeMillis()) return false
            scheduler.schedule(updated.toScheduledEvent())
        } else {
            scheduler.cancel(existing.toScheduledEvent())
        }
        return try {
            scheduledAlarmDao.setEnabled(alarmId, enabled)
            true
        } catch (_: Exception) {
            if (enabled) {
                scheduler.cancel(updated.toScheduledEvent())
            } else if (existing.enabled) {
                scheduler.schedule(existing.toScheduledEvent())
            }
            false
        }
    }

    override suspend fun cancelAlarm(alarmId: String) {
        val existing = scheduledAlarmDao.getById(alarmId)?.withDefaultOwnerId() ?: return
        scheduler.cancel(existing.toScheduledEvent())
        scheduledAlarmDao.delete(alarmId)
    }

    override suspend fun cancelAlarms(alarmIds: Collection<String>) {
        alarmIds.forEach { cancelAlarm(it) }
    }

    override suspend fun cancelNextAlarm(): ClockAlarm? {
        val now = System.currentTimeMillis()
        val nextAlarm = scheduledAlarmDao.getUnfiredFuture(now)
            .asSequence()
            .filter { it.entryType == ClockEventType.ALARM.name }
            .filter { it.enabled }
            .sortedBy { it.triggerAtMillis }
            .map { it.withDefaultOwnerId() }
            .firstOrNull()
            ?: return null
        scheduler.cancel(nextAlarm.toScheduledEvent())
        scheduledAlarmDao.delete(nextAlarm.id)
        return nextAlarm.toClockAlarm()
    }

    override suspend fun cancelAlarmsByLabel(label: String): Int {
        val now = System.currentTimeMillis()
        val matches = scheduledAlarmDao.getUnfiredFuture(now)
            .asSequence()
            .filter { it.entryType == ClockEventType.ALARM.name }
            .filter { it.enabled }
            .filter { it.label?.equals(label, ignoreCase = true) == true }
            .map { it.withDefaultOwnerId() }
            .toList()
        matches.forEach { schedule ->
            scheduler.cancel(schedule.toScheduledEvent())
            scheduledAlarmDao.delete(schedule.id)
        }
        return matches.size
    }

    override suspend fun scheduleTimer(durationMs: Long, label: String?): ClockTimer? {
        if (!scheduler.getPlatformState().canScheduleExactAlarms) return null
        val now = System.currentTimeMillis()
        val entity = ScheduledAlarmEntity(
            id = UUID.randomUUID().toString(),
            ownerId = null,
            triggerAtMillis = now + durationMs,
            label = label?.takeIf { it.isNotBlank() },
            createdAt = now,
            enabled = true,
            entryType = ClockEventType.TIMER.name,
            durationMs = durationMs,
            startedAtMs = now,
        ).withDefaultOwnerId()
        scheduler.schedule(entity.toScheduledEvent())
        return try {
            scheduledAlarmDao.insert(entity)
            entity.toClockTimer() ?: error("Timer schedule missing duration metadata")
        } catch (_: Exception) {
            scheduler.cancel(entity.toScheduledEvent())
            null
        }
    }

    override suspend fun cancelTimer(timerId: String) {
        val existing = scheduledAlarmDao.getById(timerId)?.withDefaultOwnerId() ?: return
        scheduler.cancel(existing.toScheduledEvent())
        scheduledAlarmDao.delete(timerId)
    }

    override suspend fun deleteCompletedTimer(timerId: String): Boolean =
        scheduledAlarmDao.deleteCompletedTimer(timerId) > 0

    override suspend fun clearCompletedTimers(): Int =
        scheduledAlarmDao.deleteCompletedTimers()

    override suspend fun recordDeliveredEvent(eventId: String) {
        val existing = scheduledAlarmDao.getById(eventId)?.withDefaultOwnerId() ?: return
        if (existing.entryType == ClockEventType.TIMER.name) {
            scheduledAlarmDao.markTimerCompleted(eventId, System.currentTimeMillis())
        } else {
            scheduledAlarmDao.markFired(eventId)
        }
    }

    override suspend fun cancelTimers(timerIds: Collection<String>) {
        timerIds.forEach { cancelTimer(it) }
    }

    override suspend fun cancelAllTimers(): Int {
        val timers = scheduledAlarmDao.getAllTimers().map { it.withDefaultOwnerId() }
        timers.forEach { timer ->
            scheduler.cancel(timer.toScheduledEvent())
            scheduledAlarmDao.delete(timer.id)
        }
        return timers.size
    }

    override suspend fun cancelTimersMatching(name: String?, durationMs: Long?): Int {
        val matches = scheduledAlarmDao.getAllTimers()
            .filter { timer ->
                val nameMatches = name != null && timer.label?.equals(name, ignoreCase = true) == true
                val durationMatches = durationMs != null && timer.durationMs == durationMs
                nameMatches || durationMatches
            }
            .map { it.withDefaultOwnerId() }
        matches.forEach { timer ->
            scheduler.cancel(timer.toScheduledEvent())
            scheduledAlarmDao.delete(timer.id)
        }
        return matches.size
    }

    override suspend fun getAllTimers(): List<ClockTimer> =
        scheduledAlarmDao.getAllTimers().mapNotNull { it.toClockTimer() }

    override suspend fun restoreScheduledEntries(nowMillis: Long): ClockRestoreReport {
        val platformState = scheduler.getPlatformState()
        val expired = scheduledAlarmDao.getUnfiredElapsed(nowMillis).map { it.withDefaultOwnerId() }
        expired.forEach { scheduledAlarmDao.markFired(it.id) }

        val future = scheduledAlarmDao.getUnfiredFuture(nowMillis).map { it.withDefaultOwnerId() }
        val disabled = future.filterNot { it.enabled }
        val enabled = future.filter { it.enabled }

        if (!platformState.canScheduleExactAlarms) {
            enabled.forEach { schedule ->
                if (schedule.entryType == ClockEventType.TIMER.name) {
                    scheduledAlarmDao.markFired(schedule.id)
                } else {
                    scheduledAlarmDao.setEnabled(schedule.id, false)
                }
            }
            return ClockRestoreReport(
                restoredCount = 0,
                expiredCount = expired.size,
                disabledCount = disabled.size + enabled.count { it.entryType == ClockEventType.ALARM.name },
                blockedByExactAlarmCapability = true,
            )
        }

        enabled.forEach { scheduler.schedule(it.toScheduledEvent()) }
        return ClockRestoreReport(
            restoredCount = enabled.size,
            expiredCount = expired.size,
            disabledCount = disabled.size,
            blockedByExactAlarmCapability = false,
        )
    }
}

private fun ScheduledAlarmEntity.withDefaultOwnerId(): ScheduledAlarmEntity =
    if (ownerId.isNullOrBlank()) copy(ownerId = id) else this

private fun ScheduledAlarmEntity.toClockAlarm(): ClockAlarm =
    ClockAlarm(
        id = id,
        triggerAtMillis = triggerAtMillis,
        label = label,
        createdAtMillis = createdAt,
        enabled = enabled,
    )

private fun ScheduledAlarmEntity.toClockTimer(): ClockTimer? {
    val duration = durationMs ?: return null
    val startedAt = startedAtMs ?: return null
    return ClockTimer(
        id = id,
        triggerAtMillis = triggerAtMillis,
        label = label,
        createdAtMillis = createdAt,
        durationMs = duration,
        startedAtMillis = startedAt,
        completedAtMillis = completedAtMs,
    )
}

private fun ScheduledAlarmEntity.toScheduledEvent(): ClockScheduledEvent =
    ClockScheduledEvent(
        eventId = id,
        ownerId = ownerId ?: id,
        type = ClockEventType.valueOf(entryType),
        triggerAtMillis = triggerAtMillis,
        label = label,
        durationMs = durationMs,
        startedAtMillis = startedAtMs,
    )

private fun clockNowFlow(intervalMs: Long = 1_000L): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(intervalMs)
    }
}