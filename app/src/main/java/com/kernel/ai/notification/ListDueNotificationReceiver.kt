package com.kernel.ai.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.notification.toNotificationId
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that fires when a list-item due-date notification alarm triggers (#901).
 *
 * Registered in AndroidManifest with action [ListNotificationScheduler.ACTION].
 * Uses Hilt entry-point injection via [AndroidEntryPoint] (no injected fields needed here —
 * all data arrives via intent extras).
 */
@AndroidEntryPoint
class ListDueNotificationReceiver : BroadcastReceiver() {
    @Inject lateinit var listItemDao: ListItemDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ListNotificationScheduler.ACTION) return

        val itemId = intent.getLongExtra(ListNotificationScheduler.EXTRA_ITEM_ID, -1L)
            .takeIf { it >= 0L } ?: return
        val itemText = intent.getStringExtra(ListNotificationScheduler.EXTRA_ITEM_TEXT) ?: return
        val listId = intent.getLongExtra(ListNotificationScheduler.EXTRA_LIST_ID, -1L)
        val listName = intent.getStringExtra(ListNotificationScheduler.EXTRA_LIST_NAME) ?: ""

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The alarm can fire after the item was ticked or deleted; neither owes a reminder.
                if (!shouldShowListReminder(listItemDao.getById(itemId))) return@launch
                showNotification(context, itemId, listId, itemText, listName)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, itemId: Long, listId: Long, itemText: String, listName: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(notificationManager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(listName.replaceFirstChar { it.uppercase() }.ifBlank { "List reminder" })
            .setContentText(itemText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenListPendingIntent(context, itemId, listId))
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

    /**
     * Opens the list the reminder belongs to. The request code is per item so that each
     * notification keeps its own target; reminders scheduled before list ids were carried fall back to the app.
     */
    private fun buildOpenListPendingIntent(context: Context, itemId: Long, listId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            itemId.toNotificationId(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (listId >= 0L) putExtra(NAVIGATION_ROUTE_EXTRA, listReminderRoute(listId))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_ID = "list_due_channel"
        private const val NAVIGATION_ROUTE_EXTRA = "navigation_route"
    }
}

/** A list reminder is shown only while its item still exists and is unticked. */
internal fun shouldShowListReminder(item: ListItemEntity?): Boolean = item != null && !item.checked

/** Nav route for a list screen; accepted by the external-route allowlist in `KernelNavHost`. */
internal fun listReminderRoute(listId: Long): String = "lists/$listId"
