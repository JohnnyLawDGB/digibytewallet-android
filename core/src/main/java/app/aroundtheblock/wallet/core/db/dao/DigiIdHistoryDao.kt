package app.aroundtheblock.wallet.core.db.dao

import androidx.room.*
import app.aroundtheblock.wallet.core.db.entity.DigiIdHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DigiIdHistoryDao {
    @Query("SELECT * FROM digiid_history ORDER BY timestamp DESC")
    fun getHistory(): Flow<List<DigiIdHistoryEntity>>

    // The history is a record: nothing reads it to choose which identity signs (Q4 A,
    // IdentityKeyPolicy). Each row's `derivation` says which one did.
    @Insert
    suspend fun insert(entry: DigiIdHistoryEntity)

    @Query("DELETE FROM digiid_history WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)
}
