package com.kernel.ai.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.kernel.ai.core.memory.clock.ClockRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AlarmBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_LABEL = "alarm_label"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_TITLE = "alarm_title"
        const val NOTIFICATION_CHANNEL_ID = "kernel_alarm"
    }

    @Inject lateinit var clockRepository: ClockRepository

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Alarm"
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarm"

        if (title == "Timer") {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    clockRepository.cancelTimer(alarmId)
                } finally {
                    pendingResult.finish()
                }
            }
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Kernel AI alarm notifications"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build(),
            )
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        notificationManager.notify(alarmId.hashCode(), notification)
    }
}
