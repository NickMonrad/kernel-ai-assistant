package com.kernel.ai.core.memory.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and cancels exact AlarmManager broadcasts for important-date day-of reminders (#902).
 *
 * Placed in :core:memory so it can be injected by [ImportantDateRepository] and
 * [com.kernel.ai.alarm.BootCompletedReceiver].
 * The broadcast is handled by ImportantDateNotificationReceiver in :app, registered in the
 * app manifest with the [ACTION] intent filter.
 *
 * Recurring dates (year == null) are scheduled for the next upcoming month/day at the given
 * hour/minute; one-off dates (year != null) are scheduled for that specific calendar date.
 * If the trigger time is already in the past the alarm is silently skipped.
 */
@Singleton
class ImportantDateNotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /**
     * Schedule (or replace) an exact alarm for [dateId].
     * The alarm fires at [notificationHour]:[notificationMinute] on the next occurrence of
     * [month]/[day] (this year if not yet passed, next year otherwise for recurring dates;
     * or on the exact [year] for one-off dates).
     * No-op if the computed trigger time is already in the past.
     */
    fun schedule(
        dateId: Long,
        label: String,
        month: Int,
        day: Int,
        year: Int?,
        notificationHour: Int,
        notificationMinute: Int,
    ) {
        val triggerAtMs = nextTriggerMs(month, day, year, notificationHour, notificationMinute)
            ?: return
        val pendingIntent = buildPendingIntent(
            dateId = dateId,
            label = label,
            month = month,
            day = day,
            year = year,
            notificationHour = notificationHour,
            notificationMinute = notificationMinute,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            pendingIntent!!,
        )
    }

    /** Cancel any pending notification alarm for [dateId]. No-op if none is scheduled. */
    fun cancel(dateId: Long) {
        val pendingIntent = buildPendingIntent(
            dateId = dateId,
            label = "",
            month = 1,
            day = 1,
            year = null,
            notificationHour = 0,
            notificationMinute = 0,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pendingIntent)
    }

    private fun buildPendingIntent(
        dateId: Long,
        label: String,
        month: Int,
        day: Int,
        year: Int?,
        notificationHour: Int,
        notificationMinute: Int,
        flags: Int,
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        dateId.toNotificationId(),
        Intent(ACTION).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_DATE_ID, dateId)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_MONTH, month)
            putExtra(EXTRA_DAY, day)
            putExtra(EXTRA_YEAR, year ?: YEAR_RECURRING)
            putExtra(EXTRA_NOTIFICATION_HOUR, notificationHour)
            putExtra(EXTRA_NOTIFICATION_MINUTE, notificationMinute)
        },
        flags,
    )

    companion object {
        const val ACTION = "com.kernel.ai.IMPORTANT_DATE_REMINDER"
        const val EXTRA_DATE_ID = "date_id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_MONTH = "month"
        const val EXTRA_DAY = "day"

        /** Sentinel value stored in extras when [year] is null (recurring date). */
        const val EXTRA_YEAR = "year"
        const val YEAR_RECURRING = -1

        const val EXTRA_NOTIFICATION_HOUR = "notification_hour"
        const val EXTRA_NOTIFICATION_MINUTE = "notification_minute"
    }
}

/**
 * Returns the epoch-millisecond trigger time for the next occurrence of [month]/[day] at
 * [notificationHour]:[notificationMinute], or null if the date is already in the past.
 *
 * For recurring dates ([year] == null): uses the current year's occurrence if it has not yet
 * passed, otherwise advances to next year.
 * For one-off dates ([year] != null): uses the specific year; returns null if already past.
 *
 * Feb 29 in a non-leap year is clamped to Feb 28 rather than rolling to March 1.
 */
internal fun nextTriggerMs(
    month: Int,
    day: Int,
    year: Int?,
    notificationHour: Int,
    notificationMinute: Int,
): Long? {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val todayYear = LocalDate.now(zone).year

    fun triggerInstantFor(y: Int): Instant {
        val clampedDay = minOf(day, YearMonth.of(y, month).lengthOfMonth())
        return LocalDate.of(y, month, clampedDay)
            .atTime(notificationHour, notificationMinute)
            .atZone(zone)
            .toInstant()
    }

    return if (year != null) {
        triggerInstantFor(year).takeIf { it.isAfter(now) }?.toEpochMilli()
    } else {
        val thisYear = triggerInstantFor(todayYear)
        val candidate = if (thisYear.isAfter(now)) thisYear else triggerInstantFor(todayYear + 1)
        candidate.takeIf { it.isAfter(now) }?.toEpochMilli()
    }
}
