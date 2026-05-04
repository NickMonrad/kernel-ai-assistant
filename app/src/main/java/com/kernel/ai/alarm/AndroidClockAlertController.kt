package com.kernel.ai.alarm

import android.content.Context
import android.content.Intent
import com.kernel.ai.core.skills.natives.ClockAlertController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidClockAlertController @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClockAlertController {
    override fun dismissActiveTimerAlerts(): Boolean {
        val hadActiveTimerAlert = ClockAlertService.hasActiveTimerAlerts()
        if (!hadActiveTimerAlert) return false

        context.startService(
            Intent(context, ClockAlertService::class.java).apply {
                action = ClockAlertContract.ACTION_STOP_TIMER_ALERTS
            },
        )
        return true
    }
}
