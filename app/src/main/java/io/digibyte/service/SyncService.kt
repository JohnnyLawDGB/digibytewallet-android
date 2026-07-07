package io.digibyte.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.digibyte.core.UtxoManager
import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.bridge.NativeCallback
import io.digibyte.core.db.dao.PeerDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.entity.PeerEntity
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.model.SyncState
import io.digibyte.core.isTestnet
import io.digibyte.core.networkSuffix
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Foreground service that drives SPV sync via the C core's BRPeerManager.
 *
 * Lifecycle:
 *  - Started by MainActivity (wallet already exists + unlocked) or from the
 *    onboarding flow once PIN setup completes.
 *  - Calls startForeground() immediately in onStartCommand to satisfy the
 *    Android 5-second foreground-service rule.
 *  - Registers a NativeCallback so that C-thread events are dispatched into
 *    the serviceScope (IO/Default) before touching Room or StateFlow.
 *  - Stopped when the wallet is locked or wiped.
 */
@AndroidEntryPoint
class SyncService : Service() {

    private var syncAlreadyLaunched = false

    /** True only once startSyncWithTor() has reached the peer-manager creation
     *  gate release (markSavedBlocksLoadComplete). startSyncWithTor is launched
     *  once on the first onStartCommand, but it can exit BEFORE releasing the
     *  gate — e.g. the isWalletLoaded() poll times out because the service
     *  started before the wallet finished unlocking. If that happens, the native
     *  gate stays latched shut: the peer manager is never created and every
     *  later kick (watchdog, keepalive, forceReconnect) just defers startSync
     *  forever → permanent 0-peer wedge with no in-process recovery. We track
     *  setup completion separately from syncAlreadyLaunched so a repeat
     *  onStartCommand can RE-RUN startSyncWithTor until the gate is actually
     *  released. @Volatile — written from the serviceScope coroutine, read on
     *  the main thread in onStartCommand. */
    @Volatile private var syncSetupComplete = false

    /** Tracks the startSyncWithTor() coroutine so a repeat onStartCommand can
     *  tell whether a re-run is already in flight before launching another. */
    private var syncSetupJob: Job? = null

    /** Tracks the peer-keepalive coroutine so onStartCommand can resurrect it
     *  when the watchdog re-kicks us after the loop died silently (Doze,
     *  unhandled JNI throwable, SupervisorJob child cancellation). */
    private var keepaliveJob: Job? = null

    /** Wall-clock timestamp of the last keepalive tick. Used by the respawn
     *  check so we detect coroutines that are nominally `isActive=true` but
     *  have been frozen by Doze for so long that the peer-keepalive has
     *  effectively stopped running. Without this, a stuck-in-delay coroutine
     *  never triggers respawn because Job.isActive stays true throughout
     *  Doze suspension. */
    @Volatile private var lastKeepaliveTickMs: Long = 0L

    /** Consecutive poll ticks observing `height at chain tip`. We only flip
     *  to SyncState.Complete after a grace window of stability so the UI
     *  doesn't falsely declare "synced" while merkleblocks for recent blocks
     *  are still in flight. See TIP_GRACE_POLLS. */
    private var atTipConsecutivePolls = 0

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var utxoManager: UtxoManager
    @Inject lateinit var transactionDao: TransactionDao
    @Inject lateinit var peerDao: PeerDao
    @Inject lateinit var assetManager: AssetManager
    @Inject lateinit var assetHistoryBackfill: io.digibyte.core.asset.AssetHistoryBackfill
    @Inject lateinit var torManager: TorManager
    @Inject lateinit var okHttpClient: OkHttpClient

    /** True if Tor proxy was successfully wired before this sync session started. */
    @Volatile private var torProxyActive: Boolean = false
    /** Consecutive poll cycles with 0 peers while Tor proxy is active. */
    private var torReconnectFailures = 0

    /** Tracks BIP158 watchdog so we don't spawn two on a sync restart. */
    private var bip158WatchdogJob: Job? = null

    /**
     * SupervisorJob so that a child coroutine failure never cancels the
     * parent — important because Room insert failures must not tear down sync.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val binder = SyncBinder()

    // ── Public API exposed via Binder ─────────────────────────────────────────

    inner class SyncBinder : Binder() {
        fun getService(): SyncService = this@SyncService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Android 15 (API 35) caps the cumulative runtime of dataSync foreground
     * services at 6 hours per 24-hour window. When the budget is spent the system
     * calls this; if we don't stop promptly it force-crashes us with a
     * RemoteServiceException. We intentionally keep the FGS alive after sync (to
     * hold peers while the app is open), so a long foreground session can reach the
     * cap — drop foreground and stop. Peers reconnect via MainActivity.onResume /
     * the watchdog the next time the user interacts, by which point the budget has
     * reset. No-op on API < 35 (never called).
     */
    override fun onTimeout(startId: Int) {
        android.util.Log.w("SyncService", "dataSync FGS 6h timeout (Android 15) — stopping service to comply")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground within 5 seconds — do it first thing.
        // Android 15 caps dataSync FGS at 6h/24h and can deny the start once the
        // budget is spent (ForegroundServiceStartNotAllowedException); background
        // starts can also be rejected on 12+. If the promotion is denied, stop
        // cleanly rather than crashing (or hitting the "did not call
        // startForeground" kill) — the budget resets when the app is next
        // foregrounded, and onResume / the watchdog re-kick the service then.
        try {
            startForeground(NOTIFICATION_ID, buildNotification(progress = 0f, peerCount = 0))
        } catch (t: Throwable) {
            android.util.Log.e("SyncService", "startForeground denied — stopping service to avoid a crash", t)
            stopSelf()
            return START_NOT_STICKY
        }

        // On repeat onStartCommand (watchdog kick, sticky-restart), resurrect
        // the peer-keepalive coroutine if it died silently. Without this the
        // service stays nominally alive with its foreground notification but
        // no one is restoring peer connections — users see "0 peers forever"
        // while the watchdog fruitlessly re-kicks the same running service.
        if (syncAlreadyLaunched) {
            // If the one-shot sync setup never reached the peer-manager creation
            // gate release (startSyncWithTor exited early — typically the
            // isWalletLoaded() poll timed out because we started before unlock),
            // the native gate is latched shut and no peer manager will ever be
            // built. Re-run startSyncWithTor here so a watchdog/keepalive kick
            // can recover it once the wallet has loaded. Guarded so we never run
            // two setup coroutines at once.
            if (!syncSetupComplete && syncSetupJob?.isActive != true) {
                android.util.Log.w(
                    "SyncService",
                    "sync setup never released the creation gate — re-running startSyncWithTor"
                )
                syncSetupJob = serviceScope.launch { startSyncWithTor() }
            }
            val now = System.currentTimeMillis()
            val stale = lastKeepaliveTickMs > 0L &&
                (now - lastKeepaliveTickMs) > KEEPALIVE_STALE_THRESHOLD_MS
            if (keepaliveJob?.isActive != true) {
                android.util.Log.w("SyncService", "keepalive coroutine not active — respawning")
                keepaliveJob = serviceScope.launch { runPeerKeepalive() }
            } else if (stale) {
                // Coroutine reports active but hasn't ticked in a long time —
                // Doze froze it without cancelling. Cancel the old job so we
                // don't end up with two loops fighting, then respawn.
                val gap = (now - lastKeepaliveTickMs) / 1000L
                android.util.Log.w(
                    "SyncService",
                    "keepalive stale: no tick in ${gap}s — cancelling + respawning"
                )
                keepaliveJob?.cancel()
                lastKeepaliveTickMs = 0L
                keepaliveJob = serviceScope.launch { runPeerKeepalive() }
            }
            return START_STICKY
        }
        syncAlreadyLaunched = true

        // Restore persisted sync state so progress callbacks don't revert
        // "Connected" back to "Syncing 0%" on restart near the chain tip.
        hasReachedSynced = getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
            .getBoolean("has_synced", false)

        // Wire C core → Kotlin before kicking off sync so no events are lost.
        NativeBridge.setCallbackHandler(syncCallback)

        // Launch Tor-aware startup asynchronously. Tracked in syncSetupJob so a
        // repeat onStartCommand can re-run it if it exits before releasing the
        // peer-manager creation gate (see the syncSetupComplete branch above).
        syncSetupJob = serviceScope.launch { startSyncWithTor() }

        // Watchdog for Tor-bootstrap or Tor-SOCKS failure. If Tor never reaches
        // Connected — OR reaches Connected but routes nothing — peers stay at 0
        // and the wallet shows "Connecting…" forever. After TOR_FALLBACK_TIMEOUT_MS
        // with Tor enabled but no peers, this forces a clearnet fallback so the
        // user isn't stranded. Sister to the existing BIP158→bloom watchdog.
        if (torManager.isEnabled) {
            serviceScope.launch { runTorFallbackWatchdog() }
        }

        // Reactively track live Tor state. Whenever Tor reaches Connected — the
        // initial bootstrap OR a user re-enabling it from Settings after a
        // degraded session — re-wire its SOCKS proxy into the C core and clear
        // any stale "Tor unavailable" banner. Without this the banner raised on a
        // bootstrap-timeout degradation stays up forever even after Tor recovers
        // (reported: disable→re-enable reconnects Tor but the banner persists).
        serviceScope.launch {
            torManager.state.collect { st ->
                if (st is TorState.Connected && torManager.isEnabled) {
                    val wasActive = torProxyActive
                    NativeBridge.setSocksProxy("127.0.0.1", st.socksPort)
                    torProxyActive = true
                    torReconnectFailures = 0
                    // On the transition INTO Tor-routing (e.g. the user enables
                    // Tor mid-session), drop the leftover pre-Tor clearnet peers
                    // and re-dial through SOCKS. The C core won't re-route an
                    // already-connected peer when the proxy lands, so without this
                    // those direct connections keep syncing in the clear (an IP
                    // leak) while only new dials use Tor.
                    //
                    // Use stopSync()+startSync(), NOT forceReconnect(): stopSync
                    // is a plain BRPeerManagerDisconnect (drops all peers, keeps
                    // the manager and its in-memory block chain), so startSync
                    // then re-dials through the now-set SOCKS proxy WITHOUT a
                    // recreate. forceReconnect frees and rebuilds the manager,
                    // which re-floors the chain to the wallet's birth checkpoint
                    // and forces a ~480k-block re-sync — making already-confirmed
                    // txs briefly show unconfirmed (device-observed). Off-main
                    // (serviceScope = Dispatchers.Default).
                    if (!wasActive) {
                        android.util.Log.i("SyncService",
                            "Tor active — dropping direct peers, re-dialing through SOCKS (chain preserved)")
                        try {
                            NativeBridge.stopSync()
                            NativeBridge.startSync()
                        } catch (t: Throwable) {
                            android.util.Log.w("SyncService", "Tor-activate reconnect threw", t)
                        }
                    }
                    if (_torFailureActive.value) {
                        _torFailureActive.value = false
                        android.util.Log.i("SyncService",
                            "Tor reconnected — re-wired SOCKS proxy, cleared degradation banner")
                    }
                }
            }
        }

        // Run the v5 per-asset-history backfill exactly once per install.
        // Fast path is a single correlated SQL statement against the utxos
        // table; fallback pass decodes orphaned rows' rawBytes OP_RETURN.
        serviceScope.launch {
            runCatching { assetHistoryBackfill.runIfNeeded() }
                .onFailure { android.util.Log.w("SyncService", "backfill threw", it) }
        }

        // Keep peers alive while app is open — poll every 10s, reconnect aggressively.
        keepaliveJob = serviceScope.launch { runPeerKeepalive() }

        return START_STICKY
    }

