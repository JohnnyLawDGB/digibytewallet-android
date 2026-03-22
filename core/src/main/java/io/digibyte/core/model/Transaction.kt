package io.digibyte.core.model

data class Transaction(
    val txid: String,
    val blockHeight: Long, // 0 = unconfirmed
    val timestamp: Long,   // Unix seconds
    val amount: Long,      // satoshis (positive = received, negative = sent)
    val fee: Long,         // satoshis
    val toAddress: String,
    val fromAddress: String,
    val confirmations: Int,
    val isAssetTx: Boolean = false,
    val rawBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Transaction) return false
        return txid == other.txid
    }
    override fun hashCode() = txid.hashCode()
}
