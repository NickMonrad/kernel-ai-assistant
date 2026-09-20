package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.ListActorStateEntity
import com.kernel.ai.core.memory.entity.ListAppliedChangeEntity
import com.kernel.ai.core.memory.entity.ListChangeEntity
import com.kernel.ai.core.memory.entity.ListSourceSequenceEntity
import com.kernel.ai.core.memory.entity.ListCheckpointEntity

@Dao
interface ListActorStateDao {
    @Query("SELECT * FROM list_actor_state LIMIT 1")
    suspend fun get(): ListActorStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ListActorStateEntity)
}

@Dao
interface ListSourceSequenceDao {
    @Query("SELECT * FROM list_source_sequences WHERE actorId = :actorId AND collectionId = :collectionId LIMIT 1")
    suspend fun get(actorId: String, collectionId: String): ListSourceSequenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sequence: ListSourceSequenceEntity)
}

@Dao
interface ListCheckpointDao {
    @Query("SELECT * FROM list_checkpoints WHERE collectionId = :collectionId AND actorId = :actorId LIMIT 1")
    suspend fun get(collectionId: String, actorId: String): ListCheckpointEntity?

    /** Every actor's delivery position for one collection; used by snapshot export. */
    @Query("SELECT * FROM list_checkpoints WHERE collectionId = :collectionId")
    suspend fun getAllForCollection(collectionId: String): List<ListCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: ListCheckpointEntity)
}


@Dao
interface ListChangeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: ListChangeEntity)

    @Query("SELECT * FROM list_changes WHERE isPending = 1 ORDER BY logicalClock ASC")
    suspend fun getPending(): List<ListChangeEntity>

    @Query("SELECT COUNT(*) FROM list_changes WHERE isPending = 1")
    fun observePendingCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT * FROM list_changes WHERE changeId = :changeId LIMIT 1")
    suspend fun getById(changeId: String): ListChangeEntity?
    @Query("UPDATE list_changes SET isPending = 0 WHERE changeId IN (:changeIds)")
    suspend fun markPushed(changeIds: List<String>)

    @Query("DELETE FROM list_changes WHERE isPending = 0 AND changeId NOT IN (SELECT changeId FROM list_changes WHERE isPending = 0 ORDER BY logicalClock DESC LIMIT :limit)")
    suspend fun pruneAcknowledged(limit: Int = 512)
}

@Dao
interface ListAppliedChangeDao {
    @Query("SELECT 1 FROM list_applied_changes WHERE changeId = :changeId LIMIT 1")
    suspend fun exists(changeId: String): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(change: ListAppliedChangeEntity)
    @Query("SELECT sourceSequence FROM list_applied_changes WHERE actorId = :actorId AND collectionId = :collectionId ORDER BY sourceSequence ASC")
    suspend fun getSourceSequences(actorId: String, collectionId: String): List<Long>
}