    /**
     * Peer-keepalive poll loop. Extracted from onStartCommand so it can be
     * re-launched if the original coroutine dies.
     *
     * The loop body is wrapped in try/catch because it runs for the life of
     * the service and a single uncaught throwable (e.g. a native call
     * returning an unexpected state during a Doze transition) would exit
     * the while loop, leaving the service nominally alive but no longer
     * maintaining peer connectivity — the "permanently stuck with 0 peers"
     * state users have hit after extended backgrounding.
     */
    private suspend fun runPeerKeepalive() = coroutineScope {
        walletManager.walletState.first { it is WalletState.Unlocked }
        var tickCount = 0L
        // Consecutive 10s ticks observed with 0 peers. The light reconnect
        // (re-inject + startSync) can't dig a stuck manager out, so after
        // ZERO_PEER_RECREATE_THRESHOLD ticks we escalate to a clean recreate.
        var zeroPeerStreak = 0
        while (isActive) {
            delay(10_000L)
            // Stamp every tick so onStartCommand can detect a frozen-by-Doze
            // coroutine that's still nominally active and respawn it.
            lastKeepaliveTickMs = System.currentTimeMillis()
            tickCount++
            // Every 3rd tick (~30s), refresh asset UTXOs against the node's
            // authoritative listunspent view. SPV bloom filters are known to
            // miss asset transactions (cracked-filter / merkleblock-drop
            // conditions documented in project memory), so we can't rely on
            // onAssetDetected alone — a manual Scan tap shouldn't be required
            // just to see a new asset land. 30s is a reasonable cadence: DGB
            // blocks come every ~15s, so this catches new asset receives
            // within ~2 blocks while not hammering the backend. Skipped when
            // peers=0 (we're offline; refresh would fail anyway).
            if (tickCount % 3L == 0L && NativeBridge.getPeerCount() > 0) {
                launch {
                    runCatching { assetManager.refreshAssetUtxosFromNetwork() }
                        .onFailure { android.util.Log.d("SyncService", "asset refresh threw", it) }
                }
                // Sovereign companion to the backend refresh above: re-scan
                // every wallet-known tx via the Kotlin DigiAssetDecoder. Even
                // if the backend is down (or we're running pure-SPV), asset
                // UTXOs surface as long as SPV delivered the raw tx.
                launch {
                    runCatching { assetManager.sweepKnownTransactionsForAssets() }
                        .onFailure { android.util.Log.d("SyncService", "native sweep threw", it) }
                }
            }
            try {
                val peers = NativeBridge.getPeerCount()
                if (peers == 0) {
                    // If Tor proxy is active and we've failed to connect for
                    // several cycles, the SOCKS port is likely dead (Tor daemon
                    // crashed but kmp-tor state wasn't updated). Clear the proxy
                    // so the C core can connect directly, then try to restart Tor.
                    if (torProxyActive) {
                        torReconnectFailures++
                        if (torReconnectFailures >= MAX_TOR_RECONNECT_FAILURES) {
                            android.util.Log.w("SyncService",
                                "Tor proxy appears dead ($torReconnectFailures failures) — clearing proxy, connecting directly")
                            // Stop the daemon first so start() actually restarts
                            // (otherwise start() sees Connected state and returns
                            // the stale port immediately).
                            torManager.stop()
                            NativeBridge.clearSocksProxy()
                            torProxyActive = false
                            torReconnectFailures = 0
                            // Surface the degradation to the UI so the user knows
                            // they're no longer routed through Tor — same banner
                            // the bootstrap-failure watchdog raises.
                            _torFailureActive.value = true
                            // Don't auto-restart Tor — just stay on direct connections.
                            // The user can re-enable Tor from Settings if they want to
                            // try again. Auto-restart would re-set the proxy and kill
                            // the working direct connections.
                            android.util.Log.i("SyncService", "Continuing without Tor — user can re-enable from Settings")
                        }
                    }
                    // Workflow guard: if Tor is enabled and still coming up (proxy
                    // not yet wired and we haven't degraded), do NOT connect peers
                    // yet. They would dial DIRECT before the SOCKS proxy is set —
                    // leaking the IP — and the C core won't re-route an
                    // already-connected peer when setSocksProxy lands later. Wait
                    // for the Tor-state observer / startSyncWithTor to wire the proxy
                    // (only emitted at bootstrap 100%), then connect through it.
                    val torComingUp = torManager.isEnabled && !torProxyActive && !_torFailureActive.value
                    if (torComingUp) {
                        android.util.Log.d("SyncService",
                            "Tor enabled but proxy not ready — deferring peer connect to avoid a direct-before-Tor leak")
                    } else {
                        zeroPeerStreak++
                        if (zeroPeerStreak >= ZERO_PEER_RECREATE_THRESHOLD) {
                            android.util.Log.w("SyncService",
                                "0 peers for $zeroPeerStreak cycles — light reconnect isn't recovering, " +
                                "forcing a clean peer-manager recreate")
                            try { NativeBridge.forceReconnect() } catch (_: Throwable) {}
                            zeroPeerStreak = 0
                        }
                        android.util.Log.i("SyncService", "No peers connected, re-injecting bloom peers and reconnecting")
                        injectBloomPeers()
                        NativeBridge.startSync()
                    }
                } else {
                    zeroPeerStreak = 0
                    if (torProxyActive) torReconnectFailures = 0
                    // Peers connected but sync may have stalled (download peer
                    // disconnected, remaining peers aren't driving the sync).
                    // Kick startSync to reassign a download peer.
                    if (!hasReachedSynced && NativeBridge.getSyncProgress() < 1.0f) {
                        NativeBridge.startSync()
                    }
                }
                // If peers are connected and we have a block height but
                // onSyncComplete never fired (new wallet at chain tip),
                // mark as synced so the UI shows "Connected".
                if (!hasReachedSynced) {
                    val height = NativeBridge.getLastBlockHeight()
                    val estHeight = NativeBridge.getEstimatedBlockHeight()
                    // Push sync progress to UI
                    if (height > 0 && estHeight > 0 && height < estHeight - 5) {
                        val progress = height.toFloat() / estHeight.toFloat()
                        walletManager.updateSyncState(
                            io.digibyte.core.model.SyncState.Syncing(progress, height)
                        )
                    }
                    // If we're at the chain tip, mark complete (don't require peers > 0 —
                    // peers may connect, sync blocks, and disconnect between polls).
                    //
                    // Sanity floor: the highest hardcoded checkpoint in
                    // BRChainParams.h is block 23,187,000 — any real chain tip
                    // must be at or past that. If connected peers are all
                    // reporting a stale/low tip (e.g. a freshly-reconnected
                    // cohort that hasn't caught up to their peers' latest
                    // blocks yet), `estHeight` will be below this floor and
                    // we must NOT declare sync complete. Doing so stops the
                    // bloom-filter rescan and strands user transactions in
                    // blocks we never actually scanned — the "recovered
                    // wallet shows $0.00 forever" symptom.
                    //
                    // Bump LATEST_CHECKPOINT_HEIGHT when the submodule adds
                    // a newer BRMainNetCheckpoints entry.
                    val atRealTip = height >= LATEST_CHECKPOINT_HEIGHT &&
                                    height >= estHeight - 5
                    if (!atRealTip) atTipConsecutivePolls = 0
                    if (height > 0 && estHeight > 0 && atRealTip) {
                        // Require TIP_GRACE_POLLS consecutive at-tip observations
                        // before declaring complete. Header height can reach the
                        // chain tip while per-block merkleblocks matching the
                        // bloom filter are still being delivered; premature
                        // Complete state causes the UI to show stale balance
                        // until the user restarts the app. Real-world repro
                        // observed 2026-04-19: wallet marked complete while
                        // the block containing an outgoing spend was still
                        // being scanned, leaving the wallet showing a balance
                        // ~2 DGB higher than actual on-chain state.
                        atTipConsecutivePolls++
                        if (atTipConsecutivePolls >= TIP_GRACE_POLLS) {
                            hasReachedSynced = true
                            walletManager.updateSyncState(io.digibyte.core.model.SyncState.Complete)
                            getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                                .edit().putBoolean("has_synced", true).apply()
                            android.util.Log.i(
                                "SyncService",
                                "At chain tip (height=$height est=$estHeight) for " +
                                    "${atTipConsecutivePolls * 10}s — marking complete"
                            )
                        }
                    } else if (height > 0 && estHeight > 0 &&
                               height >= estHeight - 5 &&
                               height < LATEST_CHECKPOINT_HEIGHT) {
                        // Peers claim we're at tip but the tip is below the
                        // known checkpoint floor. Don't mark complete; log
                        // once per poll for diagnostic visibility.
                        android.util.Log.i("SyncService",
                            "peers report tip=$estHeight, below checkpoint floor " +
                            "$LATEST_CHECKPOINT_HEIGHT — NOT marking complete, " +
                            "continuing to sync past stale peers")
                        // Persist transactions
                        serviceScope.launch(Dispatchers.IO) {
                            val txData = NativeBridge.getSerializedTransactions()
                            if (txData != null) {
                                val hex = bytesToHex(txData)
                                getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                                    .edit().putString("saved_transactions", hex).apply()
                            }
                        }
                    }
                }
                updateNotification(NativeBridge.getSyncProgress(), peers)
                } catch (t: Throwable) {
                    // Swallow and continue. Losing a single 10s tick is fine;
                    // killing the poll forever is not. Log with stack so the
                    // underlying issue is still visible in bug reports.
                    android.util.Log.e("SyncService", "peer-keepalive tick threw — continuing", t)
                }
            }
        }

