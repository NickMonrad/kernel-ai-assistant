package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks which messages have been indexed into the sqlite-vec vector store.
 * The auto-generated [rowId] is used as the integer row ID in the vec0 table.
 */
@Entity(
    tableName = "message_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId", unique = true),
        Index("conversationId"),
    ],
)
data class MessageEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val messageId: String,
    val conversationId: String,
)
