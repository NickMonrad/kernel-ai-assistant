package com.kernel.ai.core.memory.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and cancels exact AlarmManager broadcasts for list-item due-date reminders (#901).
 *
 * Placed in :core:memory so it can be injected by ListsViewModel in :feature:settings.
 * The broadcast is handled by ListDueNotificationReceiver in :app, which is registered in
 * the app manifest with the [ACTION] intent filter.
 */
@Singleton
class ListNotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /**
     * Schedule (or replace) an exact alarm for [itemId] that fires at [triggerAtMs].
     * Any existing pending intent for the same [itemId] is cancelled first.
     */
    fun schedule(
        itemId: Long,
        itemText: String,
        listId: Long,
        listName: String,
        triggerAtMs: Long,
    ) {
        val pendingIntent = buildPendingIntent(
            itemId = itemId,
            itemText = itemText,
            listId = listId,
            listName = listName,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            pendingIntent!!,
        )
    }

    /** Cancel any pending notification alarm for [itemId]. No-op if none is scheduled. */
    fun cancel(itemId: Long) {
        val pendingIntent = buildPendingIntent(
            itemId = itemId,
            itemText = "",
            listId = 0L,
            listName = "",
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pendingIntent)
    }

    private fun buildPendingIntent(
        itemId: Long,
        itemText: String,
        listId: Long,
        listName: String,
        flags: Int,
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        itemId.toNotificationId(),
        Intent(ACTION).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_ITEM_TEXT, itemText)
            putExtra(EXTRA_LIST_ID, listId)
            putExtra(EXTRA_LIST_NAME, listName)
        },
        flags,
    )

    companion object {
        const val ACTION = "com.kernel.ai.LIST_ITEM_DUE"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_ITEM_TEXT = "item_text"
        const val EXTRA_LIST_ID = "list_id"
        const val EXTRA_LIST_NAME = "list_name"
    }
}

/**
 * Folds a Long item ID into an Int notification/request-code without silent wrap-around.
 * XOR-folds the upper 32 bits into the lower 32, so IDs differing only in the upper half
 * still produce distinct Int values within the realistic Room ID range.
 */
fun Long.toNotificationId(): Int = (this xor (this ushr 32)).toInt()