    /**
     * BIP 158 watchdog. The wallet defaults to SyncMode.BOTH (bloom +
     * compact filters in parallel); COMPACT_FILTERS_ONLY is user-selectable
     * for max address privacy. But the compact-filter
     * peer pool is small (the seeder currently advertises ~3 peers), so
     * if all of them are unreachable through Tor or down, the wallet
     * would otherwise sit "Connecting…" forever.
     *
     * This watchdog waits BIP158_FALLBACK_TIMEOUT_MS after sync start; if
     * the cfheaders chain hasn't progressed past the configured birth
     * height, it flips syncMode to BLOOM_ONLY in the C core and pushes a
     * bloom filterload to every connected peer (NativeBridge.fallbackToBloom).
     * The Kotlin StateFlow `bloomFallbackActive` flips true so the UI can
     * surface a "privacy degraded" banner. We DO NOT persist this choice —
     * next launch tries filters first again.
     */
    private fun startBip158Watchdog(birthHeight: Long) {
        bip158WatchdogJob?.cancel()
        // Polls every BIP158_WATCHDOG_POLL_MS, falls back if the cfheaders
        // chain isn't keeping pace with the block chain. Three exit cases:
        //   1. Mode flipped to BLOOM (manual override or already fallen back)
        //   2. cfTip caught up to within 100 blocks of blockTip → healthy
        //   3. blockTip is meaningfully ahead AND no cf progress between
        //      polls → fallback to bloom.
        //
        // A one-shot timer at +120s isn't enough: at startup blockTip is
        // often still at the saved-blocks tip (headers haven't synced yet)
        // and cfTip matches it after the persisted chain restore, so the
        // gap looks healthy. Headers catch up later, opening a gap the
        // one-shot already missed. The poll fires until one of the exit
        // conditions holds.
        //
        // Must be invoked AFTER NativeBridge.startSync(); otherwise the
        // peer manager doesn't exist and getCFChainTipHeight returns 0.
        bip158WatchdogJob = serviceScope.launch {
            val startedAt = System.currentTimeMillis()
            var lastCfTip = try { NativeBridge.getCFChainTipHeight() } catch (_: Throwable) { 0 }
            val cfTipAtStart = lastCfTip
            val blockTipAtStart = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L }
            // Per-poll block-progress tracking so the watchdog can tell "headers
            // still importing toward the tip" (stay on filters) from "block sync
            // has stalled" (fall back so bloom can progress).
            var lastBlockTip = blockTipAtStart
            var lastBlockProgressMs = startedAt
            // Re-anchor recovery is attempted at most once per sync session so a
            // poll landing before the first re-anchored cfheaders append (cfTip
            // not yet jumped) can't re-fire it every poll.
            var reanchoredThisSession = false
            // Wall-clock of the last re-anchor, for the rebuild grace window. A
            // re-anchor frees the stuck chain (getCFChainTipHeight() then reads 0
            // until the first cfheaders response rebuilds it); the grace keeps the
            // watchdog from reading that transient 0 as "dead" and degrading to bloom.
            var reanchorAtMs = 0L
            // Transient block-stall recovery: drop to bloom so any peer extends the
            // chain, then switch back to compact filters once caught up. Capped per
            // session so a flaky connection can't flap bloom↔filters forever.
            var blockStallRecoveries = 0
            var bloomRecoveryActive = false
            while (true) {
                kotlinx.coroutines.delay(BIP158_WATCHDOG_POLL_MS)
                val mode = try { NativeBridge.getSyncMode() } catch (_: Throwable) {
                    NativeBridge.SyncMode.BLOOM_ONLY
                }
                if (mode == NativeBridge.SyncMode.BLOOM_ONLY) {
                    if (bloomRecoveryActive) {
                        // We dropped to bloom to recover a block-header stall. Wait for
                        // block sync to catch up to the network tip, then switch back to
                        // compact filters and resume monitoring (cfTip is preserved
                        // across the switch — fallbackToBloom never freed the chain).
                        val bTip = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L }
                        val bEst = try { NativeBridge.getEstimatedBlockHeight() } catch (_: Throwable) { 0L }
                        if (bEst > 0L && bTip >= bEst - BLOCK_CATCHUP_GRACE) {
                            try {
                                NativeBridge.setSyncMode(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY)
                            } catch (t: Throwable) {
                                android.util.Log.e("SyncService", "BIP158 watchdog: switch-back to filters threw", t)
                            }
                            bloomRecoveryActive = false
                            lastBlockTip = bTip
                            lastBlockProgressMs = System.currentTimeMillis()
                            android.util.Log.i("SyncService",
                                "BIP158 watchdog: block sync caught up via bloom — switching back to " +
                                "compact filters (recovery $blockStallRecoveries/$MAX_BLOCK_STALL_RECOVERIES)")
                        }
                        continue   // switched back, or still catching up — keep polling
                    }
                    android.util.Log.i("SyncService",
                        "BIP158 watchdog: mode is BLOOM_ONLY, stopping poll")
                    return@launch
                }
                val cfTipNow = try { NativeBridge.getCFChainTipHeight() } catch (_: Throwable) { 0 }
                val blockTip = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L }
                val gap = blockTip - cfTipNow.toLong()
                val elapsedMs = System.currentTimeMillis() - startedAt
                val cfAdvancedSinceStart = cfTipNow > cfTipAtStart

                // Healthy = cfTip has actually moved past where we started AND
                // is keeping pace with blockTip. gap<=100 with cfTip pinned to
                // the saved-blocks tip is "stuck at restore," not "synced."
                if (gap <= 100 && cfAdvancedSinceStart) {
                    android.util.Log.i("SyncService",
                        "BIP158 watchdog: healthy (cfTip $cfTipAtStart→$cfTipNow, " +
                        "blockTip=$blockTip, gap=$gap, after ${elapsedMs}ms)")
                    return@launch
                }

                val advanced = cfTipNow > lastCfTip
                lastCfTip = cfTipNow

                val nowMs = System.currentTimeMillis()
                // Per-poll block progress — NOT since-start. The post-first-sync
                // bloom rescan resets blockTip to a checkpoint BELOW its start
                // value, so a since-start check misreads an actively-climbing
                // rescan as "stalled." Refresh the progress timestamp on each climb.
                val blockClimbing = blockTip > lastBlockTip
                if (blockClimbing) lastBlockProgressMs = nowMs
                lastBlockTip = blockTip

                // cfheaders is actively riding the header chain — healthy.
                if (advanced) {
                    // Filters recovered: DigiDollar detection is live again (#19).
                    if (_digiDollarDetectionDegraded.value) {
                        _digiDollarDetectionDegraded.value = false
                        android.util.Log.i("SyncService",
                            "BIP158 watchdog: cfheaders resumed — DigiDollar detection restored")
                    }
                    android.util.Log.d("SyncService",
                        "BIP158 watchdog: progressing (cfTip=$cfTipNow, blockTip=$blockTip, " +
                        "gap=$gap, elapsed=${elapsedMs}ms) — keeping poll alive")
                    continue
                }

                // cfheaders didn't advance this poll. Whether that's a failure
                // depends on block-header sync. cfheaders can only advance once
                // headers climb ABOVE the filter frontier (cfTip); the bloom
                // rescan resets blockTip far BELOW the persisted cfTip, so on a
                // deep-behind wallet headers take minutes to climb back. Staying
                // on filters while headers import lets cfheaders ride along once
                // they pass the frontier — abandoning here is the bug that made
                // BIP158 always fall back.
                val estHeight = try { NativeBridge.getEstimatedBlockHeight() } catch (_: Throwable) { 0L }
                val blocksCaughtUp = estHeight > 0L && blockTip >= estHeight - BLOCK_CATCHUP_GRACE

                if (!blocksCaughtUp) {
                    val stalledMs = nowMs - lastBlockProgressMs
                    if (stalledMs < BIP158_FALLBACK_TIMEOUT_MS) {
                        android.util.Log.d("SyncService",
                            "BIP158 watchdog: header sync still catching up to tip " +
                            "(blockTip=$blockTip, est=$estHeight, cfTip=$cfTipNow) — " +
                            "staying on filters (elapsed=${elapsedMs}ms)")
                        continue
                    }
                    if (blockStallRecoveries < MAX_BLOCK_STALL_RECOVERIES) {
                        blockStallRecoveries++
                        android.util.Log.w("SyncService",
                            "BIP158 watchdog: block sync stalled below tip for ${stalledMs}ms " +
                            "(blockTip=$blockTip, est=$estHeight) — bloom recovery " +
                            "$blockStallRecoveries/$MAX_BLOCK_STALL_RECOVERIES, will retry filters once caught up")
                        try { NativeBridge.fallbackToBloom() } catch (t: Throwable) {
                            android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                        }
                        bloomRecoveryActive = true
                        lastBlockProgressMs = System.currentTimeMillis()   // fresh stall window
                        continue   // NO banner — recover via bloom, switch back once caught up
                    }
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: block sync stalled $blockStallRecoveries times — " +
                        "staying on bloom for the session (blockTip=$blockTip, est=$estHeight)")
                    try {
                        NativeBridge.fallbackToBloom()
                        _bloomFallbackActive.value = true
                        // Headers can't advance here, so filters can't either — bloom is
                        // the only way to keep syncing. But it's P2TR-blind, so warn a DD
                        // holder that DigiDollar detection is degraded this session (#19);
                        // filters retry on next launch and a rescan re-credits anything missed.
                        if (NativeBridge.getDigiDollarBalance() > 0L) {
                            _digiDollarDetectionDegraded.value = true
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                    }
                    return@launch
                }

