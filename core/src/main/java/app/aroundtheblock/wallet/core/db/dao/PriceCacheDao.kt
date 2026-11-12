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

    /** Every cached price: Settings → Display → Show prices turned off. */
    @Query("DELETE FROM price_cache")
    suspend fun clear()
}
