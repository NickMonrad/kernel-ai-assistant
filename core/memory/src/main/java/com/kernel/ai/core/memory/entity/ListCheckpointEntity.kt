package com.kernel.ai.core.memory.entity

import androidx.room.Entity

@Entity(primaryKeys = ["collectionId", "actorId"], tableName = "list_checkpoints")
data class ListCheckpointEntity(
    val collectionId: String,
    val actorId: String,
    val highestContiguousSourceSequence: Long,
)
