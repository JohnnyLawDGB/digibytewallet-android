package io.digibyte.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "digiid_history")
data class DigiIdHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val callbackUrl: String,
    val address: String,
    val timestamp: Long,
    val success: Boolean,
    /** Which identity signed this login: "legacy" (m/0'/0/0) or "site" (per-site m/13'). */
    @ColumnInfo(defaultValue = "legacy") val derivation: String = "legacy"
)
