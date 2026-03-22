package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hub_cached_messages")
data class CachedMessageEntity(
    @PrimaryKey val id: Int,
    val channelId: Int,
    val content: String,
    val fromHandle: String? = null,
    val fromAddress: String,
    val timestamp: Long,
    val signature: String
)
