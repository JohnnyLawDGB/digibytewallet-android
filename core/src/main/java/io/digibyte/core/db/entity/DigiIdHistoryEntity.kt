package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "digiid_history")
data class DigiIdHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val callbackUrl: String,
    val address: String,
    val timestamp: Long,
    val success: Boolean
)
