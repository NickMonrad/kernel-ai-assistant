package com.kernel.ai.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.notification.toNotificationId
import dagger.hilt.android.AndroidEntryPoint

/**
 * BroadcastReceiver that fires when a list-item due-date notification alarm triggers (#901).
 *
 * Registered in AndroidManifest with action [ListNotificationScheduler.ACTION].
 * Uses Hilt entry-point injection via [AndroidEntryPoint] (no injected fields needed here —
 * all data arrives via intent extras).
 */
@AndroidEntryPoint
class ListDueNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ListNotificationScheduler.ACTION) return

        val itemId = intent.getLongExtra(ListNotificationScheduler.EXTRA_ITEM_ID, -1L)
            .takeIf { it >= 0L } ?: return
        val itemText = intent.getStringExtra(ListNotificationScheduler.EXTRA_ITEM_TEXT) ?: return
        val listName = intent.getStringExtra(ListNotificationScheduler.EXTRA_LIST_NAME) ?: ""

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(notificationManager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(listName.replaceFirstChar { it.uppercase() }.ifBlank { "List reminder" })
            .setContentText(itemText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppPendingIntent(context))
            .build()

        notificationManager.notify(itemId.toNotificationId(), notification)
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "List reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminders for list items with a due date"
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
        const val CHANNEL_ID = "list_due_channel"
    }
}
