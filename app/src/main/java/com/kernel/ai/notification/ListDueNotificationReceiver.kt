package com.kernel.ai.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
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
    @Inject lateinit var listNameDao: ListNameDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ListNotificationScheduler.ACTION) return

        val itemId = intent.getLongExtra(ListNotificationScheduler.EXTRA_ITEM_ID, -1L)
            .takeIf { it >= 0L } ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The alarm can fire after the item was deleted or ticked off, or after its list was deleted or archived.
                val item = listItemDao.getById(itemId)
                val list = item?.let { listNameDao.getById(it.listId) }
                val reminder = dueReminderFor(item, list) ?: return@launch
                showNotification(context, reminder)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, reminder: DueReminder) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(notificationManager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(reminder.listName.replaceFirstChar { it.uppercase() }.ifBlank { "List reminder" })
            .setContentText(reminder.itemText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenListPendingIntent(context, reminder))
            .build()

        notificationManager.notify(reminder.itemId.toNotificationId(), notification)
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
     * Opens the list the reminder belongs to. The data URI keeps this PendingIntent distinct from other
     * activity intents (for example the stopwatch request), and the per-item request code keeps each
     * notification's target separate.
     */
    private fun buildOpenListPendingIntent(context: Context, reminder: DueReminder): PendingIntent =
        PendingIntent.getActivity(
            context,
            reminder.itemId.toNotificationId(),
            Intent(context, MainActivity::class.java).apply {
                data = Uri.parse("jandal://list-reminder/${reminder.itemId}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(NAVIGATION_ROUTE_EXTRA, listReminderRoute(reminder.listId))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_ID = "list_due_channel"
        private const val NAVIGATION_ROUTE_EXTRA = "navigation_route"
    }
}

/** A reminder to show: its item is open and its list is active and unarchived. */
internal data class DueReminder(val itemId: Long, val itemText: String, val listId: Long, val listName: String)

/**
 * Null when the reminder must stay silent: the item was deleted or ticked off, or its list was deleted or archived.
 * Reads only database rows, so renamed lists and edited items show their current text.
 */
internal fun dueReminderFor(item: ListItemEntity?, list: ListNameEntity?): DueReminder? {
    if (item == null || item.lifecycle != "ACTIVE" || item.checked) return null
    if (list == null || list.lifecycle != "ACTIVE" || list.archivedAt != null) return null
    return DueReminder(itemId = item.id, itemText = item.text, listId = list.id, listName = list.name)
}

/** Nav route for a list screen; accepted by the external-route allowlist in `KernelNavHost`. */
internal fun listReminderRoute(listId: Long): String = "lists/$listId"
