package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallet_config")
data class WalletConfigEntity(
    @PrimaryKey val id: Int = 1, // single row
    val creationTimestamp: Long = 0L,
    val defaultAddressFormat: Int = 2,
    val lastSyncHeight: Long = 0L,
    val fiatCurrency: String = "USD",
    val autoLockTimeoutMs: Long = 60_000L
)
