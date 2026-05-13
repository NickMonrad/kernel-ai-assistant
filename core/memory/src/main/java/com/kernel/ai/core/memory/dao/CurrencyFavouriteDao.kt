package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.CurrencyFavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CurrencyFavouriteDao {
    @Query("SELECT * FROM currency_favourites ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<CurrencyFavouriteEntity>>

    @Query("SELECT COUNT(*) FROM currency_favourites")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CurrencyFavouriteEntity)

    @Query("DELETE FROM currency_favourites WHERE id = :id")
    suspend fun delete(id: String): Int
}
