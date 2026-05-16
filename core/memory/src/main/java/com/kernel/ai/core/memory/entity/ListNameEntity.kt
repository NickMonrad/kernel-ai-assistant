package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lists",
    indices = [Index(value = ["name"], unique = true)],
)
data class ListNameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Bumped on rename and whenever any child item changes. */
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
)
