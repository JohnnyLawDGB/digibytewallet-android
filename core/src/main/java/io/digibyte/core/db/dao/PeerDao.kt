package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.PeerEntity

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    suspend fun getAllPeers(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(peer: PeerEntity)

    @Query("DELETE FROM peers WHERE lastSeen < :before")
    suspend fun pruneOlderThan(before: Long)
}
