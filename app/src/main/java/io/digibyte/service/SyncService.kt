package io.digibyte.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.digibyte.core.UtxoManager
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground within 5 seconds — do it first thing.
        startForeground(NOTIFICATION_ID, buildNotification(progress = 0f, peerCount = 0))

        // On repeat onStartCommand (watchdog kick, sticky-restart), resurrect
        // the peer-keepalive coroutine if it died silently. Without this the
        // service stays nominally alive with its foreground notification but
        // no one is restoring peer connections — users see "0 peers forever"
        // while the watchdog fruitlessly re-kicks the same running service.
        if (syncAlreadyLaunched) {
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
        hasReachedSynced = getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
            .getBoolean("has_synced", false)

        // Wire C core → Kotlin before kicking off sync so no events are lost.
        NativeBridge.setCallbackHandler(syncCallback)

        // Launch Tor-aware startup asynchronously.
        serviceScope.launch {
            startSyncWithTor()
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
        while (isActive) {
            delay(10_000L)
            // Stamp every tick so onStartCommand can detect a frozen-by-Doze
            // coroutine that's still nominally active and respawn it.
            lastKeepaliveTickMs = System.currentTimeMillis()
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
                            // Don't auto-restart Tor — just stay on direct connections.
                            // The user can re-enable Tor from Settings if they want to
                            // try again. Auto-restart would re-set the proxy and kill
                            // the working direct connections.
                            android.util.Log.i("SyncService", "Continuing without Tor — user can re-enable from Settings")
                        }
                    }
                    android.util.Log.i("SyncService", "No peers connected, re-injecting bloom peers and reconnecting")
                    injectBloomPeers()
                    NativeBridge.startSync()
                } else {
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
                            getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
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
                                getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
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
            } else {
                // Tor failed to start — clear any stale proxy and fall through.
                NativeBridge.clearSocksProxy()
                torProxyActive = false
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
                android.util.Log.w("SyncService", "Wallet not loaded after 60s — giving up")
                return
            }
        }
        android.util.Log.i("SyncService", "Wallet ready, starting sync (waited ${waitCount * 500}ms)")

        // Load saved blocks and peers from previous session before syncing
        val prefs = getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
        val savedBlocks = prefs.getString("saved_blocks", null)
        val savedPeers = prefs.getString("saved_peers", null)

        if (savedBlocks != null) {
            val blockBytes = savedBlocks.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val loaded = NativeBridge.loadSavedBlocks(blockBytes)
            android.util.Log.i("SyncService", "Loaded $loaded saved blocks from disk")
        }
        if (savedPeers != null) {
            val peerBytes = savedPeers.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val loaded = NativeBridge.loadSavedPeers(peerBytes)
            android.util.Log.i("SyncService", "Loaded $loaded saved peers from disk")
        }

        // Inject bloom-capable peers from the seeder API before starting sync.
        // This ensures the wallet has multiple bloom peers to try, not just digiscope.me.
        injectBloomPeers()

        // If wallet previously completed sync, show Connected immediately.
        // The peer manager will catch up the last few blocks silently.
        if (hasReachedSynced) {
            walletManager.updateSyncState(SyncState.Complete)
        } else {
            walletManager.updateSyncState(SyncState.Syncing(0f, 0))
        }

        // Start SPV sync — will use saved blocks/peers if loaded
        NativeBridge.startSync()
    }

    override fun onDestroy() {
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
                val entity = TransactionEntity(
                    txid        = txHash,
                    blockHeight = 0, // updated when block confirmed
                    timestamp   = System.currentTimeMillis() / 1000L,
                    amount      = if (isReceive) amount else -amount,
                    fee         = 0,
                    toAddress   = "",
                    fromAddress = "",
                    confirmations = 0,
                    isAssetTx   = false
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
            getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
                .edit().putBoolean("has_synced", true).apply()
            // Persist transactions and drop the foreground notification.
            // Peers stay connected so the user can send/receive while the app
            // is open. The service dies naturally when the activity is destroyed.
            // WorkManager handles background catch-ups after that.
            serviceScope.launch(Dispatchers.IO) {
                val txData = NativeBridge.getSerializedTransactions()
                if (txData != null) {
                    val hex = bytesToHex(txData)
                    getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
                        .edit().putString("saved_transactions", hex).apply()
                    android.util.Log.i("SyncService", "Saved ${txData.size} bytes of transactions")
                }
                android.util.Log.i("SyncService", "Sync complete — keeping foreground service for peer connections")
                // Do NOT call stopForeground — Android kills the service without
                // the notification, which triggers onDestroy → stopSync → peers drop.
                // Update the notification to show connected status instead.
                val peers = NativeBridge.getPeerCount()
                updateNotification(1f, peers)
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
            }
        }
        override fun onSaveBlocks(data: ByteArray, replace: Int) {
            // Persist off the C core callback thread to avoid blocking peer manager
            val copy = data.copyOf()
            serviceScope.launch(Dispatchers.IO) {
                val hex = bytesToHex(copy)
                getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
                    .edit().putString("saved_blocks", hex).apply()
            }
        }

        override fun onSavePeers(data: ByteArray, replace: Int) {
            val copy = data.copyOf()
            serviceScope.launch(Dispatchers.IO) {
                val hex = bytesToHex(copy)
                getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
                    .edit().putString("saved_peers", hex).apply()
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
        val prefs = getSharedPreferences("dgb_bloom_peers", MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val existing = prefs.getString("peer_pool", null)
        val pool: MutableList<Pair<String, Int>> =
            if (existing != null) parsePool(existing) else mutableListOf()
        val lastFetch = prefs.getLong("last_fetch", 0L)

        val stale = now - lastFetch > BLOOM_REFRESH_INTERVAL_MS
        if (stale || pool.isEmpty()) {
            val fresh = fetchFromSeeder()
            if (fresh != null && fresh.isNotEmpty()) {
                // Merge: add any peer we haven't seen before.
                val seen = pool.toSet()
                for (p in fresh) if (!seen.contains(p)) pool.add(p)
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
            val (ip, port) = pool[(cursor + i) % pool.size]
            NativeBridge.injectPeerByIp(ip, port)
        }
        val newCursor = (cursor + batchSize) % pool.size
        prefs.edit().putInt("pool_cursor", newCursor).apply()

        android.util.Log.i(
            "SyncService",
            "Injected $batchSize peers (cursor $cursor→$newCursor, pool=${pool.size})"
        )
    }

    /** Try the seeder once. Returns the parsed peer list or null on failure. */
    private fun fetchFromSeeder(): List<Pair<String, Int>>? {
        return try {
            val request = Request.Builder().url(BLOOM_SEEDER_URL).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    parsePeersJson(response.body!!.string())
                } else {
                    android.util.Log.w("SyncService", "Bloom seeder API returned ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SyncService", "Bloom seeder API unreachable: ${e.message}")
            null
        }
    }

    /** Parse the seeder JSON shape: {"peers":[{"ip":"...","port":12024}, ...]} */
    private fun parsePeersJson(json: String): List<Pair<String, Int>> {
        return try {
            val root = org.json.JSONObject(json)
            val arr = root.getJSONArray("peers")
            val out = ArrayList<Pair<String, Int>>(arr.length())
            for (i in 0 until arr.length()) {
                val peer = arr.getJSONObject(i)
                out.add(peer.getString("ip") to peer.optInt("port", 12024))
            }
            out
        } catch (e: Exception) {
            android.util.Log.w("SyncService", "Failed to parse bloom peer JSON: ${e.message}")
            emptyList()
        }
    }

    /** Pool is stored as a compact JSON array of "ip:port" strings. */
    private fun serializePool(pool: List<Pair<String, Int>>): String {
        val arr = org.json.JSONArray()
        for ((ip, port) in pool) arr.put("$ip:$port")
        return arr.toString()
    }

    private fun parsePool(json: String): MutableList<Pair<String, Int>> {
        return try {
            val arr = org.json.JSONArray(json)
            val out = ArrayList<Pair<String, Int>>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getString(i)
                val colon = s.lastIndexOf(':')
                if (colon > 0) {
                    val port = s.substring(colon + 1).toIntOrNull() ?: 12024
                    out.add(s.substring(0, colon) to port)
                }
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    // ── Hex encoding (allocation-free) ─────────────────────────────────────────

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
        /** Bloom seeder API URL — returns bloom-capable peers as JSON. */
        private const val BLOOM_SEEDER_URL = "https://api.digiscope.me/api/peers/bloom"
        /** Refresh bloom peer list every 60 minutes. */
        private const val BLOOM_REFRESH_INTERVAL_MS = 60 * 60 * 1000L
        /** How many peers to inject per call. The C peer manager caps its
         *  own connections at 5, so injecting more is a pool of candidates
         *  for the peer manager to pick from rather than concurrent connections. */
        private const val BLOOM_BATCH_SIZE = 20
        /** Clear Tor proxy after this many consecutive 0-peer poll cycles. */
        private const val MAX_TOR_RECONNECT_FAILURES = 3

        /**
         * Sanity floor for "we've reached the chain tip." The highest
         * hardcoded checkpoint in
         * native/src/main/jni/digibytewallet-core/BRChainParams.h is
         * block 23,187,000. Any real chain tip must be at or past that;
         * peers claiming a lower tip are lagging or dishonest and must
         * NOT cause us to declare sync complete (which stops the bloom
         * rescan and strands user transactions in unscanned blocks).
         *
         * Update this when a newer BRMainNetCheckpoints entry is added
         * to the submodule.
         */
        private const val LATEST_CHECKPOINT_HEIGHT = 23_187_000L

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
    }
}
