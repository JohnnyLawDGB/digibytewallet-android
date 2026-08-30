package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.DigiIdHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DigiIdHistoryDao {
    @Query("SELECT * FROM digiid_history ORDER BY timestamp DESC")
    fun getHistory(): Flow<List<DigiIdHistoryEntity>>

    @Insert
    suspend fun insert(entry: DigiIdHistoryEntity)

    /** Grandfather check: has this domain ever completed a login with the legacy identity?
     *  (IdentityKeyPolicy — such a domain must keep the legacy key or the site's account,
     *  bound to that address, becomes unreachable.) */
    @Query("SELECT COUNT(*) FROM digiid_history WHERE domain = :domain AND success = 1 AND derivation = 'legacy'")
    suspend fun countSuccessfulLegacy(domain: String): Int

    @Query("DELETE FROM digiid_history WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)
}
