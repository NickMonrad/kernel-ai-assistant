package com.kernel.ai.core.memory.clock

import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val PRE_ALARM_LEAD_MS = 30 * 60 * 1_000L
private const val PRE_ALARM_MIN_NOTICE_MS = 1_000L
private const val PRE_ALARM_EVENT_SUFFIX = ":pre"

@Singleton
class ClockRepositoryImpl @Inject constructor(
    private val scheduledAlarmDao: ScheduledAlarmDao,
    private val scheduler: ClockScheduler,
 ) : ClockRepository {
    override fun observeManageableAlarms(): Flow<List<ClockAlarm>> =
        scheduledAlarmDao.observeAllAlarmSchedules().map { schedules ->
            schedules.mapNotNull { it.toClockAlarm() }
        }

    override fun observeActiveAlarms(): Flow<List<ClockAlarm>> =
        combine(scheduledAlarmDao.observeAllAlarmSchedules(), clockNowFlow()) { schedules, now ->
            schedules
                .asSequence()
                .mapNotNull { it.toClockAlarm() }
                .filter { it.enabled && it.nextTriggerAtMillis > now }
                .toList()
        }

    override fun observeUpcomingAlarms(): Flow<List<ClockAlarm>> =
        observeActiveAlarms()

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

    override suspend fun createAlarm(draft: AlarmDraft): ClockAlarm? {
        if (!scheduler.getPlatformState().canScheduleExactAlarms) return null
        val now = System.currentTimeMillis()
        val triggerAtMillis = draft.nextTriggerAtMillis(now) ?: return null
        val entity = ScheduledAlarmEntity(
            id = UUID.randomUUID().toString(),
            ownerId = null,
            triggerAtMillis = triggerAtMillis,
            label = draft.label?.takeIf { it.isNotBlank() },
            createdAt = now,
            enabled = true,
            entryType = ClockEventType.ALARM.name,
            alarmHour = draft.hour,
            alarmMinute = draft.minute,
            repeatType = draft.repeatTypeName(),
            repeatDaysMask = draft.repeatDaysMask(),
            oneOffDateEpochDay = draft.oneOffDateEpochDay(),
            timeZoneId = draft.timeZoneId,
        ).withDefaultOwnerId()
        scheduleAlarmEvents(entity, now)
        return try {
            scheduledAlarmDao.insert(entity)
            entity.toClockAlarm()
        } catch (_: Exception) {
            cancelAlarmEvents(entity, now)
            null
        }
    }

    override suspend fun updateAlarm(alarmId: String, draft: AlarmDraft): ClockAlarm? {
        val existing = scheduledAlarmDao.getById(alarmId)?.withDefaultOwnerId() ?: return null
        if (existing.entryType != ClockEventType.ALARM.name) return null
        val now = System.currentTimeMillis()
        val triggerAtMillis = draft.nextTriggerAtMillis(now) ?: return null
        val updated = existing.copy(
            triggerAtMillis = triggerAtMillis,
            label = draft.label?.takeIf { it.isNotBlank() },
            fired = false,
            enabled = true,
            alarmHour = draft.hour,
            alarmMinute = draft.minute,
            repeatType = draft.repeatTypeName(),
            repeatDaysMask = draft.repeatDaysMask(),
            oneOffDateEpochDay = draft.oneOffDateEpochDay(),
            timeZoneId = draft.timeZoneId,
        )
        cancelAlarmEvents(existing, now)
        scheduleAlarmEvents(updated, now)
        return try {
            scheduledAlarmDao.insert(updated)
            updated.toClockAlarm()
        } catch (_: Exception) {
            cancelAlarmEvents(updated, now)
            scheduleAlarmEvents(existing, now)
            null
        }
    }

    override suspend fun setAlarmEnabled(alarmId: String, enabled: Boolean): Boolean {
        val existing = scheduledAlarmDao.getById(alarmId)?.withDefaultOwnerId() ?: return false
        if (existing.entryType != ClockEventType.ALARM.name) return false
        val now = System.currentTimeMillis()
        if (!enabled) {
            cancelAlarmEvents(existing, now)
            return try {
                scheduledAlarmDao.insert(existing.copy(enabled = false))
                true
            } catch (_: Exception) {
                scheduleAlarmEvents(existing, now)
                false
            }
        }

        if (!scheduler.getPlatformState().canScheduleExactAlarms) return false
        val draft = existing.toAlarmDraft() ?: return false
        val nextTriggerAtMillis = draft.nextTriggerAtMillis(now) ?: return false
        val updated = existing.copy(
            enabled = true,
            fired = false,
            triggerAtMillis = nextTriggerAtMillis,
        )
        scheduleAlarmEvents(updated, now)
        return try {
            scheduledAlarmDao.insert(updated)
            true
        } catch (_: Exception) {
            cancelAlarmEvents(updated, now)
            false
        }
    }

    override suspend fun cancelAlarm(alarmId: String) {
        val existing = scheduledAlarmDao.getById(alarmId)?.withDefaultOwnerId() ?: return
        cancelAlarmEvents(existing, System.currentTimeMillis())
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
        cancelAlarmEvents(nextAlarm, now)
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
            cancelAlarmEvents(schedule, now)
            scheduledAlarmDao.delete(schedule.id)
        }
        return matches.size
    }

    override suspend fun skipAlarmOccurrence(alarmId: String, occurrenceTriggerAtMillis: Long): Boolean {
        val existing = scheduledAlarmDao.getById(alarmId)?.withDefaultOwnerId() ?: return false
        if (existing.entryType != ClockEventType.ALARM.name || !existing.enabled) return false
        if (!existing.isRepeatingAlarm() || existing.triggerAtMillis != occurrenceTriggerAtMillis) return false
        val nextTriggerAtMillis = existing.nextRepeatingTriggerAfter(occurrenceTriggerAtMillis) ?: return false
        val updated = existing.copy(triggerAtMillis = nextTriggerAtMillis, fired = false)
        cancelAlarmEvents(existing, System.currentTimeMillis())
        scheduleAlarmEvents(updated, System.currentTimeMillis())
        return try {
            scheduledAlarmDao.insert(updated)
            true
        } catch (_: Exception) {
            cancelAlarmEvents(updated, System.currentTimeMillis())
            scheduleAlarmEvents(existing, System.currentTimeMillis())
            false
        }
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

    override suspend fun handleScheduledEvent(
        ownerId: String,
        type: ClockEventType,
        occurrenceTriggerAtMillis: Long?,
    ) {
        val existing = scheduledAlarmDao.getById(ownerId)?.withDefaultOwnerId() ?: return
        when (type) {
            ClockEventType.TIMER -> {
                scheduledAlarmDao.markTimerCompleted(ownerId, System.currentTimeMillis())
            }

            ClockEventType.PRE_ALARM -> Unit

            ClockEventType.ALARM -> {
                if (existing.entryType != ClockEventType.ALARM.name) return
                if (occurrenceTriggerAtMillis != null && existing.triggerAtMillis != occurrenceTriggerAtMillis) return
                if (!existing.isRepeatingAlarm()) {
                    scheduledAlarmDao.markFired(ownerId)
                    return
                }
                val nextTriggerAtMillis = existing.nextRepeatingTriggerAfter(existing.triggerAtMillis) ?: return
                val updated = existing.copy(triggerAtMillis = nextTriggerAtMillis, fired = false)
                scheduleAlarmEvents(updated, System.currentTimeMillis())
                scheduledAlarmDao.insert(updated)
            }
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
        val future = scheduledAlarmDao.getUnfiredFuture(nowMillis).map { it.withDefaultOwnerId() }
        val disabled = future.filterNot { it.enabled }

        if (!platformState.canScheduleExactAlarms) {
            expired.forEach { schedule ->
                when (schedule.entryType) {
                    ClockEventType.TIMER.name -> scheduledAlarmDao.markFired(schedule.id)
                    ClockEventType.ALARM.name -> scheduledAlarmDao.insert(schedule.copy(enabled = false))
                }
            }
            future.filter { it.enabled }.forEach { schedule ->
                when (schedule.entryType) {
                    ClockEventType.TIMER.name -> scheduledAlarmDao.markFired(schedule.id)
                    ClockEventType.ALARM.name -> scheduledAlarmDao.insert(schedule.copy(enabled = false))
                }
            }
            return ClockRestoreReport(
                restoredCount = 0,
                expiredCount = expired.size,
                disabledCount = disabled.size + future.count { it.enabled && it.entryType == ClockEventType.ALARM.name },
                blockedByExactAlarmCapability = true,
            )
        }

        var restoredCount = 0
        var expiredCount = 0
        expired.forEach { schedule ->
            when (schedule.entryType) {
                ClockEventType.TIMER.name -> {
                    scheduledAlarmDao.markFired(schedule.id)
                    expiredCount += 1
                }

                ClockEventType.ALARM.name -> {
                    if (!schedule.enabled) {
                        return@forEach
                    }
                    if (!schedule.isRepeatingAlarm()) {
                        scheduledAlarmDao.markFired(schedule.id)
                        expiredCount += 1
                    } else {
                        val nextTriggerAtMillis = schedule.nextRepeatingTriggerAfter(nowMillis) ?: return@forEach
                        val updated = schedule.copy(triggerAtMillis = nextTriggerAtMillis, fired = false)
                        scheduledAlarmDao.insert(updated)
                        scheduleAlarmEvents(updated, nowMillis)
                        restoredCount += 1
                    }
                }
            }
        }

        future.filter { it.enabled }.forEach { schedule ->
            when (schedule.entryType) {
                ClockEventType.TIMER.name -> {
                    scheduler.schedule(schedule.toScheduledEvent())
                    restoredCount += 1
                }

                ClockEventType.ALARM.name -> {
                    scheduleAlarmEvents(schedule, nowMillis)
                    restoredCount += 1
                }
            }
        }

        return ClockRestoreReport(
            restoredCount = restoredCount,
            expiredCount = expiredCount,
            disabledCount = disabled.size,
            blockedByExactAlarmCapability = false,
        )
    }

    private fun scheduleAlarmEvents(entity: ScheduledAlarmEntity, nowMillis: Long) {
        scheduler.schedule(entity.toScheduledEvent())
        entity.toPreAlarmScheduledEvent(nowMillis)?.let(scheduler::schedule)
    }

    private fun cancelAlarmEvents(entity: ScheduledAlarmEntity, nowMillis: Long) {
        scheduler.cancel(entity.toScheduledEvent())
        entity.toPreAlarmScheduledEvent(nowMillis)?.let(scheduler::cancel)
        scheduler.cancel(entity.toPreAlarmCancellationEvent())
    }
}

private fun ScheduledAlarmEntity.withDefaultOwnerId(): ScheduledAlarmEntity =
    if (ownerId.isNullOrBlank()) copy(ownerId = id) else this

private fun ScheduledAlarmEntity.toClockAlarm(): ClockAlarm? {
    if (entryType != ClockEventType.ALARM.name) return null
    val zoneId = ZoneId.of(timeZoneId ?: ZoneId.systemDefault().id)
    val trigger = Instant.ofEpochMilli(triggerAtMillis).atZone(zoneId)
    val repeatRule = toAlarmRepeatRule(trigger)
    return ClockAlarm(
        id = id,
        label = label,
        createdAtMillis = createdAt,
        enabled = enabled,
        hour = alarmHour ?: trigger.hour,
        minute = alarmMinute ?: trigger.minute,
        repeatRule = repeatRule,
        timeZoneId = zoneId.id,
        triggerAtMillis = triggerAtMillis,
    )
}

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
        occurrenceTriggerAtMillis = triggerAtMillis,
    )

