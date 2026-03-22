package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.WalletConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletConfigDao {
    @Query("SELECT * FROM wallet_config WHERE id = 1")
    fun observe(): Flow<WalletConfigEntity?>

    @Query("SELECT * FROM wallet_config WHERE id = 1")
    suspend fun get(): WalletConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: WalletConfigEntity)
}
