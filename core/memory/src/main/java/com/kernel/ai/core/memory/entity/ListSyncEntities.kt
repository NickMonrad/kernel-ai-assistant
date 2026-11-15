package com.kernel.ai.core.memory.entity

import androidx.room.Entity

@Entity(tableName = "list_actor_state")
data class ListActorStateEntity(
    @androidx.room.PrimaryKey val actorId: String,
    val logicalClock: Long = 0L,
)

@Entity(primaryKeys = ["actorId", "collectionId"], tableName = "list_source_sequences")
data class ListSourceSequenceEntity(
    val actorId: String,
    val collectionId: String,
    val sourceSequence: Long,
)


@Entity(
    tableName = "list_changes",
    indices = [androidx.room.Index(value = ["collectionId"]), androidx.room.Index(value = ["isPending"])],
)
data class ListChangeEntity(
    @androidx.room.PrimaryKey val changeId: String,
    val formatVersion: Int,
    val collectionId: String,
    val targetId: String,
    val actorId: String,
    val sourceSequence: Long,
    val logicalClock: Long,
    val stampActorId: String,
    val operation: String,
    val payload: String,
    val isPending: Boolean,
)

@Entity(tableName = "list_applied_changes")
data class ListAppliedChangeEntity(
    @androidx.room.PrimaryKey val changeId: String,
    val collectionId: String,
    val actorId: String,
    val sourceSequence: Long,
)