private fun ScheduledAlarmEntity.toPreAlarmScheduledEvent(nowMillis: Long): ClockScheduledEvent? {
    if (entryType != ClockEventType.ALARM.name || !enabled || !isRepeatingAlarm()) return null
    val preAlarmTrigger = maxOf(nowMillis + PRE_ALARM_MIN_NOTICE_MS, triggerAtMillis - PRE_ALARM_LEAD_MS)
    if (preAlarmTrigger >= triggerAtMillis) return null
    return ClockScheduledEvent(
        eventId = "${ownerId ?: id}$PRE_ALARM_EVENT_SUFFIX",
        ownerId = ownerId ?: id,
        type = ClockEventType.PRE_ALARM,
        triggerAtMillis = preAlarmTrigger,
        label = label,
        occurrenceTriggerAtMillis = triggerAtMillis,
    )
}

private fun ScheduledAlarmEntity.toPreAlarmCancellationEvent(): ClockScheduledEvent =
    ClockScheduledEvent(
        eventId = "${ownerId ?: id}$PRE_ALARM_EVENT_SUFFIX",
        ownerId = ownerId ?: id,
        type = ClockEventType.PRE_ALARM,
        triggerAtMillis = triggerAtMillis,
        label = label,
        occurrenceTriggerAtMillis = triggerAtMillis,
    )

