package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block_headers")
data class HeaderEntity(
    @PrimaryKey val hash: String,
    val height: Long,
    val timestamp: Long,
    val previousHash: String,
    val nonce: Long,
    val target: Long
)
