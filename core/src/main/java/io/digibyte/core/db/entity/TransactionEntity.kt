package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val txid: String,
    val blockHeight: Long,
    val timestamp: Long,
    val amount: Long,
    val fee: Long,
    val toAddress: String,
    val fromAddress: String,
    val confirmations: Int,
    val sent: Long = 0L,
    val received: Long = 0L,
    val isAssetTx: Boolean = false,
    val rawBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransactionEntity) return false
        return txid == other.txid
    }
    override fun hashCode() = txid.hashCode()
}
