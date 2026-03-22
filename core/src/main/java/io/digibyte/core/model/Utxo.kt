package io.digibyte.core.model

data class Utxo(
    val txid: String,
    val vout: Int,
    val scriptPubKey: ByteArray,
    val satoshis: Long,
    val blockHeight: Long,
    val isAsset: Boolean = false,
    val assetId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Utxo) return false
        return txid == other.txid && vout == other.vout
    }
    override fun hashCode() = 31 * txid.hashCode() + vout
}
