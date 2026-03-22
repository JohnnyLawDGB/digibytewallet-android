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

    @Query("DELETE FROM digiid_history WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)
}
