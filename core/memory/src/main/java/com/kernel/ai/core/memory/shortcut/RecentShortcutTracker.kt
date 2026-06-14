package com.kernel.ai.core.memory.shortcut

import com.kernel.ai.core.memory.dao.RecentShortcutDao
import com.kernel.ai.core.memory.entity.RecentShortcutEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks recently used shortcut IDs.
 *
 * Records when a user explicitly opens a shortcut from Tools, drawer, or other
 * deliberate tool-launch surfaces. Does NOT record prompt text, personal data,
 * or action payloads.
 *
 * Settings is excluded from recents. The list is capped at [MAX_RECENTS] entries,
 * newest-first, with duplicates deduped.
 */
@Singleton
class RecentShortcutTracker @Inject constructor(
    private val dao: RecentShortcutDao,
) {
    companion object {
        /** Maximum number of recent shortcuts to retain. */
        const val MAX_RECENTS = 5
    }

    /** Observe all recent shortcut IDs, newest first. */
    fun observeAll(): Flow<List<RecentShortcutEntity>> = dao.observeAll()

    /**
     * Record that a shortcut was opened.
     * Deduplicates by replacing the timestamp, then trims to [MAX_RECENTS].
     */
    suspend fun record(id: String) {
        dao.upsert(
            RecentShortcutEntity(
                id = id,
                openedAt = System.currentTimeMillis(),
            )
        )
        dao.trimToLimit(MAX_RECENTS)
    }

    /** Clear all recent shortcuts. */
    suspend fun clear() {
        dao.deleteAll()
    }
}