private fun ScheduledAlarmEntity.isRepeatingAlarm(): Boolean =
    repeatType == "DAILY" || repeatType == "SELECTED_WEEKDAYS"

private fun ScheduledAlarmEntity.toAlarmDraft(): AlarmDraft? {
    if (entryType != ClockEventType.ALARM.name) return null
    val zoneId = timeZoneId ?: ZoneId.systemDefault().id
    val trigger = Instant.ofEpochMilli(triggerAtMillis).atZone(ZoneId.of(zoneId))
    return AlarmDraft(
        label = label,
        hour = alarmHour ?: trigger.hour,
        minute = alarmMinute ?: trigger.minute,
        repeatRule = toAlarmRepeatRule(trigger),
        timeZoneId = zoneId,
    )
}

private fun ScheduledAlarmEntity.toAlarmRepeatRule(trigger: ZonedDateTime): AlarmRepeatRule =
    when (repeatType) {
        "DAILY" -> AlarmRepeatRule.Daily
        "SELECTED_WEEKDAYS" -> AlarmRepeatRule.SelectedWeekdays(repeatDaysMask ?: 0)
        else -> AlarmRepeatRule.OneOff(oneOffDateEpochDay ?: trigger.toLocalDate().toEpochDay())
    }

private fun ScheduledAlarmEntity.nextRepeatingTriggerAfter(afterMillis: Long): Long? =
    toAlarmDraft()?.nextTriggerAtMillis(afterMillis)

