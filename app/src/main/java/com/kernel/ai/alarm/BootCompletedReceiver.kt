package com.kernel.ai.alarm

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
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var clockRepository: ClockRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clockRepository.restoreScheduledEntries()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
