package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kernel.ai.core.memory.entity.CurrencyFavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CurrencyFavouriteDao {
    @Query("SELECT * FROM currency_favourites ORDER BY sort_order ASC")
    abstract fun observeAll(): Flow<List<CurrencyFavouriteEntity>>

    @Query("SELECT COUNT(*) FROM currency_favourites")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: CurrencyFavouriteEntity)

    @Query("DELETE FROM currency_favourites WHERE id = :id")
    abstract suspend fun delete(id: String): Int

    /** Atomically checks the limit and inserts. Returns false if already at or over [limit]. */
    @Transaction
    open suspend fun insertIfUnderLimit(entity: CurrencyFavouriteEntity, limit: Int): Boolean {
        if (count() >= limit) return false
        insert(entity)
        return true
    }
}
