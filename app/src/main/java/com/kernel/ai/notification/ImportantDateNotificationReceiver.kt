package com.kernel.ai.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.notification.ImportantDateNotificationScheduler
import com.kernel.ai.core.memory.notification.ImportantDateNotificationScheduler.Companion.YEAR_RECURRING
import com.kernel.ai.core.memory.notification.toNotificationId
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * BroadcastReceiver that fires when an important-date day-of notification alarm triggers (#902).
 *
 * Registered in AndroidManifest with action [ImportantDateNotificationScheduler.ACTION].
 * After posting the notification, recurring dates (year == [YEAR_RECURRING]) are automatically
 * rescheduled for the same month/day next year so the reminder repeats annually.
 * One-off dates (year != [YEAR_RECURRING]) are not rescheduled.
 */
@AndroidEntryPoint
class ImportantDateNotificationReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ImportantDateNotificationScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ImportantDateNotificationScheduler.ACTION) return

        val dateId = intent.getLongExtra(ImportantDateNotificationScheduler.EXTRA_DATE_ID, -1L)
            .takeIf { it >= 0L } ?: return
        val label = intent.getStringExtra(ImportantDateNotificationScheduler.EXTRA_LABEL) ?: return
        val month = intent.getIntExtra(ImportantDateNotificationScheduler.EXTRA_MONTH, -1)
            .takeIf { it in 1..12 } ?: return
        val day = intent.getIntExtra(ImportantDateNotificationScheduler.EXTRA_DAY, -1)
            .takeIf { it in 1..31 } ?: return
        val yearRaw = intent.getIntExtra(ImportantDateNotificationScheduler.EXTRA_YEAR, YEAR_RECURRING)
        val year: Int? = if (yearRaw == YEAR_RECURRING) null else yearRaw
        val notificationHour = intent.getIntExtra(ImportantDateNotificationScheduler.EXTRA_NOTIFICATION_HOUR, 9)
        val notificationMinute = intent.getIntExtra(ImportantDateNotificationScheduler.EXTRA_NOTIFICATION_MINUTE, 0)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(notificationManager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(buildTitle(label))
            .setContentText(buildDateText(month, day))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppPendingIntent(context))
            .build()

        notificationManager.notify(dateId.toNotificationId(), notification)

        // Reschedule for next year if this is a recurring date (no specific year).
        if (year == null) {
            scheduler.schedule(
                dateId = dateId,
                label = label,
                month = month,
                day = day,
                year = null,
                notificationHour = notificationHour,
                notificationMinute = notificationMinute,
            )
        }
    }

    private fun buildTitle(label: String): String =
        if (label.lowercase().contains("birthday")) {
            "🎂 Today is $label!"
        } else {
            "📅 $label is today"
        }

    private fun buildDateText(month: Int, day: Int): String {
        val date = LocalDate.of(LocalDate.now().year, month, day)
        return date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Important date reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Day-of reminders for important dates"
            },
        )
    }

    private fun buildOpenAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_ID = "important_dates_channel"
    }
}
