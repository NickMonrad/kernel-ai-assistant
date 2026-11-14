package com.kernel.ai.bootrestore

import android.content.Context
import android.content.Intent
import com.kernel.ai.alarm.BootCompletedReceiver
import dagger.hilt.android.EntryPointAccessors

/**
 * Test-app-only unprotected trigger. It delegates on this framework-created receiver instance to
 * the production BOOT_COMPLETED implementation, so its goAsync() PendingResult remains tied to
 * the test broadcast and completion can be awaited without sending a protected system action.
 */
class BootRestoreAcceptanceReceiver : BootCompletedReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEST_BOOT_RESTORE) return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootRestoreDatabaseEntryPoint::class.java,
        )
        clockRepository = entryPoint.clockRepository()
        listItemDao = entryPoint.listItemDao()
        listNameDao = entryPoint.listNameDao()
        listNotificationScheduler = entryPoint.listNotificationScheduler()
        importantDateDao = entryPoint.importantDateDao()
        importantDateNotificationScheduler = entryPoint.importantDateNotificationScheduler()
        importantDateNotificationPreferences = entryPoint.importantDateNotificationPreferences()

        super.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    companion object {
        const val ACTION_TEST_BOOT_RESTORE =
            "com.kernel.ai.debug.bootrestore.action.TEST_BOOT_RESTORE"
    }
}
