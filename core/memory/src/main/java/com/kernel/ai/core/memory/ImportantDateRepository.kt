package com.kernel.ai.core.memory

import com.kernel.ai.core.memory.dao.ImportantDateDao
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportantDateRepository @Inject constructor(
    private val dao: ImportantDateDao,
) {
    fun observeAll(): Flow<List<ImportantDateEntity>> = dao.observeAll()

    suspend fun getAll(): List<ImportantDateEntity> = dao.getAll()

    suspend fun findByLabel(label: String): ImportantDateEntity? =
        dao.findByNormalizedLabel(normalizeLabel(label))

    suspend fun save(
        label: String,
        month: Int,
        day: Int,
        year: Int?,
    ) {
        val trimmedLabel = label.trim()
        dao.insert(
            ImportantDateEntity(
                label = trimmedLabel,
                normalizedLabel = normalizeLabel(trimmedLabel),
                month = month,
                day = day,
                year = year,
            ),
        )
    }

    suspend fun deleteByLabel(label: String): Int = dao.deleteByNormalizedLabel(normalizeLabel(label))

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
