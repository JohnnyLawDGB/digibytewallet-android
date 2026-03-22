package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val address: String,
    val port: Int,
    val lastSeen: Long,
    val services: Long
)
