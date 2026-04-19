package io.digibyte.core.asset

import android.content.Context
import android.util.Log
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.TransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One-shot worker that populates `transactions.assetId` for rows that
 * predated MIGRATION_4_5 (the column was nullable-added in DB v5).
 *
 * Pass 1 — SQL correlated subquery: for every asset-tx row, copy the
 * `asset_id` from its matching UTXO row if one exists. Atomic, idempotent,
 * no memory pressure.
 *
 * Pass 2 — rawBytes fallback: batch-load rows still missing an assetId
 * (their UTXO was spent and purged), decode the OP_RETURN, and use the
 * `metadataCid` from [DecodedAssetHeader] as a stable asset identifier.
 * Rows without a CID stay null (better than mis-attribution).
 *
 * Idempotence guard: SharedPreferences flag keyed by schema version.
 * Interrupt-safe: pass 1 is a single SQL statement; pass 2 commits per-row,
 * so a partial run just resumes on the next call until the batch is empty.
 */
class AssetHistoryBackfill(
    private val context: Context,
    private val transactionDao: TransactionDao,
    private val decoder: DigiAssetDecoder
) {
    private val mutex = Mutex()

    suspend fun runIfNeeded() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(FLAG_KEY, false)) return@withLock

            Log.i(TAG, "Starting asset history backfill")

            try {
                transactionDao.backfillAssetIdFromUtxos()
                Log.i(TAG, "Pass 1 (UTXO join) complete")
            } catch (t: Throwable) {
                Log.e(TAG, "Pass 1 failed, aborting backfill", t)
                return@withLock
            }

            var decoded = 0
            while (true) {
                val batch = try {
                    transactionDao.getUnbackfilledAssetTxsBatch()
                } catch (t: Throwable) {
                    Log.e(TAG, "Pass 2 batch fetch failed", t)
                    break
                }
                if (batch.isEmpty()) break

                var progressedThisBatch = false
                for (tx in batch) {
                    try {
                        val raw = tx.rawBytes ?: continue
                        val opReturn = NativeBridge.getOpReturnData(raw) ?: continue
                        val header = decoder.decode(opReturn) ?: continue
                        val assetId = header.metadataCid ?: continue
                        transactionDao.updateAssetId(tx.txid, assetId)
                        decoded++
                        progressedThisBatch = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Decode failed for txid=${tx.txid}: ${e.message}")
                    }
                }
                // If a batch came back non-empty but we couldn't update any
                // rows (all decode-failed or no rawBytes/CID), bail out to
                // avoid an infinite loop. The remaining rows will stay with
                // assetId=null and simply not appear in per-asset history.
                if (!progressedThisBatch) {
                    Log.i(TAG, "Pass 2 stalled — ${batch.size} rows have no decodable CID, leaving null")
                    break
                }
            }

            Log.i(TAG, "Pass 2 (rawBytes fallback) decoded $decoded rows")
            prefs.edit().putBoolean(FLAG_KEY, true).apply()
            Log.i(TAG, "Backfill complete")
        }
    }

    companion object {
        private const val PREFS_NAME = "dgb_asset_backfill"
        private const val FLAG_KEY = "asset_history_backfill_v5_done"
        private const val TAG = "AssetHistoryBackfill"
    }
}
