package com.kernel.ai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.dao.ImportantDateDao
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.notification.ImportantDateNotificationPreferences
import com.kernel.ai.core.memory.notification.ImportantDateNotificationScheduler
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var clockRepository: ClockRepository
    @Inject lateinit var listItemDao: ListItemDao
    @Inject lateinit var listNameDao: ListNameDao
    @Inject lateinit var listNotificationScheduler: ListNotificationScheduler
    @Inject lateinit var importantDateDao: ImportantDateDao
    @Inject lateinit var importantDateNotificationScheduler: ImportantDateNotificationScheduler
    @Inject lateinit var importantDateNotificationPreferences: ImportantDateNotificationPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                clockRepository.restoreScheduledEntries()
                listItemDao.getAllActiveWithNotification().forEach { item ->
                    val listName = listNameDao.getById(item.listId)?.name ?: ""
                    listNotificationScheduler.schedule(
                        itemId = item.id,
                        itemText = item.text,
                        listId = item.listId,
                        listName = listName,
                        triggerAtMs = item.notificationTime!!,
                    )
                }

                val (hour, minute) = importantDateNotificationPreferences.getNotificationTime()
                importantDateDao.getAllWithNotificationEnabled().forEach { date ->
                    importantDateNotificationScheduler.schedule(
                        dateId = date.id,
                        label = date.label,
                        month = date.month,
                        day = date.day,
                        year = date.year,
                        notificationHour = hour,
                        notificationMinute = minute,
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

