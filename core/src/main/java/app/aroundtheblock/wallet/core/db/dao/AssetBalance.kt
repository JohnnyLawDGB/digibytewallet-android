package app.aroundtheblock.wallet.core.db.dao

data class AssetBalance(
    val assetId: String,
    val totalQuantity: Long,
    val utxoCount: Int
)
