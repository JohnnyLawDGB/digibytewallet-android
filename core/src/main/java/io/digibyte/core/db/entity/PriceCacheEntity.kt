package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "price_cache")
data class PriceCacheEntity(
    @PrimaryKey val currency: String, // e.g. "USD"
    val pricePerDgb: Double,
    val change24h: Double,
    val source: String, // "coingecko", "binance", "oracle"
    val updatedAt: Long // Unix millis
)
