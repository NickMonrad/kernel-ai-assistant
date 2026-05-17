package com.kernel.ai.core.memory

import com.kernel.ai.core.memory.dao.ImportantDateDao
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import com.kernel.ai.core.memory.notification.ImportantDateNotificationPreferences
import com.kernel.ai.core.memory.notification.ImportantDateNotificationScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportantDateRepository @Inject constructor(
    private val dao: ImportantDateDao,
    private val notificationScheduler: ImportantDateNotificationScheduler,
    private val notificationPreferences: ImportantDateNotificationPreferences,
) {
    fun observeAll(): Flow<List<ImportantDateEntity>> = dao.observeAll()

    val notificationTime: Flow<Pair<Int, Int>> = notificationPreferences.notificationTime

    suspend fun getAll(): List<ImportantDateEntity> = dao.getAll()

    suspend fun findByLabel(label: String): ImportantDateEntity? =
        dao.findByNormalizedLabel(normalizeLabel(label))

    suspend fun save(
        label: String,
        month: Int,
        day: Int,
        year: Int?,
        notificationHour: Int? = null,
        notificationMinute: Int? = null,
    ) {
        val trimmedLabel = label.trim()
        val normalized = normalizeLabel(trimmedLabel)
        // Cancel any existing alarm before @Insert(REPLACE) deletes the old row and its ID.
        dao.findByNormalizedLabel(normalized)?.let { existing ->
            notificationScheduler.cancel(existing.id)
        }
        val insertedId = dao.insert(
            ImportantDateEntity(
                label = trimmedLabel,
                normalizedLabel = normalized,
                month = month,
                day = day,
                year = year,
                notificationEnabled = true,
                notificationHour = notificationHour,
                notificationMinute = notificationMinute,
            ),
        )
        val (globalHour, globalMinute) = notificationPreferences.getNotificationTime()
        notificationScheduler.schedule(
            dateId = insertedId,
            label = trimmedLabel,
            month = month,
            day = day,
            year = year,
            notificationHour = notificationHour ?: globalHour,
            notificationMinute = notificationMinute ?: globalMinute,
        )
    }

    suspend fun deleteByLabel(label: String): Int {
        val normalized = normalizeLabel(label)
        val entity = dao.findByNormalizedLabel(normalized)
        val result = dao.deleteByNormalizedLabel(normalized)
        if (result > 0 && entity != null) {
            notificationScheduler.cancel(entity.id)
        }
        return result
    }

    /**
     * Updates the stored notification time and reschedules all existing important-date alarms
     * to use the new time. Called from the settings screen when the user changes the reminder time.
     */
    suspend fun rescheduleAll(notificationHour: Int, notificationMinute: Int) {
        notificationPreferences.setNotificationTime(notificationHour, notificationMinute)
        dao.getAllWithNotificationEnabled().forEach { date ->
            notificationScheduler.schedule(
                dateId = date.id,
                label = date.label,
                month = date.month,
                day = date.day,
                year = date.year,
                notificationHour = date.notificationHour ?: notificationHour,
                notificationMinute = date.notificationMinute ?: notificationMinute,
            )
        }
    }

    companion object {
        fun normalizeLabel(raw: String): String = raw
            .trim()
            .lowercase()
            .removePrefix("my ")
            .removePrefix("the ")
            .replace(Regex("""\b([\p{L}\d]+)'s\b"""), "$1")
            .replace("'", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

