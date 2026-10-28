package app.aroundtheblock.wallet.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.aroundtheblock.wallet.core.db.entity.CachedMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedMessageDao {
    @Query("SELECT * FROM hub_cached_messages WHERE channelId = :channelId ORDER BY timestamp DESC LIMIT :limit")
    fun getMessagesForChannel(channelId: Int, limit: Int = 100): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<CachedMessageEntity>)

    /** A message the server deleted, so the offline fallback cannot show it again. No schema change. */
    @Query("DELETE FROM hub_cached_messages WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM hub_cached_messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM hub_cached_messages")
    suspend fun deleteAll()
}
