package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.UtxoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UtxoDao {
    @Query("SELECT * FROM utxos WHERE is_asset = 0 AND spent = 0")
    fun getSpendableDigiByteUtxos(): Flow<List<UtxoEntity>>

    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND spent = 0")
    fun getAssetUtxos(): Flow<List<UtxoEntity>>

    /** One-shot suspend reads used by sendAsset (Flow variants cache too aggressively
     *  when consumed via .first() for single-use selection). */
    @Query("SELECT * FROM utxos WHERE is_asset = 0 AND spent = 0")
    suspend fun getSpendableDigiByteUtxosNow(): List<UtxoEntity>

    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND spent = 0 AND asset_id = :assetId")
    suspend fun getAssetUtxosByIdNow(assetId: String): List<UtxoEntity>

    @Query("SELECT COALESCE(SUM(satoshis), 0) FROM utxos WHERE is_asset = 0 AND spent = 0")
    fun getDigiByteBalance(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(utxos: List<UtxoEntity>)

    @Query("UPDATE utxos SET spent = 1 WHERE txid = :txid AND vout = :vout")
    suspend fun markSpent(txid: String, vout: Int)

    /** Inverse of markSpent — seam for a held branch to restore an asset UTXO's
     *  input after a dead/failed asset-send so it becomes spendable again. Not
     *  wired into any caller yet. */
    @Query("UPDATE utxos SET spent = 0 WHERE txid = :txid AND vout = :vout")
    suspend fun markUnspent(txid: String, vout: Int)

    /** Set the spent flag for an outpoint to an explicit value. Used by the
     *  asset spent-reconcile to make Room's spent state track the sovereign
     *  native UTXO set (both spend on send-confirm AND un-spend if a dropped
     *  send's input returns to the wallet's UTXO set). */
    @Query("UPDATE utxos SET spent = :spent WHERE txid = :txid AND vout = :vout")
    suspend fun setSpent(txid: String, vout: Int, spent: Boolean)

    /** Rewrite the placeholder asset_id of every UTXO row matching [oldAssetId]
     *  once M3 parent-walk resolves it to a real DigiAsset id. Safe no-op if
     *  no rows match (e.g. the placeholder was already replaced). */
    @Query("UPDATE utxos SET asset_id = :newAssetId WHERE asset_id = :oldAssetId")
    suspend fun replaceAssetId(oldAssetId: String, newAssetId: String)

    /** Fetch the current asset_id for a (txid, vout) if any. Used by the
     *  sweep path to avoid clobbering a previously-resolved real asset-id
     *  with a fresh "unresolved:…" placeholder. */
    @Query("SELECT asset_id FROM utxos WHERE txid = :txid AND vout = :vout LIMIT 1")
    suspend fun getAssetIdAt(txid: String, vout: Int): String?

    /** A RESOLVED asset-id for any asset output of this tx (skips "unresolved:…"
     *  placeholders), or null if none is resolved yet. Used to label an activity
     *  row with the asset's real name/symbol instead of a bare "Tokens". */
    @Query("SELECT asset_id FROM utxos WHERE txid = :txid AND is_asset = 1 AND asset_id NOT LIKE 'unresolved:%' LIMIT 1")
    suspend fun getResolvedAssetIdForTx(txid: String): String?

    @Query("DELETE FROM utxos")
    suspend fun deleteAll()

    /** All asset rows (spent + unspent). Used by the SOVEREIGN ownership
     *  reconcile to find phantom rows — asset outputs sitting at addresses the
     *  wallet does not own (e.g. the recipient marker of a send WE made). */
    @Query("SELECT * FROM utxos WHERE is_asset = 1")
    suspend fun getAllAssetUtxosNow(): List<UtxoEntity>

    /** Distinct txids that produced a tracked asset output (spent or unspent) —
     *  covers both asset receives and the owned asset-change output of a send.
     *  Used to label a transaction row as DigiAsset on the activity list. */
    @Query("SELECT DISTINCT txid FROM utxos WHERE is_asset = 1")
    suspend fun getAssetTxids(): List<String>

    /** Delete a single asset UTXO by outpoint. Scoped to is_asset = 1 so a
     *  plain-DGB UTXO is never removed. The reconcile deletes phantom rows one
     *  at a time (their count is tiny), which also sidesteps the SQLite
     *  bound-variable limit an `IN (:keys)` bulk delete would hit.
     *
     *  Returns the number of rows actually deleted (0 or 1) so a caller can
     *  distinguish a real delete from a no-op — e.g. an owned DGB-change
     *  output at the same txid is is_asset = 0 and never matches this query,
     *  so it must not be counted as a removed asset row. */
    @Query("DELETE FROM utxos WHERE is_asset = 1 AND txid = :txid AND vout = :vout")
    suspend fun deleteAssetUtxo(txid: String, vout: Int): Int

    // HAVING SUM > 0: an asset you hold 0 of (e.g. fully sent, leaving only a
    // spent or 0-quantity row) must drop off the Assets tab, not linger as
    // "<name> — 0 held". Also guards against a 0/negative-quantity phantom row.
    @Query("SELECT asset_id as assetId, SUM(asset_quantity) as totalQuantity, COUNT(*) as utxoCount FROM utxos WHERE is_asset = 1 AND spent = 0 GROUP BY asset_id HAVING SUM(asset_quantity) > 0")
    fun getAssetBalances(): Flow<List<AssetBalance>>

    /** All asset rows of a given provenance (unspent + spent). Used by the
     *  native-positive-removal prune, which only touches NATIVE rows. */
    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND asset_source = :source")
    suspend fun getAssetUtxosBySourceNow(source: String): List<UtxoEntity>

    /** The asset row at an outpoint, or null. Used by the non-clobbering
     *  re-tag to preserve spent/quantity/blockHeight. */
    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND txid = :txid AND vout = :vout LIMIT 1")
    suspend fun getAssetUtxoAt(txid: String, vout: Int): UtxoEntity?

    /** Re-tag provenance only — never rewrites quantity/spent/blockHeight. */
    @Query("UPDATE utxos SET asset_source = :source WHERE is_asset = 1 AND txid = :txid AND vout = :vout")
    suspend fun markAssetSource(txid: String, vout: Int, source: String)

    /** Raise a resolved quantity without touching other columns. Callers must
     *  only ever pass a value >= the current one (never downgrade). */
    @Query("UPDATE utxos SET asset_quantity = :quantity WHERE is_asset = 1 AND txid = :txid AND vout = :vout")
    suspend fun updateAssetQuantity(txid: String, vout: Int, quantity: Long)
}
