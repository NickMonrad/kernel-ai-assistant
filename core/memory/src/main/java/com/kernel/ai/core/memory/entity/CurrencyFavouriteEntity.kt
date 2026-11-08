package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "currency_favourites")
data class CurrencyFavouriteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_code") val fromCode: String,
    @ColumnInfo(name = "to_code") val toCode: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
