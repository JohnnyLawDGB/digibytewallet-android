package io.digibyte.core.reconcile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    const val PREFS = "dgb_reconcile"
    const val KEY_LAST_VERSION = "last_version_code_reconciled"

    /** Persisted flag: true iff the most recent attempted reconcile failed
     *  AND the version-gate still says we should run. Cleared on Done.
     *  Survives process death so the UI can surface the banner across
     *  app restarts. */
    const val KEY_LAST_FAILED = "last_attempt_failed"

    /** Give SPV a few seconds to connect peers + settle headers before we
     *  hand the node the wallet's full address set. */
    internal const val STARTUP_DELAY_MS = 10_000L

    @Volatile private var inFlight = false

    /** Hot StateFlow for the failed-reconcile banner. WalletViewModel
     *  collects this and renders a yellow banner + Retry button when true.
     *  Initialised from the persisted pref on first read so a cold
     *  process restart still shows the warning. */
    private val _lastAttemptFailed = MutableStateFlow(false)
    val lastAttemptFailed: StateFlow<Boolean> = _lastAttemptFailed.asStateFlow()

    /** Hydrate the StateFlow from persisted state. Call once on app
     *  startup (before the wallet UI subscribes) so the banner reflects
     *  a failure from a prior process. */
    fun hydrateFailedFlag(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _lastAttemptFailed.value = prefs.getBoolean(KEY_LAST_FAILED, false)
    }

    /** Manually clear the failed flag — invoked after a successful manual
     *  reconcile run from Settings → Recovery → Scan, or after the user
     *  taps "Dismiss" on the banner. */
    fun clearFailedFlag(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LAST_FAILED, false).apply()
        _lastAttemptFailed.value = false
    }

    /**
     * Pure version-gate decision. Exposed for testing and any caller that
     * wants to check without actually running reconcile.
     *
     * Returns true iff [currentVersionCode] is valid (>0) and strictly
     * greater than [lastReconciledVersionCode]. Downgrades and equal
     * versions are no-ops.
     */
    fun shouldRun(lastReconciledVersionCode: Int, currentVersionCode: Int): Boolean =
        currentVersionCode > 0 && lastReconciledVersionCode < currentVersionCode

    /**
     * Production overload. [assetManager] is optional — when provided, the
     * reconcile also refreshes the local utxos table's asset rows so the
     * Assets tab doesn't require an app restart after an import.
     */
    suspend fun runIfNeeded(
        context: Context,
        assetManager: io.digibyte.core.asset.AssetManager? = null,
    ) {
        runIfNeeded(context) { ctx -> ChainReconciliationService(DgbNodeClient(ctx), assetManager) }
    }

    /**
     * Testable overload that lets callers inject a service factory so the
     * network-facing ChainReconciliationService can be stubbed in unit tests.
     * Production call site uses the no-arg overload above.
     */
    internal suspend fun runIfNeeded(
        context: Context,
        serviceFactory: (Context) -> ChainReconciliationService,
    ) {
        if (inFlight) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = currentVersionCode(context)
        val last = prefs.getInt(KEY_LAST_VERSION, 0)
        // Fresh install (no prior baseline): there is no "post-upgrade" discrepancy
        // to fix — the wallet was just created (empty) or restored (restore has its
        // own funds-fetching path). Running the reconcile here means a doomed HTTP
        // attempt that fails on the startup race / empty wallet, sets the failed
        // flag, and raises a false "Balance refresh failed" banner on every new
        // install (the banner then re-appears after dismiss because the failed
        // reconcile retries each launch). Stamp the baseline so genuine FUTURE
        // upgrades still reconcile, and skip now. Also clear any stale failed flag.
        if (last == 0) {
            persistVersion(prefs, current)
            setFailedFlag(prefs, false)
            Log.d(TAG, "fresh install — baseline stamped at v$current, no reconcile")
            return
        }
        if (!shouldRun(last, current)) {
            Log.d(TAG, "no reconcile needed (last=$last current=$current)")
            return
        }
        inFlight = true
        try {
            Log.i(TAG, "auto-reconcile v$last -> v$current; waiting ${STARTUP_DELAY_MS}ms for peers")
            delay(STARTUP_DELAY_MS)
            val service = serviceFactory(context)
            when (val result = service.reconcile()) {
                is ChainReconciliationService.State.Done -> {
                    Log.i(
                        TAG,
                        "reconcile done: imported=${result.txsImported} " +
                            "already=${result.alreadyKnown} " +
                            "utxos=${result.utxosSeenOnChain} " +
                            "addresses=${result.scannedAddresses}"
                    )
                    persistVersion(prefs, current)
                    setFailedFlag(prefs, false)
                }
                is ChainReconciliationService.State.Failed -> {
                    Log.w(TAG, "reconcile failed: ${result.reason} — will retry next launch")
                    setFailedFlag(prefs, true)
                }
                else -> {
                    Log.w(TAG, "reconcile returned unexpected state: $result")
                    setFailedFlag(prefs, true)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post-upgrade reconcile threw", t)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            setFailedFlag(prefs, true)
        } finally {
            inFlight = false
        }
    }

    private fun persistVersion(prefs: SharedPreferences, versionCode: Int) {
        prefs.edit().putInt(KEY_LAST_VERSION, versionCode).apply()
    }

    private fun setFailedFlag(prefs: SharedPreferences, failed: Boolean) {
        prefs.edit().putBoolean(KEY_LAST_FAILED, failed).apply()
        _lastAttemptFailed.value = failed
    }

    private fun currentVersionCode(context: Context): Int = try {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    } catch (t: Throwable) {
        Log.w(TAG, "failed to read versionCode", t)
        0
    }

    /** Visible for tests only — reset in-flight guard. */
    internal fun resetInFlightForTest() {
        inFlight = false
    }
}
