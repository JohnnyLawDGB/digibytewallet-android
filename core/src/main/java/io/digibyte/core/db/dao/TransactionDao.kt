package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE txid = :txid")
    suspend fun getTransaction(txid: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("UPDATE transactions SET confirmations = :confirmations WHERE txid = :txid")
    suspend fun updateConfirmations(txid: String, confirmations: Int)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
