package com.kernel.ai.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernel.ai.core.memory.clock.ClockRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ClockTimerActionReceiver : BroadcastReceiver() {
    @Inject lateinit var clockRepository: ClockRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClockAlertContract.ACTION_CANCEL_TIMER) return
        val timerId = intent.getStringExtra(ClockAlertContract.EXTRA_TIMER_ID) ?: return
        context.getSystemService(NotificationManager::class.java)
            .cancel(ClockAlertContract.timerNotificationId(timerId))

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clockRepository.cancelTimer(timerId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