                // Headers are caught up to the network tip but cfheaders still
                // isn't advancing. Before falling back to bloom, try a one-time
                // re-anchor: a legacy wallet can have cfTip persisted far below the
                // block floor (the gap was never re-downloaded), which retention
                // can't bridge. Re-anchoring discards the stuck chain and restarts
                // filters at the floor. The skipped gap was already bloom-scanned —
                // gated on hasReachedSynced, which is that guarantee.
                if (elapsedMs >= BIP158_FALLBACK_TIMEOUT_MS) {
                    // Bloom (BIP37) never matches P2TR, so degrading to it blinds
                    // the wallet to DigiDollar (issue #19). If DD is held, prefer
                    // staying on filters over the silent-loss bloom degrade.
                    val hasDigiDollar = try {
                        NativeBridge.getDigiDollarBalance() > 0L
                    } catch (_: Throwable) {
                        false
                    }
                    when (decidePostTimeoutAction(
                        hasReachedSynced, reanchoredThisSession, nowMs - reanchorAtMs, hasDigiDollar,
                    )) {
                        PostTimeoutAction.REANCHOR -> {
                            val reanchored = try {
                                NativeBridge.reanchorCompactFilterChainAtFloor()
                            } catch (t: Throwable) {
                                android.util.Log.e("SyncService", "BIP158 watchdog: re-anchor threw", t)
                                false
                            }
                            if (reanchored) {
                                reanchoredThisSession = true
                                reanchorAtMs = nowMs
                                // Kotlin owns SharedPreferences: drop the stale chain so a
                                // kill before the first re-anchored append can't restore
                                // the stuck cfTip.
                                getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                                    .edit().remove("saved_filter_headers").apply()
                                android.util.Log.i("SyncService",
                                    "BIP158 watchdog: re-anchored filter chain at block floor " +
                                    "(cfTip was $cfTipNow, below floor) — staying on filters")
                                continue
                            }
                            // re-anchor returned false (cfTip not below the floor) — the
                            // deficit isn't the problem. Mark it exhausted (backdate past
                            // the grace window) so subsequent polls don't re-invoke the JNI
                            // every 15s and instead settle into the bloom / stay-on-filters
                            // decision below (#19 review — matters only for DD wallets,
                            // which keep polling; non-DD falls to bloom and ends this poll).
                            reanchoredThisSession = true
                            reanchorAtMs = nowMs - REANCHOR_GRACE_MS
                        }
                        PostTimeoutAction.AWAIT_REANCHOR -> {
                            // The re-anchor freed the stuck chain; getCFChainTipHeight()
                            // reads 0 until the first cfheaders response lazily rebuilds
                            // it. Don't read that rebuild window as a dead chain — keep
                            // polling until the append lands (caught by the `advanced`
                            // check above) or the grace window expires.
                            android.util.Log.d("SyncService",
                                "BIP158 watchdog: awaiting first cfheaders append after " +
                                "re-anchor (${nowMs - reanchorAtMs}/${REANCHOR_GRACE_MS}ms) — " +
                                "staying on filters")
                            continue
                        }
                        PostTimeoutAction.STAY_ON_FILTERS_DD -> {
                            // handled by the DigiDollar guard below (shared with
                            // the re-anchor-returned-false convergence)
                        }
                        PostTimeoutAction.FALLBACK_BLOOM -> {
                            // fall through to the bloom degrade below
                        }
                    }
                    // DigiDollar held: never degrade to the P2TR-blind bloom path
                    // (issue #19). Keep retrying filters and warn once; nothing is
                    // missed because we never leave the filter set.
                    if (hasDigiDollar) {
                        if (!_digiDollarDetectionDegraded.value) {
                            android.util.Log.w("SyncService",
                                "BIP158 watchdog: cfheaders stalled (gap=$gap, cfTip=$cfTipNow) " +
                                "but wallet holds DigiDollar — staying on filters; bloom is " +
                                "P2TR-blind and would make DigiDollar undetectable")
                        }
                        _digiDollarDetectionDegraded.value = true
                        continue
                    }
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: headers caught up (blockTip=$blockTip) but no " +
                        "cfheaders progress after ${elapsedMs}ms (cfTip stuck at $cfTipNow, " +
                        "gap=$gap) — falling back to bloom")
                    try {
                        NativeBridge.fallbackToBloom()
                        _bloomFallbackActive.value = true
                    } catch (t: Throwable) {
                        android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                    }
                    return@launch
                }
                android.util.Log.d("SyncService",
                    "BIP158 watchdog: gap=$gap, cfTip stuck at $cfTipNow while " +
                    "blockTip=$blockTip (caught up) — awaiting cf progress")
            }
        }
    }

    /**
     * Tor watchdog. Runs in parallel with startSyncWithTor(). If Tor was
     * enabled but the wallet is still at 0 peers after TOR_FALLBACK_TIMEOUT_MS,
     * force a clearnet fallback: stop the daemon, clear the C-core SOCKS
     * proxy, set torProxyActive=false, raise torFailureActive=true (for the
     * UI banner), then re-inject peers + startSync so the connection attempts
     * actually go direct. Covers both (a) Tor never reaching Connected at
     * all (kmp-tor hung in Starting forever), and (b) Tor reaching Connected
     * but routing through SOCKS still failing to dial any peers.
     *
     * Exits silently once peers > 0 — the bloom-fallback watchdog already
     * handles BIP158→bloom; this one is solely about clearnet degradation.
     */
    private suspend fun runTorFallbackWatchdog() {
        val startedAt = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(10_000L)
            // Healthy — peers connected. Nothing more to do.
            if (NativeBridge.getPeerCount() > 0) return
            // User toggled Tor off via Settings; let the keepalive path handle it.
            if (!torManager.isEnabled) return

            val elapsedMs = System.currentTimeMillis() - startedAt
            if (elapsedMs < TOR_FALLBACK_TIMEOUT_MS) continue

            android.util.Log.w(
                "SyncService",
                "Tor watchdog: peers still 0 after ${elapsedMs}ms with tor_enabled — " +
                "forcing clearnet fallback so the user isn't stranded"
            )
            try {
                torManager.stop()
            } catch (t: Throwable) {
                android.util.Log.w("SyncService", "Tor watchdog: stop threw", t)
            }
            NativeBridge.clearSocksProxy()
            torProxyActive = false
            torReconnectFailures = 0
            _torFailureActive.value = true
            injectBloomPeers()
            NativeBridge.startSync()
            return
        }
    }

    /**
     * If Tor is enabled, attempt to start Tor and wire the SOCKS5 proxy before
     * calling startSync(). If Tor fails or is disabled, clear any stale proxy
     * and start sync directly — graceful degradation is critical.
     *
     * NEVER blocks sync on Tor failure.
     */
    private suspend fun startSyncWithTor() {
        if (torManager.isEnabled) {
            val torResult = torManager.start()
            if (torResult is TorState.Connected) {
                // Wire the SOCKS5 proxy into the C core before connecting to peers.
                NativeBridge.setSocksProxy("127.0.0.1", torResult.socksPort)
                torProxyActive = true
                // Tor came up cleanly — clear any stale "Tor unavailable" banner
                // left over from a previous degraded session.
                _torFailureActive.value = false
            } else {
                // Tor failed to start — clear any stale proxy, surface the
                // failure to the UI banner, fall through to clearnet sync.
                android.util.Log.w(
                    "SyncService",
                    "Tor start failed: $torResult — falling back to clearnet"
                )
                NativeBridge.clearSocksProxy()
                torProxyActive = false
                _torFailureActive.value = true
            }
        } else {
            // Tor disabled — ensure no stale proxy from a previous session.
            NativeBridge.clearSocksProxy()
            torProxyActive = false
        }

        // Wait for the C core wallet to be fully initialized.
        // The Kotlin WalletState may be Unlocked before the native wallet
        // is ready (race between createWalletFromBytes and SyncService startup).
        // Poll isWalletLoaded() which checks g_wallet != NULL in the C core.
        var waitCount = 0
        while (!NativeBridge.isWalletLoaded()) {
            delay(500L)
            waitCount++
            if (waitCount > 120) { // 60 seconds max wait
                // Return WITHOUT marking syncSetupComplete — the creation gate
                // stays unreleased, so the next onStartCommand kick (watchdog/
                // keepalive) re-runs this coroutine and tries again once the
                // wallet has finished unlocking. Previously this gave up
                // permanently and wedged the wallet at 0 peers forever.
                android.util.Log.w(
                    "SyncService",
                    "Wallet not loaded after 60s — will retry on next kick (gate not yet released)"
                )
                return
            }
        }
        android.util.Log.i("SyncService", "Wallet ready, starting sync (waited ${waitCount * 500}ms)")

        // Load saved blocks and peers from previous session before syncing
        val prefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
        val savedBlocks = prefs.getString("saved_blocks", null)
        val savedPeers = prefs.getString("saved_peers", null)

        if (savedBlocks != null) {
            val blockBytes = savedBlocks.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val loaded = NativeBridge.loadSavedBlocks(blockBytes)
            android.util.Log.i("SyncService", "Loaded $loaded saved blocks from disk")
            // Seed the monotonic persistence guard with the on-disk tip so the
            // first save this session can't overwrite a higher persisted window
            // with a lower one — the regression this guard exists to prevent.
            val onDiskTip = parseSavedBlocksTopHeight(blockBytes)
            if (onDiskTip > prefs.getLong("saved_blocks_tip", 0L)) {
                prefs.edit().putLong("saved_blocks_tip", onDiskTip).apply()
            }
        }
        if (savedPeers != null) {
            val peerBytes = savedPeers.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val loaded = NativeBridge.loadSavedPeers(peerBytes)
            android.util.Log.i("SyncService", "Loaded $loaded saved peers from disk")
        }

        // Release the peer-manager creation gate now that saved blocks/peers are
        // in the C core. Until this, any racing early startSync (onResume, the
        // active-screen wake, the keepalive) is deferred — otherwise it would
        // build the manager with no saved blocks and floor the chain at the
        // birth checkpoint (~480k-block re-sync every launch). Called
        // unconditionally so fresh wallets (no saved blocks) also proceed.
        NativeBridge.markSavedBlocksLoadComplete()

        // The native creation gate is now released — peer-manager creation is
        // permitted. Mark setup complete so onStartCommand stops re-running this
        // coroutine on subsequent kicks. Set it the instant the gate opens (not
        // at end of function) so even if later setup steps throw, the wedge-
        // recovery re-run loop doesn't fire pointlessly.
        syncSetupComplete = true

        // For a wallet that has synced before and is resuming at the saved tip,
        // suppress the one-time post-first-sync rescan — it re-floors the chain
        // to the birth checkpoint (~480k-block re-download) to catch txs a
        // from-scratch header-only sync would miss, but a resuming wallet
        // already did that scan in a prior session. Fresh wallets (!has_synced)
        // still get the rescan.
        if (hasReachedSynced) {
            NativeBridge.markInitialSyncDone()
        }

        // Inject bloom-capable peers from the seeder API before starting sync.
        // This ensures the wallet has multiple bloom peers to try, not just digiscope.me.
        injectBloomPeers()

        // ─── BIP 158 privacy-first sync ─────────────────────────────────────────
        // Default sync mode is BOTH (bloom + compact filters in parallel) for new
        // installs and any user who hasn't explicitly chosen otherwise;
        // COMPACT_FILTERS_ONLY (addresses never leave the device) is selectable.
        // A 120s watchdog falls back to BLOOM_ONLY for THIS session
        // if filter peers don't make progress; the choice resets on next launch
        // so we try filters again. Users can override in Settings → Sync Mode.
        val settings = getSharedPreferences("dgb_settings", MODE_PRIVATE)
        val syncMode = settings.getInt("sync_mode", NativeBridge.SyncMode.BOTH)
        NativeBridge.setSyncMode(syncMode)
        if (syncMode != NativeBridge.SyncMode.BLOOM_ONLY) {
            val savedFilters = prefs.getString("saved_filter_headers", null)
            if (savedFilters != null) {
                val bytes = savedFilters.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val ok = NativeBridge.setCompactFilterChain(bytes)
                android.util.Log.i("SyncService",
                    "BIP158: restored filter chain (${bytes.size} bytes, ok=$ok)")
            }
            // Birth height defaults to the most-recent block we know about, so a
            // fresh enable scans forward from "now" rather than re-downloading
            // history. Recovery flows can override by writing dgb_settings/cf_birth_height.
            //
            // getLastBlockHeight() returns 0 here because the peer manager isn't
            // built until startSync(). Read the saved-blocks tip directly for
            // repeat launches, and fall back to the wallet's birth checkpoint
            // for fresh wallets — the C-side clamp in
            // BRPeerManagerEnableAutoCompactFilterFetch will snap the value
            // up further if the in-memory window can't resolve it.
            val savedTip = NativeBridge.getSavedBlocksTip()
            val tip = if (savedTip > 0) savedTip else NativeBridge.getWalletBirthCheckpointHeight()
            val birthHeight = settings.getLong("cf_birth_height", maxOf(0L, tip - 100L))
            NativeBridge.enableAutoCompactFilterFetch(birthHeight)
            android.util.Log.i("SyncService",
                "BIP158: mode=$syncMode, auto-fetch from height $birthHeight " +
                "(savedTip=$savedTip, anchor=$tip)")
        }
        // ─────────────────────────────────────────────────────────────────────────

        // If wallet previously completed sync, show Connected immediately.
        // The peer manager will catch up the last few blocks silently.
        if (hasReachedSynced) {
            walletManager.updateSyncState(SyncState.Complete)
        } else {
            walletManager.updateSyncState(SyncState.Syncing(0f, 0))
        }

        // Start SPV sync — will use saved blocks/peers if loaded.
        // Side effect: creates the peer manager and applies pending BIP 158
        // state (sync mode, filter chain, auto-fetch), so cfTip becomes
        // queryable immediately after this returns.
        NativeBridge.startSync()

        // Dandelion durability recovery: re-broadcast any recorded send the
        // wallet still sees as unconfirmed. A stem killed mid-embargo (process
        // death before the in-memory fluff timer fires) strands the tx — never
        // on-chain, never in mempool — because stem'd txs aren't in the C core's
        // re-broadcast set. Wait for peers so the fluff can actually propagate.
        // Wait up to 150s for peers — long enough to cover a Tor bootstrap
        // (~90s) before the SOCKS dials land, so the recovery still fires when
        // Tor is on.
        serviceScope.launch {
            var waited = 0
            while (NativeBridge.getPeerCount() <= 0 && waited < 150_000) {
                delay(2000L); waited += 2000
            }
            if (NativeBridge.getPeerCount() > 0) rebroadcastStrandedSends()
        }

        // Watchdog must snapshot cfTip AFTER startSync — otherwise it reads
        // 0 (no peer manager yet), compares against the restored chain at
        // +120s, and falsely declares progress. Without fallback firing in
        // the no-filter-peer case, the wallet sits with no bloom loaded and
        // silently misses every incoming tx.
        val syncModeNow = settings.getInt("sync_mode", NativeBridge.SyncMode.BOTH)
        if (syncModeNow != NativeBridge.SyncMode.BLOOM_ONLY) {
            val savedTipForWatchdog = NativeBridge.getSavedBlocksTip()
            val anchorForWatchdog = if (savedTipForWatchdog > 0) savedTipForWatchdog
                                    else NativeBridge.getWalletBirthCheckpointHeight()
            val birthHeightForWatchdog = settings.getLong(
                "cf_birth_height",
                maxOf(0L, anchorForWatchdog - 100L)
            )
            startBip158Watchdog(birthHeightForWatchdog)
        } else {
            // Persisted BLOOM_ONLY: the watchdog never runs, so it can't raise
            // the DigiDollar-degraded flag. Bloom is P2TR-blind, so a wallet
            // that already holds DigiDollar can't detect new DD this session —
            // surface the wallet-screen banner directly (#19 review). Stays set
            // for the session; the Sync Mode screen is the way back to filters.
            if (runCatching { NativeBridge.getDigiDollarBalance() > 0L }.getOrDefault(false)) {
                _digiDollarDetectionDegraded.value = true
            }
        }
    }

    override fun onDestroy() {
        // Flush the latest block window synchronously before teardown so a
        // graceful stop doesn't drop it to serviceScope.cancel(). The monotonic
        // guard ensures this never regresses a higher persisted tip.
        lastSavedBlocksData?.let { runCatching { persistBlocks(it, synchronous = true) } }
        NativeBridge.stopSync()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── NativeCallback — called from C JNI threads ────────────────────────────

    // Initialized in onStartCommand from persisted flag so progress callbacks
    // don't revert "Connected" back to "Syncing 0%" on restart near the chain tip.
    private var hasReachedSynced = false

    private val syncCallback = object : NativeCallback {

        override fun onSyncProgress(progress: Float, blockHeight: Long) {
            // progress == -1 is a signal from C core that a rescan is starting.
            if (progress < 0f) {
                hasReachedSynced = false // rescan resets sync status
                walletManager.updateSyncState(SyncState.Rescanning)
                return
            }

            // After fully synced (headers + rescan), only update on new blocks
            if (hasReachedSynced) return

            walletManager.updateSyncState(SyncState.Syncing(progress, blockHeight))
            val peers = NativeBridge.getPeerCount()
            updateNotification(progress, peers)
        }

        override fun onTransactionReceived(txHash: String, amount: Long, isReceive: Boolean) {
            serviceScope.launch {
                // Native asset detection: run the Kotlin DigiAssetDecoder against
                // the OP_RETURN in this tx (looked up via BRWallet). If it's a
                // DA payload, UTXO rows for non-OP_RETURN outputs are inserted
                // with is_asset=true and a placeholder asset-id; metadata fetch
                // via IPFS is kicked off on the same path. Happens regardless of
                // backend health — our sovereign fallback.
                val detected = runCatching {
                    assetManager.processIncomingAssetTx(txHashHex = txHash, blockHeight = 0L)
                }.onFailure {
                    android.util.Log.w("SyncService", "native asset detect failed for $txHash", it)
                }.getOrNull()

                val entity = TransactionEntity(
                    txid        = txHash,
                    blockHeight = 0, // updated when block confirmed
                    timestamp   = System.currentTimeMillis() / 1000L,
                    amount      = if (isReceive) amount else -amount,
                    fee         = 0,
                    toAddress   = "",
                    fromAddress = "",
                    confirmations = 0,
                    isAssetTx   = detected != null,
                    assetId     = detected?.assetId,
                )
                transactionDao.insert(entity)
            }
        }

        override fun onPeerConnected(peerCount: Int) {
            updateNotification(NativeBridge.getSyncProgress(), peerCount)

            // Persist newly connected peer address from the native side.
            // NativeBridge doesn't currently expose the address directly, so
            // we update the prune window to keep Room tidy.
            serviceScope.launch {
                val cutoff = System.currentTimeMillis() / 1000L - PEER_STALE_SECONDS
                peerDao.pruneOlderThan(cutoff)
            }
        }

        override fun onPeerDisconnected(peerCount: Int) {
            updateNotification(NativeBridge.getSyncProgress(), peerCount)
            // Don't show failure if we already reached the chain tip —
            // peer drops are normal, the polling loop will reconnect.
        }

        override fun onSyncComplete() {
            // Defense in depth for the same eclipse-attack / stale-peer scenario
            // the poll loop guards against: if the C core fires syncStopped while
            // our known height is below the checkpoint floor, all connected peers
            // are lying or behind — don't latch Complete, let the bloom rescan
            // keep running until a real tip peer appears.
            val height = NativeBridge.getLastBlockHeight()
            if (height > 0 && height < LATEST_CHECKPOINT_HEIGHT) {
                android.util.Log.w("SyncService",
                    "onSyncComplete fired at height=$height, below checkpoint " +
                    "floor $LATEST_CHECKPOINT_HEIGHT — ignoring (stale peers)")
                return
            }
            hasReachedSynced = true
            walletManager.updateSyncState(SyncState.Complete)
            // Persist sync-complete so restarts don't flash "Syncing 0%"
            getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                .edit().putBoolean("has_synced", true).apply()
            // Persist transactions and drop the foreground notification.
            // Peers stay connected so the user can send/receive while the app
            // is open. The service dies naturally when the activity is destroyed.
            // WorkManager handles background catch-ups after that.
            serviceScope.launch(Dispatchers.IO) {
                val txData = NativeBridge.getSerializedTransactions()
                if (txData != null) {
                    val hex = bytesToHex(txData)
                    getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                        .edit().putString("saved_transactions", hex).apply()
                    android.util.Log.i("SyncService", "Saved ${txData.size} bytes of transactions")
                }
                android.util.Log.i("SyncService", "Sync complete — keeping foreground service for peer connections")
                // Do NOT call stopForeground — Android kills the service without
                // the notification, which triggers onDestroy → stopSync → peers drop.
                // Update the notification to show connected status instead.
                val peers = NativeBridge.getPeerCount()
                updateNotification(1f, peers)

                // Sovereign asset-detection sweep: walk every known tx through
                // the Kotlin DigiAssetDecoder now that the wallet is fully
                // loaded. Any asset UTXOs we own become visible in the
                // Assets tab without touching the backend. Safe to run
                // repeatedly — UtxoDao.insertAll is REPLACE-on-PK.
                runCatching { assetManager.sweepKnownTransactionsForAssets() }
                    .onFailure { android.util.Log.w("SyncService", "native asset sweep failed", it) }
            }
        }

        override fun onSyncFailed(errorCode: Int, message: String) {
            // Don't show failure to the user — just retry after a short delay.
            // Most "failures" are peers rejecting SPV mode, which is normal.
            // The wallet will keep trying peers until it finds a compatible one.
            if (!hasReachedSynced) {
                android.util.Log.w("SyncService", "Sync error ($errorCode): $message — will retry via poll loop")
                // Don't retry from here — let the 30s polling loop handle reconnection.
                // Multiple concurrent startSync calls cause use-after-free crashes.
            }
        }

        override fun onBalanceChanged(balanceSatoshis: Long) {
            // UtxoDao Flow picks this up automatically via Room's invalidation
            // tracker — no explicit action needed here.
        }

        override fun onAssetDetected(txHash: String, assetId: String, quantity: Long, isReceive: Boolean) {
            // Called from a C JNI thread — serviceScope.launch transitions to Dispatchers.Default
            // and ensures all Room writes are serialised through the supervisor job.
            if (assetId.isBlank()) {
                android.util.Log.w("SyncService", "onAssetDetected: blank assetId for txHash=$txHash, skipping insert")
                return
            }
            serviceScope.launch {
                // Upsert the transaction record marked as an asset tx.
                // amount is stored as positive (receive) or negative (send).
                transactionDao.insert(
                    TransactionEntity(
                        txid          = txHash,
                        blockHeight   = 0, // updated when the block confirms
                        timestamp     = System.currentTimeMillis() / 1000L,
                        amount        = if (isReceive) quantity else -quantity,
                        fee           = 0,
                        toAddress     = "",
                        fromAddress   = "",
                        confirmations = 0,
                        isAssetTx     = true,
                        assetId       = assetId
                    )
                )
                // Refresh the UTXO table so the asset shows up in the Assets
                // tab immediately. onAssetDetected only gives us the txHash +
                // assetId + quantity — we need the vout, sats, and metadata
                // for a complete UtxoEntity, and listunspent is the cheapest
                // authoritative source.
                runCatching { assetManager.refreshAssetUtxosFromNetwork() }
                    .onFailure { android.util.Log.d("SyncService", "refresh-after-detect threw", it) }
            }
        }
        override fun onSaveBlocks(data: ByteArray, replace: Int) {
            // Persist off the C core callback thread to avoid blocking peer manager.
            // Cache the latest window so onDestroy can flush it synchronously, and
            // route through persistBlocks() for the monotonic anti-regression guard.
            val copy = data.copyOf()
            lastSavedBlocksData = copy
            serviceScope.launch(Dispatchers.IO) { persistBlocks(copy, synchronous = false) }
        }

        override fun onSavePeers(data: ByteArray, replace: Int) {
            val copy = data.copyOf()
            serviceScope.launch(Dispatchers.IO) {
                val hex = bytesToHex(copy)
                getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                    .edit().putString("saved_peers", hex).apply()
            }
        }

        override fun onSaveFilterHeaders(data: ByteArray) {
            // BIP 158 filter-header chain advanced. Persist hex-encoded for
            // restore on next wallet open. Fires only when sync mode != BLOOM_ONLY.
            val copy = data.copyOf()
            serviceScope.launch(Dispatchers.IO) {
                val hex = bytesToHex(copy)
                getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                    .edit().putString("saved_filter_headers", hex).apply()
            }
        }
    }

    // ── Bloom peer discovery ────────────────────────────────────────────────────

    /**
     * Fetch bloom-capable peers from the seeder API and inject them into the
     * C core's peer list. Uses a cached response (SharedPreferences) and
     * refreshes from the network at most once per hour.
     */
    /**
     * Inject a batch of bloom-serving peers into the native peer manager.
     *
     * Pool strategy (avoids hammering the seeder every 10s on a flaky network):
     *   1. Maintain a persistent pool of every bloom peer we've ever been
     *      told about, stored as JSON in SharedPreferences.
     *   2. Each call rotates a cursor through the pool and injects
     *      [BLOOM_BATCH_SIZE] peers starting at that cursor. The native
     *      core dedupes by (ip, port) so re-injecting the same peer is a
     *      no-op there.
     *   3. Fetch fresh from the seeder ONLY when the pool is stale
     *      (> 1 hour old), empty, or we've fully rotated through it
     *      without connecting. This keeps SyncService usable even if
     *      api.digiscope.me is briefly down — the pool from any previous
     *      session is still usable.
     */
    private fun injectBloomPeers() {
        if (isTestnet(this@SyncService)) {
            // Testnet26 has no mainnet-shaped seeder infra — api.digiscope.me
            // only knows mainnet peers, so the mainnet bloom-pool fetch AND the
            // Dandelion-capable-peer fetch (also served from that same seeder)
            // are both skipped entirely on testnet. Inject the two hardcoded
            // testnet26 peers instead; the refreshed testnet DNS seeds
            // (BRTestNetParams, native) supply the rest of the pool. The
            // mainnet branch below is unreached here and therefore unchanged.
            injectTestnetPeers()
            return
        }
        // Dandelion peers piggyback here so they're injected at every sync-start
        // path (all of them call injectBloomPeers). Runs first so bloom's early
        // returns can't skip it; self-throttled by its own last_fetch timer.
        injectDandelionPeers()
        val prefs = getSharedPreferences("dgb_bloom_peers" + networkSuffix(this@SyncService), MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val existing = prefs.getString("peer_pool", null)
        // Triple = (ip, port, servicesHex). servicesHex carries the seeder's
        // capability bits (0x40 = compact filters) so filter peers are tagged
        // when injected; 0 = unknown → native bloom-only default.
        val pool: MutableList<Triple<String, Int, Long>> =
            if (existing != null) parsePool(existing) else mutableListOf()
        val lastFetch = prefs.getLong("last_fetch", 0L)

        val stale = now - lastFetch > BLOOM_REFRESH_INTERVAL_MS
        if (stale || pool.isEmpty()) {
            val fresh = fetchFromSeeder()
            if (fresh != null && fresh.isNotEmpty()) {
                // Merge: add any peer we haven't seen before (dedup on ip:port,
                // ignoring services so a re-fetch doesn't duplicate an entry
                // whose capability bits changed).
                val seen = pool.mapTo(HashSet()) { it.first to it.second }
                for (p in fresh) if (seen.add(p.first to p.second)) pool.add(p)
                prefs.edit()
                    .putString("peer_pool", serializePool(pool))
                    .putLong("last_fetch", now)
                    .apply()
                android.util.Log.i(
                    "SyncService",
                    "Fetched bloom peers: ${fresh.size} new, pool now ${pool.size}"
                )
            } else if (pool.isEmpty()) {
                android.util.Log.w(
                    "SyncService",
                    "Bloom seeder empty and no cached pool — wallet may struggle to connect"
                )
                return
            }
        }

        if (pool.isEmpty()) return

        // Rotate cursor; inject a contiguous slice starting there.
        val cursor = prefs.getInt("pool_cursor", 0).coerceAtLeast(0) % pool.size
        val batchSize = BLOOM_BATCH_SIZE.coerceAtMost(pool.size)
        for (i in 0 until batchSize) {
            val (ip, port, services) = pool[(cursor + i) % pool.size]
            NativeBridge.injectPeerByIp(ip, port, services)
        }
        val newCursor = (cursor + batchSize) % pool.size
        prefs.edit().putInt("pool_cursor", newCursor).apply()

        android.util.Log.i(
            "SyncService",
            "Injected $batchSize peers (cursor $cursor→$newCursor, pool=${pool.size})"
        )
    }

    /** Inject the hardcoded testnet26 peers (no seeder involved — testnet26
     *  has no mainnet-shaped seeder infra). testnet26 nodes serve BIP157/158
     *  compact filters, not bloom, so tag them NODE_NETWORK|NODE_COMPACT_FILTERS
     *  (0x41) — filter-first selection dials them and the relaxed testnet accept
     *  gate keeps a compact-filter peer even without bloom. 95.111.238.51 is a
     *  verified compact-filter node (listed first). */
    private fun injectTestnetPeers() {
        for (ip in TESTNET_PRIORITY_PEERS) {
            NativeBridge.injectPeerByIp(ip, TESTNET_PRIORITY_PEER_PORT, TESTNET_PEER_SERVICES)
        }
        android.util.Log.i(
            "SyncService",
            "Testnet26: injected ${TESTNET_PRIORITY_PEERS.size} hardcoded peer(s)"
        )
    }

    /** Fetch the seeder's Dandelion-capable peers, connect them, and mark each
     *  Dandelion-capable in the core so a broadcast can stem to one. The VPS
     *  priority peer is covered because the seeder tags it (see the dandelion spec).
     *  No capable peer → sends transparently flood (no privacy, still delivered). */
    private fun injectDandelionPeers() {
        // Default OFF: Dandelion's stem→embargo→fluff strands a tx if the process
        // dies mid-embargo. Until that's fully hardened, sends flood directly
        // (reliable delivery). Opt-in via Settings → Network Info.
        val enabled = getSharedPreferences("dgb_dandelion", MODE_PRIVATE).getBoolean("enabled", false)
        try { NativeBridge.setDandelionEnabled(enabled) } catch (_: Throwable) {}
        if (!enabled) return

        val prefs = getSharedPreferences("dgb_dandelion_peers" + networkSuffix(this@SyncService), MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = prefs.getString("peer_pool", null)?.let { parsePool(it) } ?: mutableListOf()
        val lastFetch = prefs.getLong("last_fetch", 0L)
        // Triple = (ip, port, servicesHex) — Dandelion peers are also filter-
        // capable per the seeder, so carry their services_hex through too.
        val pool: List<Triple<String, Int, Long>> =
            if (now - lastFetch > BLOOM_REFRESH_INTERVAL_MS || cached.isEmpty()) {
                val fresh = fetchFromSeeder("dandelion")
                if (!fresh.isNullOrEmpty()) {
                    prefs.edit().putString("peer_pool", serializePool(fresh))
                        .putLong("last_fetch", now).apply()
                    fresh
                } else cached
            } else cached

        if (pool.isEmpty()) {
            android.util.Log.i("SyncService", "Dandelion: no capable peers advertised — sends will flood")
            return
        }
        for ((ip, port, services) in pool) {
            NativeBridge.injectPeerByIp(ip, port, services)  // connect it (so it joins the pool)
            NativeBridge.addDandelionPeer(ip)                // mark it Dandelion-capable
        }
        android.util.Log.i("SyncService", "Dandelion: injected + marked ${pool.size} capable peer(s)")
    }

    /** Try the seeder once. Returns the parsed peer list or null on failure.
     *  [capability] filters the seeder pool (e.g. "dandelion"); null = default pool. */
    private fun fetchFromSeeder(capability: String? = null): List<Triple<String, Int, Long>>? {
        val url = if (capability != null) "$SEEDER_URL?capability=$capability" else SEEDER_URL
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    parsePeersJson(response.body!!.string())
                } else {
                    android.util.Log.w("SyncService", "Seeder API returned ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SyncService", "Seeder API unreachable: ${e.message}")
            null
        }
    }

    /** Parse the seeder JSON shape: {"peers":[{"ip":"...","port":12024,"services_hex":"0x44d", ...}], "capability":"filter|bloom|filter+bloom", ...}
     *  Extracts ip + port + services_hex per peer. services_hex carries the node's
     *  advertised service bits — notably 0x40 (SERVICES_NODE_COMPACT_FILTERS) — so
     *  the native filter-first peer selection can recognize and hold filter peers.
     *  Absent/unparseable services_hex → 0 (native falls back to bloom-only). */
    private fun parsePeersJson(json: String): List<Triple<String, Int, Long>> {
        return try {
            val root = org.json.JSONObject(json)
            val capability = root.optString("capability", "unknown")
            val arr = root.getJSONArray("peers")
            android.util.Log.i("SyncService",
                "Seeder response: capability=$capability, ${arr.length()} peer(s)")
            val out = ArrayList<Triple<String, Int, Long>>(arr.length())
            for (i in 0 until arr.length()) {
                val peer = arr.getJSONObject(i)
                val services = parseSeederServicesHex(peer.optString("services_hex", ""))
                out.add(Triple(peer.getString("ip"), peer.optInt("port", 12024), services))
            }
            out
        } catch (e: Exception) {
            android.util.Log.w("SyncService", "Failed to parse seeder peer JSON: ${e.message}")
            emptyList()
        }
    }

    /** Pool is stored as a compact JSON array of "ip:port:servicesHex" strings
     *  (servicesHex is lowercase hex, no 0x prefix). Seeder peers are IPv4, so
     *  splitting on ':' is unambiguous. Legacy "ip:port" entries from older
     *  caches parse with services = 0 (native falls back to bloom-only). */
    private fun serializePool(pool: List<Triple<String, Int, Long>>): String {
        val arr = org.json.JSONArray()
        for ((ip, port, services) in pool) arr.put("$ip:$port:${services.toString(16)}")
        return arr.toString()
    }

    private fun parsePool(json: String): MutableList<Triple<String, Int, Long>> {
        return try {
            val arr = org.json.JSONArray(json)
            val out = ArrayList<Triple<String, Int, Long>>(arr.length())
            for (i in 0 until arr.length()) {
                val parts = arr.getString(i).split(':')
                if (parts.size >= 2) {
                    val port = parts[1].toIntOrNull() ?: 12024
                    val services = if (parts.size >= 3) (parts[2].toLongOrNull(16) ?: 0L) else 0L
                    out.add(Triple(parts[0], port, services))
                }
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    // ── Hex encoding (allocation-free) ─────────────────────────────────────────

    /**
     * Re-fluff (flood-broadcast) any recorded send the wallet still sees as
     * unconfirmed. Recovers Dandelion stems stranded by a process death during
     * the embargo window. Idempotent: an already-propagated or confirmed tx is
     * harmlessly re-announced; a double-spend is rejected.
     *
     * Retries with verification: getPeerCount counts peers that are merely
     * connecting (not yet relay-ready, especially over Tor), so a single fluff
     * can fire before any peer can carry it. After each fluff we wait and check
     * getRelayCount(txid) — once the network relays it back the tx has
     * propagated and we stop. If it never relays back after all attempts it is
     * likely an unrecoverable double-spend (its inputs were already spent).
     */
    private suspend fun rebroadcastStrandedSends() {
        val recorded = OutgoingTxStore(this).allTxids()
        if (recorded.isEmpty()) return
        // wallet's confirmation view: txid -> blockHeight (TX_UNCONFIRMED = INT32_MAX)
        val heights = HashMap<String, Long>()
        runCatching {
            NativeBridge.getTransactionDetails().trim().lines().forEach { line ->
                val parts = line.split("|")
                if (parts.size >= 4) heights[parts[0]] = parts[3].toLongOrNull() ?: 0L
            }
        }
        val unconfirmed = recorded.filter { txid ->
            val h = heights[txid]
            h == null || h <= 0L || h >= Int.MAX_VALUE.toLong()
        }
        val store = OutgoingTxStore(this)
        var dropped = false
        for (txid in unconfirmed) {
            // Definitive double-spend: the C core marks a tx invalid when its
            // inputs were already spent by another (confirmed) tx. Such a tx can
            // never confirm — drop the phantom and forget the record rather than
            // re-fluffing forever. (Only acts on this authoritative signal, never
            // on the relay guess, so a valid-but-slow send is never dropped.)
            if (!runCatching { NativeBridge.isTransactionValid(txid) }.getOrDefault(true)) {
                if (runCatching { NativeBridge.removeTransaction(txid) }.getOrDefault(false)) {
                    store.remove(txid)
                    dropped = true
                    android.util.Log.i("SyncService",
                        "Dandelion recovery: dropped conflicted (double-spend) send $txid")
                }
                continue
            }
            // Valid but unconfirmed → RE-PUBLISH to recover a stem stranded by a
            // process death. fluffTransaction only floods a tx already in the
            // peer manager's in-memory publishedTx, which a restart empties — so
            // it's a no-op for exactly this case. Fetch the raw bytes and
            // publishTransaction instead: that re-registers the tx for broadcast
            // (BRPeerManagerPublishTx) and floods it to all connected peers.
            val raw = runCatching { NativeBridge.getSerializedTransactionForHash(txid) }.getOrNull()
            if (raw == null) {
                android.util.Log.w("SyncService",
                    "Dandelion recovery: $txid not in wallet tx set — can't re-publish")
                continue
            }
            var propagated = false
            for (attempt in 1..3) {
                runCatching { NativeBridge.publishTransaction(raw) }
                    .onFailure { android.util.Log.w("SyncService", "re-publish $txid threw", it) }
                delay(15_000L)
                val relays = runCatching { NativeBridge.getRelayCount(txid) }.getOrDefault(0)
                if (relays > 0) {
                    android.util.Log.i("SyncService",
                        "Dandelion recovery: $txid re-published & propagated (relays=$relays, attempt $attempt)")
                    propagated = true
                    break
                }
            }
            if (!propagated) {
                android.util.Log.w("SyncService",
                    "Dandelion recovery: $txid still un-relayed after re-publish retries")
            }
        }
        // Persist the wallet tx set so any drops survive a restart.
        if (dropped) {
            runCatching {
                NativeBridge.getSerializedTransactions()?.let { data ->
                    getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
                        .edit().putString("saved_transactions", bytesToHex(data)).apply()
                }
            }
        }
    }

    /** Most recent serialized block window from [NativeCallback.onSaveBlocks],
     *  cached so [onDestroy] can flush it synchronously before teardown. The C
     *  core only emits saves on 4000-block boundaries / at the tip, so this is
     *  the last boundary window — sub-boundary catch-up isn't captured here, but
     *  the recent bundled checkpoint makes re-syncing that gap near-instant. */
    @Volatile private var lastSavedBlocksData: ByteArray? = null

    /**
     * Write the serialized saved-blocks window to disk behind a monotonic guard:
     * never replace a higher persisted tip with a lower one (the bug that forced
     * ~480k-block re-syncs). [synchronous] uses commit() for the onDestroy flush
     * so it survives serviceScope cancellation.
     */
    private fun persistBlocks(data: ByteArray, synchronous: Boolean) {
        val prefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
        val newTop = parseSavedBlocksTopHeight(data)
        val persistedTop = prefs.getLong("saved_blocks_tip", 0L)
        if (!shouldPersistBlocks(newTop, persistedTop)) {
            android.util.Log.i("SyncService",
                "saved_blocks: skipping regressive write (new tip $newTop < persisted $persistedTop)")
            return
        }
        val editor = prefs.edit().putString("saved_blocks", bytesToHex(data))
        if (newTop >= 0L) editor.putLong("saved_blocks_tip", newTop)
        if (synchronous) editor.commit() else editor.apply()
    }

    private val hexChars = "0123456789abcdef".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hexChars[v ushr 4]
            chars[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(chars)
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Blockchain Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows DigiByte blockchain synchronization progress"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(progress: Float, peerCount: Int): Notification {
        val pct = (progress * 100).toInt()
        val torSuffix = if (torProxyActive) " · via Tor" else ""
        val contentText = when {
            pct >= 100 -> "Synced — $peerCount peer${if (peerCount != 1) "s" else ""} connected$torSuffix"
            peerCount == 0 -> "Connecting to peers…$torSuffix"
            else -> "Syncing $pct% — $peerCount peer${if (peerCount != 1) "s" else ""}$torSuffix"
        }
        val indeterminate = pct == 0 && peerCount == 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DigiByte Wallet")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            // MIN priority keeps the notification off the lock screen / status
            // bar as much as the platform allows while still signalling to the
            // OS that this is a persistent, active foreground task. Raising to
            // DEFAULT made aggressive OEM killers (notably Samsung's) less
            // likely to reap the process, but we went with MIN for user-facing
            // invisibility — if process death continues in the field we can
            // revisit. See docs/bugs/peer-keepalive-proc-death.md.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(progress: Float, peerCount: Int) {
        val notification = buildNotification(progress, peerCount)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID       = "dgb_sync_channel"
        const val NOTIFICATION_ID  = 1
        const val ERR_NO_PEERS     = 1001
        /** Peers not seen in 24 hours are pruned from the DB. */
        private const val PEER_STALE_SECONDS = 86_400L
        /** Capability-aware seeder API. Returns filter-capable peers when available,
         *  falling through to bloom-capable peers when not. The wallet's existing
         *  parser ignores the extra per-peer fields (peer_capability, capabilities,
         *  services_hex, etc.) since it only reads ip + port.
         *  MAINNET ONLY — api.digiscope.me knows nothing about testnet26 peers.
         *  Never fetched when [io.digibyte.core.isTestnet] is true; see
         *  [injectBloomPeers]. */
        private const val SEEDER_URL = "https://api.digiscope.me/api/peers"
        /** Hardcoded testnet26 public peers injected in place of the mainnet
         *  digiscope seeder pool when the wallet is running on testnet. The
         *  native startSync() path also prepends these as the cold-start
         *  priority peer(s) (mirroring digiscope.me on mainnet); re-injecting
         *  them here on every reconnect attempt additionally adds them to an
         *  already-live peer manager's candidate pool (see
         *  NativeBridge.injectPeerByIp), same as the mainnet bloom pool does. */
        private val TESTNET_PRIORITY_PEERS = listOf("95.111.238.51", "164.68.98.125", "129.212.182.152")
        private const val TESTNET_PRIORITY_PEER_PORT = 12033
        /** NODE_NETWORK (0x01) | NODE_COMPACT_FILTERS (0x40) — testnet26 nodes
         *  serve BIP157/158 filters, not bloom. */
        private const val TESTNET_PEER_SERVICES = 0x41L
        /** Refresh bloom peer list every 60 minutes. */
        private const val BLOOM_REFRESH_INTERVAL_MS = 60 * 60 * 1000L
        /** How many peers to inject per call. The C peer manager caps its
         *  own connections at 5, so injecting more is a pool of candidates
         *  for the peer manager to pick from rather than concurrent connections. */
        private const val BLOOM_BATCH_SIZE = 20
        /** Clear the Tor proxy and degrade to direct after this many consecutive
         *  0-peer keepalive cycles (10s each = 150s). Must be GENEROUS: peers dial
         *  through Tor's onion circuits far slower than direct (first circuit +
         *  per-peer SOCKS dial routinely exceeds a minute), and a healthy Tor
         *  connection must NOT be torn down just because peers are still
         *  establishing. The old value of 3 (30s) killed Tor ~27s after it
         *  connected on boot, forcing a manual re-enable. The dedicated
         *  runTorFallbackWatchdog (TOR_FALLBACK_TIMEOUT_MS = 120s) is the primary
         *  fallback for a genuinely-dead daemon; this is a longer mid-session
         *  backstop that fires only after Tor has had ample time to route. */
        private const val MAX_TOR_RECONNECT_FAILURES = 15
        /** How long to wait for BIP158 cfheaders progress before falling back
         *  to bloom for this session. The pool is thin (3 filter peers) so we
         *  need a generous window for Tor bootstrap + slow handshakes; 120s
         *  was the user-chosen ceiling. Past this deadline, a poll interval
         *  with no cf progress AND a real cfTip→blockTip gap triggers
         *  fallbackToBloom. */
        private const val BIP158_FALLBACK_TIMEOUT_MS = 120_000L

        /** How close blockTip must be to the peers' estimated network height
         *  to consider block-header sync "caught up." While headers are still
         *  importing toward the tip (a deep-behind wallet or post-rescan
         *  checkpoint reset), cfheaders legitimately can't advance — the
         *  watchdog stays on filters instead of falling back to bloom. */
        private const val BLOCK_CATCHUP_GRACE = 50L

        /** Max transient block-stall → bloom → back-to-filters recovery cycles per
         *  session. After this many, stay on bloom and surface the privacy banner. */
        private const val MAX_BLOCK_STALL_RECOVERIES = 3

        /** Poll cadence for the BIP158 watchdog. Tight enough to catch a
         *  freshly-opened cfTip→blockTip gap shortly after headers catch
         *  past the saved-blocks tip, loose enough to avoid log spam. */
        private const val BIP158_WATCHDOG_POLL_MS = 15_000L

        /** Process-wide flag set when the BIP158 watchdog falls back to
         *  bloom for the current session. The wallet UI collects this to
         *  surface a "privacy degraded" banner. Resets to false on every
         *  process start (companion-object lifecycle == process lifecycle)
         *  so the next launch re-tries filters first. */
        val bloomFallbackActive: kotlinx.coroutines.flow.StateFlow<Boolean>
            get() = _bloomFallbackActive
        private val _bloomFallbackActive =
            kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Process-wide flag set when the wallet holds DigiDollar and the BIP158
         *  watchdog would otherwise degrade to bloom — bloom (BIP37) never
         *  matches P2TR outputs, so it cannot see DigiDollar (issue #19). When
         *  set, the watchdog kept (or forced) filters and the UI surfaces a
         *  "DigiDollar detection degraded" warning. Cleared when cfheaders
         *  resume; resets on process start like [bloomFallbackActive]. */
        val digiDollarDetectionDegraded: kotlinx.coroutines.flow.StateFlow<Boolean>
            get() = _digiDollarDetectionDegraded
        private val _digiDollarDetectionDegraded =
            kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Process-wide flag set when the Tor watchdog gives up waiting for
         *  bootstrap and forces a clearnet fallback. The wallet UI collects
         *  this to surface a "Tor unavailable" banner. Same lifecycle as
         *  bloomFallbackActive — resets on every process start so the next
         *  launch re-tries Tor. */
        val torFailureActive: kotlinx.coroutines.flow.StateFlow<Boolean>
            get() = _torFailureActive
        private val _torFailureActive =
            kotlinx.coroutines.flow.MutableStateFlow(false)

        /** How long to wait for Tor to bring up peer connectivity before
         *  forcing a clearnet fallback. Covers both (a) Tor never reaching
         *  Connected at all, and (b) Tor reaching Connected but routing
         *  through SOCKS failing to dial any peers. Chosen to be a bit longer
         *  than TorManager's own BOOTSTRAP_TIMEOUT_MS (90s) so the manager's
         *  own timeout fires first when applicable. */
        private const val TOR_FALLBACK_TIMEOUT_MS = 120_000L

        /**
         * Sanity floor for "we've reached the chain tip." The highest
         * hardcoded checkpoint in
         * native/src/main/jni/digibytewallet-core/BRChainParams.h is
         * block 23,660,000. Any real chain tip must be at or past that;
         * peers claiming a lower tip are lagging or dishonest and must
         * NOT cause us to declare sync complete (which stops the bloom
         * rescan and strands user transactions in unscanned blocks).
         *
         * Update this when a newer BRMainNetCheckpoints entry is added
         * to the submodule.
         */
        private const val LATEST_CHECKPOINT_HEIGHT = 23_660_000L

        /** How many consecutive 10s polls we must observe `atRealTip` before
         *  we flip to SyncState.Complete. Headers can reach tip while per-block
         *  merkleblocks are still being delivered; a grace window prevents the
         *  UI from declaring sync complete while outgoing-spend merkleblocks
         *  are still in flight. 3 polls * 10s = 30s. */
        private const val TIP_GRACE_POLLS = 3

        /** If the keepalive hasn't stamped a tick in this long, we assume
         *  Doze or a similar platform-level pause froze it. onStartCommand
         *  cancels the old job and respawns. Chosen to be > 5× the normal
         *  10s tick to avoid respawn thrash under short GC pauses etc. */
        private const val KEEPALIVE_STALE_THRESHOLD_MS = 60_000L

        /** Consecutive 10s keepalive ticks at 0 peers before escalating from a light
         *  reconnect (re-inject + startSync) to a clean peer-manager recreate
         *  (forceReconnect). 3 ticks ≈ 30s — gives the light path a few tries first,
         *  then digs out a manager stuck after long Doze idle. */
        private const val ZERO_PEER_RECREATE_THRESHOLD = 3
    }
}
