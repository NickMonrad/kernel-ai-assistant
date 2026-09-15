package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lists",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["collectionId"], unique = true),
    ],
)
data class ListNameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Effective local display name; may be a generated collision alias. */
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Presentation/cache timestamp; never sync conflict authority. */
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /** Local overview order. */
    @ColumnInfo(name = "displayOrder") val displayOrder: Int = 0,
    /** Epoch-ms when locally archived, or null if active. */
    val archivedAt: Long? = null,
    /** Opaque globally stable synchronized collection identity. */
    val collectionId: String = java.util.UUID.randomUUID().toString(),
    /** Canonical synchronized title, independent from [name]. */
    val canonicalTitle: String = name,
    /** Generated local-only alias, null when [name] is the canonical title. */
    val localDisplayAlias: String? = null,
    val lifecycle: String = "ACTIVE",
    val titleLogicalClock: Long = 0L,
    val titleStampActorId: String = "",
    val lifecycleLogicalClock: Long = 0L,
    val lifecycleStampActorId: String = "",
)
