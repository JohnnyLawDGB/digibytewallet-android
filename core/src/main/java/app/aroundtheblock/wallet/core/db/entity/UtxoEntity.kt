package app.aroundtheblock.wallet.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "utxos", primaryKeys = ["txid", "vout"])
data class UtxoEntity(
    val txid: String,
    val vout: Int,
    val scriptPubKey: ByteArray,
    val satoshis: Long,
    val blockHeight: Long,
    @ColumnInfo(name = "is_asset") val isAsset: Boolean = false,
    @ColumnInfo(name = "asset_id") val assetId: String? = null,
    @ColumnInfo(name = "asset_quantity") val assetQuantity: Long = 0,
    val spent: Boolean = false,
    @ColumnInfo(name = "asset_source") val assetSource: String = "BACKEND",
    /** Whether the row's units are backed; see [app.aroundtheblock.wallet.core.asset.AssetCredit].
     *  Only a BACKED or VERIFIED row counts, is sendable, or names its asset. */
    @ColumnInfo(name = "asset_credit") val assetCredit: String = "UNCHECKED",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UtxoEntity) return false
        return txid == other.txid && vout == other.vout
    }
    override fun hashCode() = 31 * txid.hashCode() + vout
}