private fun AlarmDraft.repeatTypeName(): String =
    when (repeatRule) {
        is AlarmRepeatRule.OneOff -> "ONE_OFF"
        AlarmRepeatRule.Daily -> "DAILY"
        is AlarmRepeatRule.SelectedWeekdays -> "SELECTED_WEEKDAYS"
    }

private fun AlarmDraft.repeatDaysMask(): Int? =
    (repeatRule as? AlarmRepeatRule.SelectedWeekdays)?.daysMask

private fun AlarmDraft.oneOffDateEpochDay(): Long? =
    (repeatRule as? AlarmRepeatRule.OneOff)?.dateEpochDay

private fun AlarmDraft.nextTriggerAtMillis(fromMillis: Long): Long? {
    val zone = ZoneId.of(timeZoneId)
    val from = Instant.ofEpochMilli(fromMillis).atZone(zone)
    return when (val rule = repeatRule) {
        is AlarmRepeatRule.OneOff -> {
            val candidate = ZonedDateTime.of(
                java.time.LocalDate.ofEpochDay(rule.dateEpochDay),
                java.time.LocalTime.of(hour, minute),
                zone,
            ).toInstant().toEpochMilli()
            candidate.takeIf { it > fromMillis }
        }

        AlarmRepeatRule.Daily -> {
            val todayCandidate = from.toLocalDate().atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (todayCandidate > fromMillis) {
                todayCandidate
            } else {
                from.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            }
        }

        is AlarmRepeatRule.SelectedWeekdays -> {
            for (offset in 0..7) {
                val date = from.toLocalDate().plusDays(offset.toLong())
                if (!rule.includes(date.dayOfWeek)) continue
                val candidate = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                if (candidate > fromMillis) return candidate
            }
            null
        }
    }
}

private fun clockNowFlow(intervalMs: Long = 1_000L): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(intervalMs)
    }
}