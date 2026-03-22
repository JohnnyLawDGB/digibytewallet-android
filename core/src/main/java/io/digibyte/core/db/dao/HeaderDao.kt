package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.HeaderEntity

@Dao
interface HeaderDao {
    @Query("SELECT * FROM block_headers ORDER BY height DESC LIMIT 1")
    suspend fun getLatestHeader(): HeaderEntity?

    @Query("SELECT * FROM block_headers WHERE height = :height")
    suspend fun getHeaderAtHeight(height: Long): HeaderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(headers: List<HeaderEntity>)

    @Query("DELETE FROM block_headers WHERE height < :belowHeight")
    suspend fun pruneBelow(belowHeight: Long)
}
