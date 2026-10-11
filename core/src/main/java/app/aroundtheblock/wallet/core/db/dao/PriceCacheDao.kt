package app.aroundtheblock.wallet.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.aroundtheblock.wallet.core.db.entity.PriceCacheEntity

@Dao
interface PriceCacheDao {
    @Query("SELECT * FROM price_cache WHERE currency = :currency")
    suspend fun getPrice(currency: String): PriceCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(price: PriceCacheEntity)
}
