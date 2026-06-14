package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.FavouriteShortcutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteShortcutDao {
    @Query("SELECT * FROM favourite_shortcuts ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<FavouriteShortcutEntity>>

    @Query("SELECT id FROM favourite_shortcuts")
    suspend fun getAllIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM favourite_shortcuts WHERE id = :id)")
    suspend fun isFavourited(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavouriteShortcutEntity)

    @Query("DELETE FROM favourite_shortcuts WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM favourite_shortcuts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM favourite_shortcuts")
    suspend fun count(): Int
}
