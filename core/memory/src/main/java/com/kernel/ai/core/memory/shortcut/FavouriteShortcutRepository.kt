package com.kernel.ai.core.memory.shortcut

import com.kernel.ai.core.memory.dao.FavouriteShortcutDao
import com.kernel.ai.core.memory.entity.FavouriteShortcutEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavouriteShortcutRepository @Inject constructor(
    private val dao: FavouriteShortcutDao,
) {
    /** Observe all favourited shortcut IDs in display order. */
    fun observeAll(): Flow<List<FavouriteShortcutEntity>> = dao.observeAll()

    /** Returns the set of all favourited shortcut IDs. */
    suspend fun getAllIds(): Set<String> = dao.getAllIds().toSet()

    /** Check if a specific shortcut ID is favourited. */
    suspend fun isFavourited(id: String): Boolean = dao.isFavourited(id)

    /** Toggle favourite state for a shortcut ID. Returns true if now favourited, false if unfavourited. */
    suspend fun toggle(id: String): Boolean {
        return if (dao.isFavourited(id)) {
            dao.delete(id)
            false
        } else {
            val nextSortOrder = dao.count()
            dao.insert(
                FavouriteShortcutEntity(
                    id = id,
                    sortOrder = nextSortOrder,
                    addedAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }

    /** Add a favourite. No-op if already favourited. */
    suspend fun add(id: String) {
        if (!dao.isFavourited(id)) {
            val nextSortOrder = dao.count()
            dao.insert(
                FavouriteShortcutEntity(
                    id = id,
                    sortOrder = nextSortOrder,
                    addedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Remove a favourite. No-op if not favourited. */
    suspend fun remove(id: String) {
        dao.delete(id)
    }
}
