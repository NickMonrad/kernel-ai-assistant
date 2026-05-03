package com.kernel.ai.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.kernel.ai.core.memory.clock.ClockRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ClockStopwatchActionReceiver : BroadcastReceiver() {
    @Inject lateinit var clockRepository: ClockRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ClockAlertContract.ACTION_PAUSE_STOPWATCH -> {
                        clockRepository.pauseStopwatch(
                            nowWallClockMillis = System.currentTimeMillis(),
                            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        )
                    }

                    ClockAlertContract.ACTION_RESUME_STOPWATCH -> {
                        clockRepository.resumeStopwatch(
                            nowWallClockMillis = System.currentTimeMillis(),
                            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        )
                    }

                    ClockAlertContract.ACTION_RESET_STOPWATCH -> {
                        context.getSystemService(NotificationManager::class.java)
                            .cancel(ClockAlertContract.STOPWATCH_NOTIFICATION_ID)
                        clockRepository.resetStopwatch()
                    }

                    else -> Unit
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
