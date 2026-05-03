package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kernel.ai.core.memory.entity.StopwatchLapEntity
import com.kernel.ai.core.memory.entity.StopwatchStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StopwatchDao {
    @Query("SELECT * FROM stopwatch_state WHERE id = :id LIMIT 1")
    fun observeState(id: String): Flow<StopwatchStateEntity?>

    @Query("SELECT * FROM stopwatch_laps WHERE stopwatch_id = :stopwatchId ORDER BY lap_number DESC, id DESC")
    fun observeLaps(stopwatchId: String): Flow<List<StopwatchLapEntity>>

    @Query("SELECT * FROM stopwatch_state WHERE id = :id LIMIT 1")
    suspend fun getState(id: String): StopwatchStateEntity?

    @Query("SELECT * FROM stopwatch_laps WHERE stopwatch_id = :stopwatchId ORDER BY lap_number DESC, id DESC")
    suspend fun getLaps(stopwatchId: String): List<StopwatchLapEntity>

    @Query("SELECT MAX(lap_number) FROM stopwatch_laps WHERE stopwatch_id = :stopwatchId")
    suspend fun getMaxLapNumber(stopwatchId: String): Int?

    @Query("SELECT elapsed_ms FROM stopwatch_laps WHERE stopwatch_id = :stopwatchId ORDER BY lap_number DESC, id DESC LIMIT 1")
    suspend fun getLastLapElapsedMs(stopwatchId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: StopwatchStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLap(entity: StopwatchLapEntity): Long

    @Query("DELETE FROM stopwatch_laps WHERE stopwatch_id = :stopwatchId")
    suspend fun deleteLaps(stopwatchId: String)

    @Query("DELETE FROM stopwatch_state WHERE id = :id")
    suspend fun deleteState(id: String)

    @Transaction
    suspend fun resetStopwatch(id: String) {
        deleteLaps(id)
        deleteState(id)
    }
}
