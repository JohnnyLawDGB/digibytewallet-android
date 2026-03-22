package io.digibyte.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.digibyte.core.db.entity.CachedMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedMessageDao {
    @Query("SELECT * FROM hub_cached_messages WHERE channelId = :channelId ORDER BY timestamp DESC LIMIT :limit")
    fun getMessagesForChannel(channelId: Int, limit: Int = 100): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<CachedMessageEntity>)

    @Query("DELETE FROM hub_cached_messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM hub_cached_messages")
    suspend fun deleteAll()
}
