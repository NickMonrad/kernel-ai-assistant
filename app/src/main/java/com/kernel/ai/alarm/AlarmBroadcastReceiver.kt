package com.kernel.ai.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.memory.clock.ClockRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AlarmBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var clockRepository: ClockRepository

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(ClockAlertContract.EXTRA_LABEL) ?: "Alarm"
        val ownerId = intent.getStringExtra(ClockAlertContract.EXTRA_OWNER_ID) ?: return
        val title = intent.getStringExtra(ClockAlertContract.EXTRA_TITLE) ?: "Alarm"
        val type = intent.getStringExtra(ClockAlertContract.EXTRA_EVENT_TYPE)
            ?.let(ClockEventType::valueOf)
            ?: ClockEventType.ALARM

        if (type == ClockEventType.TIMER) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(ClockAlertContract.timerNotificationId(ownerId))
        }
        ClockAlertService.trigger(
            context,
            TriggeredClockAlert(ownerId = ownerId, type = type, title = title, label = label),
        )

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clockRepository.recordDeliveredEvent(ownerId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
