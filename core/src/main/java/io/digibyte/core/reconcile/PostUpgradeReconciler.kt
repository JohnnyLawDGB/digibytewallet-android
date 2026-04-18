package io.digibyte.core.reconcile

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Silently runs [ChainReconciliationService.reconcile] once per version bump
 * (and once on the first launch of any wallet) so users don't need to know
 * about the manual "Scan for missing funds" button.
 *
 * Rationale: SPV can drop merkleblocks during a sync window (stale peers,
 * cracked bloom filter, flavor/derivation-path upgrades) and once a UTXO is
 * missed, BRPeerManager only listens forward — the wallet will never notice
 * it on its own. Reconcile asks a trusted full node for the authoritative
 * UTXO set on every address we derive and imports anything that was missed.
 *
 * We run it once-per-version because it touches an external node and we don't
 * want to hit it on every launch. The version gate also scopes the trust
 * decision: each release is an implicit "we believe this build is worth
 * reconciling with" moment.
 *
 * Idempotent: persists the reconciled version code only on success so a
 * reconcile that failed (no network, node down) is retried next launch.
 */
object PostUpgradeReconciler {
    private const val TAG = "PostUpgradeReconcile"
    private const val PREFS = "dgb_reconcile"
    private const val KEY_LAST_VERSION = "last_version_code_reconciled"

    /** Give SPV a few seconds to connect peers + settle headers before we
     *  hand the node the wallet's full address set. */
    private const val STARTUP_DELAY_MS = 10_000L

    @Volatile private var inFlight = false

    suspend fun runIfNeeded(context: Context) {
        if (inFlight) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = currentVersionCode(context)
        val last = prefs.getInt(KEY_LAST_VERSION, 0)
        if (current == 0 || last >= current) {
            Log.d(TAG, "no reconcile needed (last=$last current=$current)")
            return
        }
        inFlight = true
        try {
            Log.i(TAG, "auto-reconcile v$last -> v$current; waiting ${STARTUP_DELAY_MS}ms for peers")
            delay(STARTUP_DELAY_MS)
            val service = ChainReconciliationService(DgbNodeClient(context))
            when (val result = service.reconcile()) {
                is ChainReconciliationService.State.Done -> {
                    Log.i(
                        TAG,
                        "reconcile done: imported=${result.txsImported} " +
                            "already=${result.alreadyKnown} " +
                            "utxos=${result.utxosSeenOnChain} " +
                            "addresses=${result.scannedAddresses}"
                    )
                    prefs.edit().putInt(KEY_LAST_VERSION, current).apply()
                }
                is ChainReconciliationService.State.Failed -> {
                    Log.w(TAG, "reconcile failed: ${result.reason} — will retry next launch")
                }
                else -> Log.w(TAG, "reconcile returned unexpected state: $result")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post-upgrade reconcile threw", t)
        } finally {
            inFlight = false
        }
    }

    private fun currentVersionCode(context: Context): Int = try {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    } catch (t: Throwable) {
        Log.w(TAG, "failed to read versionCode", t)
        0
    }
}
