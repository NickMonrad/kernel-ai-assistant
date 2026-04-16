package com.kernel.ai.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduledAlarmDao: ScheduledAlarmDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alarmManager = context.getSystemService(AlarmManager::class.java)
                val now = System.currentTimeMillis()
                val unfiredAlarms = scheduledAlarmDao.getUnfiredFuture(now)

                unfiredAlarms.forEach { alarm ->
                    val alarmIntent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
                        putExtra(AlarmBroadcastReceiver.EXTRA_LABEL, alarm.label ?: "Alarm")
                        putExtra(AlarmBroadcastReceiver.EXTRA_ALARM_ID, alarm.id)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        alarm.id.hashCode(),
                        alarmIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        alarm.triggerAtMillis,
                        pendingIntent,
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
