package app.aroundtheblock.wallet.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `utxos.asset_credit`: whether an asset row's units are backed
 * ([app.aroundtheblock.wallet.core.asset.AssetCredit]).
 *
 * Every existing row starts UNCHECKED. Rows written before this version took a transfer's
 * quantity from its instructions without asking whether the inputs carried those units, and named
 * it from the first input's history, so none of them is trusted as it stands: each one is decided
 * again — from the wallet's own inputs where it can, otherwise by the indexer — and counts only
 * once it has been.
 *
 * The names go too. An existing row's asset id came from the first input's history, which the
 * sender chooses, and the transaction's history label from that same walk; left in place, a forged
 * receive would keep appearing in the real asset's history. So every asset row goes back to its
 * `unresolved:<txid>` placeholder and the transactions that produced asset rows lose their label.
 * Deciding a row again names it: an issuance from its own header on the next sweep, a transfer
 * from its allocation or the indexer.
 *
 * Nothing is deleted and no quantity changes: the hold-out rules keep reading the stored
 * quantities exactly as before.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE utxos ADD COLUMN asset_credit TEXT NOT NULL DEFAULT 'UNCHECKED'")
        db.execSQL("UPDATE transactions SET assetId = NULL WHERE txid IN (SELECT txid FROM utxos WHERE is_asset = 1)")
        db.execSQL("UPDATE utxos SET asset_id = 'unresolved:' || txid WHERE is_asset = 1")
    }
}
