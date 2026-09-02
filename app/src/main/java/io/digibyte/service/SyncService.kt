package io.digibyte.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.digibyte.BootGuard
import io.digibyte.core.UtxoManager
import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.WalletManager
import io.digibyte.core.WalletState
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.reconcile.ChainReconciliationService
import io.digibyte.core.reconcile.DgbNodeClient
import io.digibyte.core.asset.assetPruneGateOpen
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.bridge.NativeCallback
import io.digibyte.core.sync.CfAbandonmentStore
import io.digibyte.core.sync.CfScanLedgerStore
import io.digibyte.core.sync.FilterHeaderStore
import io.digibyte.core.sync.SavedBlockStore
import io.digibyte.core.sync.KeepaliveAction
import io.digibyte.core.sync.keepaliveAction
import io.digibyte.core.db.dao.PeerDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.entity.PeerEntity
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.model.SyncState
import io.digibyte.core.isTestnet
import io.digibyte.core.networkSuffix
import io.digibyte.core.settings.CustomNode
import io.digibyte.core.settings.CustomNodePrefs
import io.digibyte.core.settings.syncModeFor
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import kotlinx.coroutines.runInterruptible

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

    /** Self-healing watchdog that re-arms the keepalive if it dies or goes
     *  stale, on a timer — independent of onStartCommand. onStartCommand is the
     *  only OTHER resurrection path, and nothing fires it while the app sits
     *  foreground-idle, so a keepalive that dies on a transient error (observed:
     *  a network blip dropping every peer at once → error 101 → the keepalive
     *  coroutine dies) would otherwise stay dead and strand the wallet at 0
     *  peers until a manual force-stop. See runKeepaliveWatchdog. */
    private var keepaliveWatchdogJob: Job? = null

    /** Wall-clock timestamp of the last keepalive tick. Used by the respawn
     *  check so we detect coroutines that are nominally `isActive=true` but
     *  have been frozen by Doze for so long that the peer-keepalive has
     *  effectively stopped running. Without this, a stuck-in-delay coroutine
     *  never triggers respawn because Job.isActive stays true throughout
     *  Doze suspension. */
    @Volatile private var lastKeepaliveTickMs: Long = 0L

    /**
     * Dedicated thread for the one BLOCKING native call the keepalive makes.
     *
     * NativeBridge.keepAlivePeers() takes PEER_GUARD (the native peer-manager lock) and can
     * therefore block for as long as whatever else holds it. Calling it inline on
     * Dispatchers.Default made the keepalive loop's own liveness depend on it, with two
     * consequences seen on a Note 8 on 2026-08-04:
     *
     *  1. The loop stamped its tick ONCE and never again — on the first iteration tickCount is 1,
     *     so every %3 and %30 branch is skipped and keepAlivePeers() is the ONLY work after the
     *     stamp. The watchdog then read "coroutine frozen" when the truth was "native call has
     *     not returned". Four respawns, exactly 90s apart (10s to the first stamp + the 80s stale
     *     window), each reporting "no tick in 80s".
     *  2. Job.cancel() CANNOT interrupt a thread inside a JNI call. So every respawn left the old
     *     coroutine parked on a Dispatchers.Default thread forever and started another that
     *     wedged identically — leaking one of a handful of shared threads every 90 seconds, which
     *     starves every other serviceScope coroutine including the ones driving the CF scan.
     *
     * Same lesson as launchBoundedRecovery: you cannot bound blocking code by cancelling the
     * coroutine that called it. Hand it to a detached worker and let the loop carry on.
     */
    private val nativeKeepaliveExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "dgb-keepalive-native").apply { isDaemon = true }
        }

    /** True while a keepAlivePeers() sweep is still inside JNI. Guards against queueing a second
     *  one behind a stuck first — they would serialize on the same lock and the backlog would
     *  only grow. */
    private val nativeKeepaliveInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Wall-clock of the last keepAlivePeers() that actually RETURNED. Deliberately separate from
     *  lastKeepaliveTickMs: one says "the loop is alive", the other says "the native layer is
     *  answering". Conflating them is what disguised a hung native call as a dead coroutine. */
    @Volatile private var lastNativeKeepaliveOkMs: Long = 0L

    /** Consecutive respawns where the previous keepalive job never actually completed. */
    private var wedgedRespawnStreak = 0

    /** Consecutive poll ticks observing `height at chain tip`. We only flip
     *  to SyncState.Complete after a grace window of stability so the UI
     *  doesn't falsely declare "synced" while merkleblocks for recent blocks
     *  are still in flight. See TIP_GRACE_POLLS. */
    private var atTipConsecutivePolls = 0
    private var lastUnscannedLogged = 0L   // rate-limits the CF-behind log; see UNSCANNED_LOG_DELTA

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
    private var tipStallWatchdogJob: Job? = null
    /** Proactive backstop that respawns a dead/frozen keepalive on a timer (recoveryScope/IO). */
    private var zeroPeerWatchdogJob: Job? = null
    /** Demand-side peer-cap controller (full while catching up, few once synced). */
    private var peerCapControllerJob: Job? = null
    private var syncedSinceMs = 0L

    /**
     * Session-scoped, NEVER-persisted override set by the dark-node banner's
     * "Use public peers" escape hatch (ACTION_OWN_NODE_ADDITIVE_SESSION). While
     * true, injectCustomNode() pins the configured node non-exclusive regardless
     * of the persisted CustomNodePrefs.isExclusive() choice — so later re-pins
     * from OTHER recovery paths (the 0-peer branch of runPeerKeepalive, the Tor
     * fallback watchdog) don't silently re-apply exclusivity and re-strand the
     * wallet at 0 peers, defeating the escape within the same session. Cleared
     * by a deliberate re-apply from Settings (ACTION_APPLY_OWN_NODE), which
     * re-honors the persisted choice. Defaults false — inert for every user who
     * hasn't used the escape hatch, and always false again on the next process
     * launch since it's never written to prefs.
     */
    @Volatile private var ownNodeAdditiveSessionOverride = false

    /** Set true when an onSyncComplete fires IN THIS PROCESS. Distinct from the
     *  persisted has_synced flag (which is true at startup before this session
     *  verifies the tx set) — the asset prune must gate on this, not that. */
    @Volatile private var syncedThisSession = false

    // ── Sync progress (lock-free status-reads refactor) ───────────────────────
    // The native getSyncProgress() pull was retired: its formula needs syncStart
    // + hasDownloadPeer, which have no public submodule getter. Instead the
    // native onSyncProgress callback PUSHES an internally-consistent float; we
    // cache it here and read the flow instead of calling native. A cold poll
    // (no recent push) computes the same formula Kotlin-side from the lock-free
    // mirrored heights + a Kotlin-tracked syncStart + peerCount>0 as the
    // hasDownloadPeer proxy (see [currentSyncProgress] / [computeSyncProgress]).
    private val syncProgressFlow: kotlinx.coroutines.flow.MutableStateFlow<Float> =
        kotlinx.coroutines.flow.MutableStateFlow(0f)

    /** True once the first real onSyncProgress callback of THIS process/session
     *  has landed. Until then the cold-poll computed float is PROVISIONAL — it
     *  leans on the persisted syncStart anchor, and native isStatusStale() also
     *  reads stale (never-refreshed) at cold start, corroborating the provisional
     *  state for any future supervisor. */
    @Volatile private var sawSyncProgressCallback = false

    /** Height at which this session's sync run started — the Kotlin stand-in for
     *  the native cachedSyncStartHeight (which has no public getter). PERSISTED
     *  alongside has_synced so a cold start mid-sync computes real progress from
     *  the true anchor instead of "reverts to Syncing 0%" garbage. */
    @Volatile private var syncStartHeight = 0L

    /** Last time the post-sync confirmation-reconcile ran (ms), so a flaky
     *  network firing onSyncComplete repeatedly can't hammer the node. */
    @Volatile private var lastConfirmReconcileMs = 0L

    /** Serializes the sovereign asset-maintenance cycle (native sweep + gated
     *  prune) below so a slow cycle on a long-history wallet can't stack a
     *  second one on top of it every ~30s tick. */
    private val assetMaintenanceRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    // Legacy Chang heal ships LOG-ONLY first. Flip to false only after the
    // on-device dry-run confirms the candidate set (see plan Rollout).
    private val legacyHealDryRun = true

    /**
     * SupervisorJob so that a child coroutine failure never cancels the
     * parent — important because Room insert failures must not tear down sync.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Event-driven network-regained recovery. serviceScope runs on Dispatchers.Default
    // (a small, CPU-bound pool); a network blip can leave a native socket call blocked,
    // starving that pool so the polling keepalive/watchdog freeze. This SEPARATE scope on
    // Dispatchers.IO (a large pool) is not starved by those frozen Default-pool coroutines,
    // so it can drive a reconnect when the OS reports the network is back — the common
    // freeze here is Default-pool starvation, not a permanent native-lock hold. (If a
    // serviceScope thread is parked inside a native call still holding PEER_GUARD,
    // forceReconnect blocks on that mutex until it releases; withTimeout bounds the wait
    // and the next onAvailable retries.)
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkRecoveryMs = 0L

    // ── BIP158 filter-header persistence (coalesced, file-backed) ──────────────
    // The CF-header chain grows to tens/hundreds of MB; hex-encoding it into
    // SharedPreferences on every cfheaders batch pinned an ever-growing String in
    // the prefs in-memory map (a 512MB heap leak → OOM loop → never-synced). Keep
    // only the latest chain in memory and flush it to a plain file at most once per
    // interval via the writer coroutine. See FilterHeaderStore.
    // (chain bytes, epoch-at-capture) — the epoch lets a re-anchor/reset delete()
    // invalidate a stale in-flight write (see FilterHeaderStore).
    @Volatile private var pendingFilterHeaders: Pair<ByteArray, Long>? = null
    // CF scan ledger (Phase-1 observe-only): mirrors pendingFilterHeaders — latest
    // serialized ledger + epoch-at-capture, flushed by the same coalesced writer.
    @Volatile private var pendingCfLedger: Pair<ByteArray, Long>? = null
    @Volatile private var filterHeadersDirty = false
    private var filterHeaderWriterJob: Job? = null
    private val filterHeaderSaveIntervalMs = 20_000L
    private val syncCompletionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val binder = SyncBinder()

    // ── Public API exposed via Binder ─────────────────────────────────────────

    inner class SyncBinder : Binder() {
        fun getService(): SyncService = this@SyncService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        foregroundSyncLive.set(true)
        createNotificationChannel()
        startFilterHeaderWriter()
        registerNetworkRegainedCallback()
        startZeroPeerWatchdog()
        startPeerCapController()
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

        // Immediate-apply entry point: the Settings own-node UI sends this action
        // right after the user saves a new host/port or flips exclusive/enabled so
        // the change takes effect without waiting for the next keepalive cycle.
        // Reuses the exact keepalive reconnect triple (forceReconnect → re-inject →
        // startSync) that already recovers a stalled peer pool elsewhere in this
        // service (see the 0-peer branch of runPeerKeepalive).
        // Wallet-screen Tor banner "Retry now": one user-initiated Tor start.
        // On success the Tor-state observer re-wires the SOCKS proxy, drops the
        // clearnet peers (IP-leak guard) and clears the banner; on another
        // failure the banner simply stays up. No state to clean here.
        if (intent?.action == ACTION_RETRY_TOR) {
            if (torManager.isEnabled) {
                android.util.Log.i("SyncService", "banner retry — restarting Tor")
                serviceScope.launch { torManager.start() }
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_APPLY_OWN_NODE) {
            // A deliberate re-apply from Settings re-honors the persisted
            // exclusive choice — clear any session escape left over from a
            // prior "Use public peers" tap so it doesn't linger past the
            // user's explicit save.
            ownNodeAdditiveSessionOverride = false
            serviceScope.launch {
                recreatePeerManagerResumingNearTip("own-node settings change")
            }
            return START_STICKY
        }

        // Session escape hatch: the dark-node banner's "Use public peers" action.
        // Re-pins the SAME configured node but non-exclusive for THIS session only —
        // prefs are never written, so the persisted exclusive choice still applies
        // on the next launch. Mirrors ACTION_APPLY_OWN_NODE's reconnect triple.
        if (intent?.action == ACTION_OWN_NODE_ADDITIVE_SESSION) {
            // Set BEFORE launching so injectCustomNode() below (and every later
            // re-pin this session — the 0-peer keepalive recovery, the Tor
            // fallback watchdog) honors the escape until a deliberate Settings
            // re-apply (ACTION_APPLY_OWN_NODE) clears it. Never written to
            // prefs, so the persisted exclusive choice is untouched.
            ownNodeAdditiveSessionOverride = true
            serviceScope.launch {
                recreatePeerManagerResumingNearTip("own-node additive override")
            }
            return START_STICKY
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
            resurrectKeepaliveIfNeeded()
            // Re-arm the self-healing watchdog too, in case it ever died — so the
            // keepalive always has a timer-based resurrector, even when
            // onStartCommand stops being called (foreground-idle).
            if (keepaliveWatchdogJob?.isActive != true) {
                keepaliveWatchdogJob = serviceScope.launch { runKeepaliveWatchdog() }
            }
            // Re-arm the tip-stall watchdog too (same OS-freeze death risk as keepalive).
            if (tipStallWatchdogJob?.isActive != true) {
                startTipStallWatchdog()
            }
            // #2 loop-revival: if the loop DIED (0 peers) even though setup completed —
            // the OS-background-freeze case where every serviceScope coroutine stopped and
            // the resurrected keepalive alone can't dig out — do a full recovery here:
            // recreate the manager + re-inject the canon peers + restart. Off-main because
            // getPeerCount takes PEER_GUARD (main-thread ANR risk). Skip while Tor is coming
            // up so peers don't dial direct before the SOCKS proxy is wired (IP leak).
            val torComingUp = torManager.isEnabled &&
                (torManager.state.value is TorState.Connecting || torManager.state.value is TorState.Starting)
            if (syncSetupComplete && !torComingUp) {
                serviceScope.launch(Dispatchers.IO) {
                    val p = runCatching { NativeBridge.getPeerCount() }.getOrDefault(-1)
                    val loaded = runCatching { NativeBridge.isWalletLoaded() }.getOrDefault(false)
                    if (p == 0 && loaded) {
                        android.util.Log.w(
                            "SyncService",
                            "loop-revival: 0 peers with sync setup complete — full recovery " +
                                "(recreate manager + re-inject canon + restart)"
                        )
                        recreatePeerManagerResumingNearTip("cf-frozen")
                    }
                }
            }
            return START_STICKY
        }
        syncAlreadyLaunched = true

        // Restore persisted sync state so progress callbacks don't revert
        // "Connected" back to "Syncing 0%" on restart near the chain tip.
        run {
            val syncPrefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
            hasReachedSynced = syncPrefs.getBoolean("has_synced", false)
            // Restore the sync-start anchor on the SAME path as has_synced, so a
            // cold poll before the first onSyncProgress callback computes real
            // progress from the true floor rather than garbage.
            syncStartHeight = syncPrefs.getLong("sync_start_height", 0L)
        }

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
                // Mid-session enable that FAILS (Settings toggle -> startAsync ->
                // bootstrap timeout/daemon death with no network, etc.). Without
                // this branch nothing flips the failure flag on that path, so the
                // keepalive's torComingUp guard defers peer dials FOREVER and the
                // wallet sits at 0 peers with no banner - the exact silent state
                // ROADMAP Phase 2 item 5 forbids. Degrade loudly: clear any stale
                // proxy, release the guard, raise the banner. Idempotent with
                // startSyncWithTor's own failure path.
                if (st is TorState.Failed && torManager.isEnabled) {
                    android.util.Log.w("SyncService",
                        "Tor failed (" + st.reason + ") — degrading to clearnet, raising banner")
                    NativeBridge.clearSocksProxy()
                    torProxyActive = false
                    _torFailureActive.value = true
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

        // Self-healing watchdog: re-arm the keepalive on a timer if it ever dies
        // or goes stale, so recovery never depends on onStartCommand being
        // fired again (it isn't, while the app sits foreground-idle).
        keepaliveWatchdogJob = serviceScope.launch { runKeepaliveWatchdog() }

        return START_STICKY
    }

    /**
     * Respawn the peer-keepalive coroutine if it has died or gone stale
     * (Doze-frozen without cancelling). Idempotent. Called from onStartCommand
     * (on a start Intent, main thread) AND runKeepaliveWatchdog (on a timer,
     * Dispatchers.Default) — @Synchronized so the two callers can't race into
     * two competing keepalive loops.
     */
    /**
     * Register a default-network callback so we react when connectivity RETURNS. A
     * network blip drops every peer socket at once (errno 101 "Network is unreachable")
     * regardless of peer quality — and the wallet had NO reaction to the network coming
     * back, so it sat at 0 peers until a force-stop (the polling keepalive/watchdog can
     * freeze when the blip blocks a native call and starves serviceScope's Default pool).
     * onAvailable fires exactly when a usable network appears; we then force a clean
     * reconnect to the 16 canon CF peers from the independent [recoveryScope].
     */
    private fun registerNetworkRegainedCallback() {
        if (networkCallback != null) return
        val cm = runCatching { getSystemService(android.net.ConnectivityManager::class.java) }.getOrNull() ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                // Trigger recovery only when the network is actually ROUTABLE to the internet,
                // not merely associated. onAvailable fires while WiFi is still authenticating
                // (captive portal / DHCP), so reconnecting then burns a dial against a
                // not-yet-usable network. NET_CAPABILITY_VALIDATED means the OS has confirmed
                // real connectivity. Cheap: onNetworkRegained is throttled + gated on peers<=0.
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    onNetworkRegained()
                }
            }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) {
            networkCallback = cb
            android.util.Log.i("SyncService", "registered network-regained callback")
        }
    }

    /**
     * Run a recovery block without letting it wedge the caller.
     *
     * THE BUG THIS EXISTS FOR (Note 8, 2026-08-02). Both recovery paths used
     * `withTimeout { injectPeers(); injectCustomNode(); NativeBridge.startSync() }`,
     * and the KDoc claimed that stopped "a hung native call wedging the recovery
     * thread". It does not. Coroutine cancellation is COOPERATIVE: withTimeout can only
     * cancel at a suspension point, and none of those three calls has one — two are
     * plain blocking functions and the third is a blocking JNI call. When one blocked,
     * the timeout never fired, .onFailure never ran, and nothing was logged:
     *
     *   10:58:42  zero-peer watchdog: keepalive unhealthy + 0 peers — reviving recovery
     *             <- 47 minutes of total silence; process alive, 0 sockets, network fine
     *
     * The mechanism meant to CURE a 0-peer wedge became the permanent wedge, which is
     * why force-stopping the app was the only known recovery.
     *
     * Two defences, because either alone is insufficient:
     *  1. the block wraps its blocking segments in `runInterruptible(Dispatchers.IO)`, so
     *     cancelling the worker Thread.interrupt()s it, which does unblock interruptible
     *     syscalls (sockets, park/sleep). It does NOT reliably interrupt a blocking JNI
     *     call, hence:
     *  2. **nothing ever awaits the worker.** The timeout lives in a SEPARATE coroutine that
     *     cancels the worker and releases the latch without joining it, so a call that
     *     ignores interruption can only leak one thread — it can no longer stop the watchdog
     *     from running, retrying, or escalating. **This is the load-bearing one.**
     *
     * Defence 2 is why this is not simply `withTimeoutOrNull { block() }`. withTimeout*
     * cancels the child and then SUSPENDS UNTIL IT COMPLETES — against a JNI call that
     * ignores interruption it never returns, the abandon log never prints, and the
     * in-flight latch never clears. That would rebuild the same permanent wedge one layer up.
     *
     * The block is `suspend` because one leg of recovery — [injectCustomNode] — resolves DNS
     * and so is itself suspending; it cannot be shoved inside runInterruptible. Callers wrap
     * each genuinely blocking leg individually rather than the whole block, which keeps the
     * ordering (injectPeers → own node → startSync) intact.
     *
     * At most one attempt is in flight; overlapping triggers are dropped, not stacked. If a
     * worker is abandoned, the latch opens anyway and a later trigger may run alongside the
     * stuck one — deliberate. Extra concurrency is the safe failure direction here; refusing
     * to ever retry is the failure mode that cost 47 minutes.
     */
    private val recoveryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun launchBoundedRecovery(tag: String, block: suspend () -> Unit) {
        if (!recoveryInFlight.compareAndSet(false, true)) {
            android.util.Log.i("SyncService", "$tag: recovery already in flight — not stacking")
            return
        }
        val worker = recoveryScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                // CancellationException lands here too — that's the abandon path, already logged.
                android.util.Log.w("SyncService", "$tag: recovery ended: ${t.javaClass.simpleName}")
            } finally {
                recoveryInFlight.set(false)
            }
        }
        recoveryScope.launch {
            delay(NETWORK_RECOVERY_TIMEOUT_MS)
            if (worker.isActive) {
                worker.cancel()               // interrupts the runInterruptible legs
                recoveryInFlight.set(false)   // a stuck worker must never latch recovery shut
                android.util.Log.w("SyncService",
                    "$tag: recovery exceeded ${NETWORK_RECOVERY_TIMEOUT_MS}ms and was abandoned " +
                    "(watchdog continues; a blocking native call cannot be force-cancelled)")
            }
        }
    }

    /**
     * The network just became available. If we're at 0 peers, force a clean reconnect —
     * recreate the manager, re-inject the canon peers, restart sync. Runs on [recoveryScope]
     * (Dispatchers.IO, large pool) so it can't be starved by a frozen serviceScope, and under
     * [launchBoundedRecovery] so a hung native call can't wedge the recovery thread — a plain
     * withTimeout CANNOT do that, since cancellation is cooperative and these calls block
     * without a suspension point (see the 2026-08-02 wedge). Throttled so rapid
     * network flaps don't stack reconnects. Tor-guarded (never dial direct while SOCKS wires up).
     */
    private fun onNetworkRegained() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkRecoveryMs < NETWORK_RECOVERY_THROTTLE_MS) return
        lastNetworkRecoveryMs = now
        if (!syncSetupComplete) return
        val torComingUp = torManager.isEnabled &&
            (torManager.state.value is TorState.Connecting || torManager.state.value is TorState.Starting)
        if (torComingUp) return
        val peers = runCatching { NativeBridge.getPeerCount() }.getOrDefault(-1)
        val loaded = runCatching { NativeBridge.isWalletLoaded() }.getOrDefault(false)
        if (peers > 0 || !loaded) return

        android.util.Log.w("SyncService",
            "network regained + $peers peers — forcing clean reconnect to canon peers")
        // Same hazard as the zero-peer watchdog: every call below is BLOCKING, so the old
        // withTimeout here could not cancel any of them. Detached + interruptible instead.
        launchBoundedRecovery("network-regained") {
            runInterruptible(Dispatchers.IO) {
                // Refresh the near-tip window BEFORE the rebuild consumes it, or the new
                // manager floors to the birth checkpoint. Same ordering as
                // recreatePeerManagerResumingNearTip; kept inline here because this path
                // splits across IO hops for the DNS-bound own-node injection.
                // Flush first: the reload below reads the last PERSISTED snapshot, and the
                // rebuild discards anything still only in memory (flushLiveStateBeforeRecreate).
                flushLiveStateBeforeRecreate()
                runCatching { reloadSavedBlocksNearTip() }
                runCatching { NativeBridge.forceReconnect() }
                injectPeers()
            }
            injectCustomNode()   // suspend (DNS); has its own IO hop
            runInterruptible(Dispatchers.IO) {
                runCatching { NativeBridge.startSync() }
                runCatching { restoreCfLedgerAndSnap() }
                // The polling keepalive/watchdog may have frozen with the pool; re-arm it
                // now that the reconnect has freed the wedged native state.
                runCatching { resurrectKeepaliveIfNeeded() }
            }
        }
    }

    /**
     * Demand-side load-spread: hold the FULL peer set while catching up (fast sync + the wedge
     * buffer we rely on), then drop to [SYNCED_PEER_COUNT] once STABLY synced so the thousands of
     * synced+idle wallets stop each pinning 8 slots on the small shared filter-node fleet. A wallet
     * at the tip receives ~1 block / 15s, which a few peers serve easily. Restores to the full
     * count the instant it falls behind (Syncing/Rescanning) — catch-up gets full redundancy.
     *
     * Reduction is gated on SyncState.Complete being stable for [PEER_CAP_REDUCE_GRACE_MS] (Complete
     * is itself already grace-gated upstream, so this never flaps on a single tip block). We call
     * the native setter every poll with the desired count; it no-ops when already at target and
     * re-applies after a manager recreate (which resets to the full default), so the state stays
     * correct without tracking it across recreates. Native never drops the download peer or the
     * pinned own-node.
     */
    @Synchronized
    private fun startPeerCapController() {
        if (peerCapControllerJob?.isActive == true) return
        peerCapControllerJob = serviceScope.launch { runPeerCapController() }
    }

    private suspend fun runPeerCapController() {
        var applied = -1
        while (true) {
            delay(PEER_CAP_POLL_MS)
            if (!syncSetupComplete) continue
            val now = System.currentTimeMillis()
            // SyncState.Complete is STICKY — it's restored from prefs and set BEFORE startSync's
            // silent catch-up finishes, and never reverts without a rescan — so it's necessary but
            // NOT sufficient. Gate the reduction on a LIVE at-tip signal: the header tip within
            // PEER_CAP_TIP_DELTA of the network estimate AND a healthy peer count. This keeps the
            // full 8 through a long-gap cold-start catch-up (est >> last) and through 0-peer
            // recovery (peer count dips), and only drops to 3 once genuinely idle at the tip.
            val atTip = run {
                if (walletManager.syncState.value !is io.digibyte.core.model.SyncState.Complete) return@run false
                val last = runCatching { NativeBridge.getLastBlockHeight() }.getOrDefault(0L)
                val est = runCatching { NativeBridge.getEstimatedBlockHeight() }.getOrDefault(0L)
                val peers = runCatching { NativeBridge.getPeerCount() }.getOrDefault(0)
                last > 0L && est > 0L && est - last <= PEER_CAP_TIP_DELTA && peers >= SYNCED_PEER_COUNT
            }
            syncedSinceMs = if (atTip) (if (syncedSinceMs == 0L) now else syncedSinceMs) else 0L
            val stableSynced = atTip && now - syncedSinceMs >= PEER_CAP_REDUCE_GRACE_MS
            val desired = if (stableSynced) SYNCED_PEER_COUNT else CATCHUP_PEER_COUNT
            runCatching { NativeBridge.setMaxPeerConnections(desired) }
            if (desired != applied) {
                android.util.Log.i("SyncService",
                    if (desired == SYNCED_PEER_COUNT) "synced — holding $desired peers (fleet load-spread)"
                    else "catching up — holding $desired peers")
                applied = desired
            }
            // CF scan ledger (Phase-1 observe-only): log the ledger state once per poll
            // for on-device watching, regardless of the peer-cap reduce/hold branch.
            runCatching {
                val c = NativeBridge.getCfScanLedgerCounts()
                if (c.size >= 4) android.util.Log.i("SyncService",
                    "cf-ledger: scannedThrough=${c[0]} outstanding=${c[1]} gaveUp=${c[2]} pending=${c[3]}")
            }

            // Stranded-send recovery, on a timer rather than only at sync start.
            //
            // rebroadcastStrandedSends had exactly ONE call site — the startup block below
            // — so a send stranded DURING a session had no recovery at all until the app
            // was restarted. That is precisely the "I have to restart the app a few times
            // to get the transaction to broadcast" report: the recovery existed, it just
            // could not run while you were sitting there waiting for it.
            //
            // Rate-limited rather than every tick: a republish is real network traffic, and
            // the common case is that there is nothing to do. It also costs nothing when
            // the store is empty, which is the overwhelming majority of ticks.
            runCatching {
                val now = System.currentTimeMillis()
                if (now - lastStrandedRebroadcastMs >= STRANDED_REBROADCAST_INTERVAL_MS &&
                    NativeBridge.getPeerCount() > 0 &&
                    OutgoingTxStore(this@SyncService).allTxids().isNotEmpty()
                ) {
                    lastStrandedRebroadcastMs = now
                    rebroadcastStrandedSends()
                }
            }.onFailure { android.util.Log.w("SyncService", "periodic stranded-send sweep threw", it) }

            // Abandoned-band backfill. Only runs when a band actually exists, and each
            // call is a single step: retire whatever the resident headers already allow,
            // then ask one peer for the next stretch underneath the band. Re-derives
            // everything from the ledger and the block set each time, so a missed tick or
            // a dropped peer costs time and nothing else.
            //
            // Before this existed the only cures for a "history gap" were a backend
            // reconcile (which discloses the address set) or a full rebuild from wallet
            // birth — re-scanning ~24M blocks to recover ~20k.
            runCatching {
                if (NativeBridge.getAbandonedBelow() > 0L) {
                    val retired = NativeBridge.backfillAbandonedBandStep()
                    if (retired > 0L) {
                        android.util.Log.i("SyncService",
                            "cf-backfill: retired $retired abandoned height(s); " +
                                "abandonedBelow now ${NativeBridge.getAbandonedBelow()}")
                    }
                }
            }.onFailure { android.util.Log.w("SyncService", "cf-backfill step threw", it) }
        }
    }

    /**
     * Launch the proactive 0-peer watchdog once, on the independent [recoveryScope].
     * Idempotent (@Synchronized + isActive guard). Torn down with recoveryScope in onDestroy.
     */
    @Synchronized
    private fun startZeroPeerWatchdog() {
        if (zeroPeerWatchdogJob?.isActive == true) return
        zeroPeerWatchdogJob = recoveryScope.launch { runZeroPeerWatchdog() }
    }

    /**
     * Proactive, TIME-driven BACKSTOP for a dead/frozen peer-keepalive. The keepalive owns
     * 0-peer recovery (light injectPeers()+startSync() every 10s → BRPeerManagerConnect, which
     * resets the native connectFailureCount give-up latch and re-dials; escalating to a full
     * forceReconnect recreate at 30s). But that coroutine can DIE or freeze in a peer-drop
     * storm, and resurrectKeepaliveIfNeeded — the thing that respawns it — was only ever called
     * on EVENTS (onResume / onStartCommand / network-regained), never on a timer. So when the
     * keepalive died while the app sat foreground-idle on a stable network, nothing brought it
     * back: the wedge captured live (foreground + screen-awake, 8 min at 0 peers, native loop
     * silent, only a force-stop cured it). This loop is that missing timer.
     *
     * Runs on recoveryScope (Dispatchers.IO) so it survives even if the Default pool the
     * keepalive lives on is starved. Acts ONLY as a backstop — while the keepalive is healthy
     * (job active AND ticking) it defers, so it never double-drives recovery against the
     * keepalive's own 0-peer branch. On a sustained wedge it does a LIGHT reconnect here
     * (un-latches via startSync WITHOUT the expensive recreate / chain re-floor) AND respawns
     * the keepalive so its own graduated recovery resumes.
     */
    private suspend fun runZeroPeerWatchdog() {
        var consecutiveZero = 0
        while (true) {
            delay(ZERO_PEER_WATCHDOG_POLL_MS)
            if (!syncSetupComplete) { consecutiveZero = 0; continue }
            val loaded = runCatching { NativeBridge.isWalletLoaded() }.getOrDefault(false)
            if (!loaded) { consecutiveZero = 0; continue }
            // Backstop only: defer while the keepalive is HEALTHY (its job is active AND it has
            // ticked within the stale threshold). A dead job fails this immediately; a frozen
            // (active-but-not-ticking) one fails it after the stale threshold. Either way the
            // keepalive's own faster 0-peer branch owns recovery when it's alive, so we never
            // double-drive it.
            val keepaliveHealthy = keepaliveJob?.isActive == true && lastKeepaliveTickMs > 0L &&
                (System.currentTimeMillis() - lastKeepaliveTickMs) <= KEEPALIVE_STALE_THRESHOLD_MS
            if (keepaliveHealthy) { consecutiveZero = 0; continue }
            val peers = runCatching { NativeBridge.getPeerCount() }.getOrDefault(-1)
            if (peers > 0) { consecutiveZero = 0; continue }
            consecutiveZero++
            if (consecutiveZero >= ZERO_PEER_WATCHDOG_TRIGGER_POLLS) {
                val secs = consecutiveZero * ZERO_PEER_WATCHDOG_POLL_MS / 1000L
                android.util.Log.w("SyncService",
                    "zero-peer watchdog: keepalive unhealthy + $peers peers for ~${secs}s — reviving recovery")
                // Don't dial DIRECT while Tor is still wiring its SOCKS proxy (IP-leak guard).
                val torComingUp = torManager.isEnabled &&
                    (torManager.state.value is TorState.Connecting || torManager.state.value is TorState.Starting)
                if (!torComingUp) {
                    // LIGHT reconnect on THIS IO scope: injectPeers()+startSync() → BRPeerManagerConnect
                    // resets the native give-up latch and re-dials WITHOUT the expensive manager recreate.
                    // Runs on recoveryScope (Dispatchers.IO) so it works even if the Default
                    // pool is starved. Detached + interruptible: see launchBoundedRecovery.
                    // Awaiting this block is what wedged the watchdog for 47 minutes.
                    launchBoundedRecovery("zero-peer watchdog") {
                        runInterruptible(Dispatchers.IO) { injectPeers() }
                        injectCustomNode()   // suspend (DNS); has its own IO hop
                        runInterruptible(Dispatchers.IO) { NativeBridge.startSync() }
                    }
                }
                // Respawn the dead/frozen keepalive so its graduated recovery resumes.
                runCatching { resurrectKeepaliveIfNeeded() }
                consecutiveZero = 0
            }
        }
    }

    @Synchronized
    /**
     * Hand the blocking native keepalive sweep to [nativeKeepaliveExecutor] and return
     * immediately, so the caller's loop keeps ticking whether or not the sweep comes back.
     *
     * If a previous sweep is still inside JNI we do NOT queue another. A second call would just
     * block on the same PEER_GUARD behind the first, and the queue would grow one entry per tick
     * for as long as the stall lasts. Instead we say so out loud — a native lock held for minutes
     * is the actual defect, and it deserves a log line naming it rather than being smoothed over.
     */
    private fun dispatchNativeKeepalive() {
        if (!nativeKeepaliveInFlight.compareAndSet(false, true)) {
            val stuckSec = if (lastNativeKeepaliveOkMs > 0L) {
                (System.currentTimeMillis() - lastNativeKeepaliveOkMs) / 1000L
            } else -1L
            // Name the holder. lockHolderInfo takes NO native lock and never touches the peer
            // manager pointer — it reads file-static atomics — so it answers even though we are
            // standing behind the very lock in question. Without this the log could only say
            // "something is holding it", which is exactly where three device runs stalled: the
            // [CF-SLOW] profiler logs on RELEASE, so a lock held forever produced no line at all.
            val holder = runCatching { NativeBridge.lockHolderInfo() }.getOrNull()
            android.util.Log.w(
                "SyncService",
                "keepAlivePeers still in JNI after ${stuckSec}s — skipping this tick " +
                    "(native peer lock held by ${holder ?: "unknown"}; not queueing behind it)"
            )
            return
        }
        runCatching {
            nativeKeepaliveExecutor.execute {
                try {
                    NativeBridge.keepAlivePeers()
                    lastNativeKeepaliveOkMs = System.currentTimeMillis()
                } catch (t: Throwable) {
                    android.util.Log.w("SyncService", "keepAlivePeers threw", t)
                } finally {
                    nativeKeepaliveInFlight.set(false)
                }
            }
        }.onFailure {
            // Executor rejected (shutting down) — release the flag so we don't latch.
            nativeKeepaliveInFlight.set(false)
        }
    }

    /**
     * The policy lives in [keepaliveAction] (core/sync/KeepaliveHealth.kt) so it can be tested
     * without an Android Service — this method only reads the state and carries out the verdict.
     * Keeping the decision here as a second copy of the same `if` chain would mean the test
     * exercised a parallel implementation rather than the shipped one.
     */
    private fun resurrectKeepaliveIfNeeded() {
        val now = System.currentTimeMillis()
        val prev = keepaliveJob
        // -1 means "never stamped": a freshly respawned loop that has not reached its first tick.
        val sinceTick = if (lastKeepaliveTickMs > 0L) now - lastKeepaliveTickMs else -1L

        val action = keepaliveAction(
            jobExists = prev != null,
            jobActive = prev?.isActive == true,
            jobCompleted = prev?.isCompleted == true,
            msSinceLastTick = sinceTick,
            staleThresholdMs = KEEPALIVE_STALE_THRESHOLD_MS,
            wedgedStreak = wedgedRespawnStreak,
            wedgedLimit = WEDGED_RESPAWN_LIMIT,
        )

        when (action) {
            KeepaliveAction.NONE -> return

            KeepaliveAction.RESPAWN_DEAD -> {
                android.util.Log.w("SyncService", "keepalive coroutine not active — respawning")
                wedgedRespawnStreak = 0
                // Reset the tick watermark so a respawned keepalive that parks on
                // walletState.first{Unlocked} (wallet locked) isn't immediately re-flagged stale
                // off the pre-death timestamp.
                lastKeepaliveTickMs = 0L
                keepaliveJob = serviceScope.launch { runPeerKeepalive() }
            }

            KeepaliveAction.RESPAWN_STALE -> {
                val gap = sinceTick / 1000L
                prev?.cancel()
                if (prev != null && !prev.isCompleted) wedgedRespawnStreak++ else wedgedRespawnStreak = 0
                android.util.Log.w(
                    "SyncService",
                    "keepalive stale: no tick in ${gap}s — cancelling + respawning " +
                        "(nativeInFlight=${nativeKeepaliveInFlight.get()})"
                )
                lastKeepaliveTickMs = 0L
                keepaliveJob = serviceScope.launch { runPeerKeepalive() }
            }

            KeepaliveAction.GIVE_UP_WEDGED -> {
                // Deliberately NOT respawning. cancel() cannot reach a thread inside JNI, so the
                // old coroutine still holds its Dispatchers.Default thread; another copy would
                // wedge in the same place and take another one. Leaking the shared pool turns a
                // stall into an outage — the CF scan runs on those same threads.
                // Name the holder HERE too, not only in dispatchNativeKeepalive's skip branch.
                // Once this branch latches, the keepalive is never respawned, so
                // dispatchNativeKeepalive is never called again and that readout goes silent —
                // which is exactly why a 31-minute wedge produced only two holder samples while
                // this line fired 68 times. The diagnostic has to live on the branch that keeps
                // running, not the one that stops.
                val holder = runCatching { NativeBridge.lockHolderInfo() }.getOrNull()
                android.util.Log.e(
                    "SyncService",
                    "keepalive stale ${sinceTick / 1000L}s and the previous job has not completed " +
                        "after $wedgedRespawnStreak attempts — NOT respawning " +
                        "(nativeInFlight=${nativeKeepaliveInFlight.get()}, held by " +
                        "${holder ?: "nothing"}). It is stuck somewhere cancellation cannot reach."
                )
            }
        }
    }

    /**
     * Self-healing watchdog for the peer-keepalive. The reconnect recovery
     * (re-inject peers, startSync, forceReconnect) lives in runPeerKeepalive's
     * 0-peer branch, but that coroutine can die — an unhandled JNI throwable, a
     * child coroutine cancelling its scope, or the peer-drop storm that follows
     * a "Network is unreachable" error (observed on a Note 8: a wifi blip
     * dropped all peers at once, error 101 fired, and the keepalive never
     * ticked again). Its only other resurrection is onStartCommand, which is
     * NOT fired while the app sits foreground-idle — so without this the wallet
     * stays at 0 peers until a manual force-stop. This loop re-arms the
     * keepalive on a timer. It launches NO child coroutines and guards every
     * tick, so it cannot die the way the keepalive can; delay() is the sole
     * cancellation point, so it exits cleanly when serviceScope is cancelled.
     */
    private suspend fun runKeepaliveWatchdog() {
        while (true) {
            delay(KEEPALIVE_WATCHDOG_INTERVAL_MS)
            try {
                resurrectKeepaliveIfNeeded()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("SyncService", "keepalive watchdog tick threw", t)
            }
        }
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
            // CF connection keepalive: ping every connected peer each 10s tick so idle filter
            // peers (only one serves cfheaders at a time; the rest sit idle) don't get dropped by
            // the remote node / NAT inactivity timeout and become dead-socket zombies. Bloom got
            // this "for free" via its always-active single download peer; CF's multi-peer model
            // needs it explicit. This is the hypothesized root of CF sync being flakier than bloom.
            dispatchNativeKeepalive()
            // CF-first: re-inject the validated filter peers every ~30s so they stay dialable in
            // the native pool after the fleet churns them out (a peer is removed from the pool on
            // connect failure). injectPeerByIp dedups, so this only re-adds ones that dropped —
            // without it, a churn burst can leave the native filter-first pre-pass with nothing
            // to dial and the wallet stops catching cfheaders up. Network fetch is throttled
            // hourly inside injectFilterPeers; the 30s cadence just re-injects the cached set.
            if (tickCount % 3L == 0L && !isTestnet(this@SyncService)) {
                launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { injectFilterPeers() } }
            }
            // Own-node health poll: same ~30s cadence as the filter-peer re-injection
            // above (reusing this tick rather than a dedicated timer). refreshOwnNodeHealth
            // self-gates on the toggle (→ UNPAIRED when off), so no extra guard needed here.
            if (tickCount % 3L == 0L) {
                runCatching { refreshOwnNodeHealth() }
            }
            // Every 30th tick (~5 min): confirmation-reconcile backstop for a tx stranded at
            // TX_UNCONFIRMED because its confirming block's cfilter round-trip was lost and is
            // never retried. Previously this ran only at onSyncComplete, which an already-synced
            // wallet never reaches again — so a DigiDollar receive that arrived live could sit
            // at $0 indefinitely until the user manually ran "Scan for missing transactions".
            // Internally gated on pending>0 and debounced, so a healthy wallet does nothing.
            // Snapshot the re-dial penalties periodically too: a process killed by the
            // OS (Doze, low memory, force-stop) never reaches onDestroy, and that is
            // exactly the wallet that most needs to not re-dial known-bad peers on the
            // way back up.
            if (tickCount % 30L == 0L) persistPeerPenalties()
            if (tickCount % 30L == 0L && NativeBridge.getPeerCount() > 0) {
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    maybeRunConfirmationReconcile("keepalive tick")
                }
            }
            // Every 3rd tick (~30s): sovereign asset maintenance. NO backend
            // call on the standing path — /api/assets/unspent is reconcile-only
            // now. Guarded so a slow cycle on a long-history wallet can't stack.
            if (tickCount % 3L == 0L && NativeBridge.getPeerCount() > 0
                && assetMaintenanceRunning.compareAndSet(false, true)) {
                launch {
                    try {
                        runCatching { assetManager.sweepKnownTransactionsForAssets() }
                            .onFailure { android.util.Log.w("SyncService", "native sweep threw", it) }
                        // Re-derive each asset row's spent flag from the native wallet.
                        // Nothing else on the standing path does: it used to ride along
                        // with the backend asset refresh, which bailed before reaching it.
                        // A stale `spent = true` HIDES a real holding, and only this
                        // clears it. Local only — no address disclosure.
                        runCatching { assetManager.reconcileAssetRowsLocally() }
                            .onFailure { android.util.Log.w("SyncService", "asset row reconcile threw", it) }
                        if (assetPruneGateOpen(
                                syncedThisSession = syncedThisSession,
                                peerCount = NativeBridge.getPeerCount(),
                                progress = currentSyncProgress(),
                                walletLoaded = NativeBridge.isWalletLoaded(),
                            )) {
                            // Rows from a broadcast that never confirmed and has since
                            // been dropped from the wallet. clearDeadAssetSend can't reach
                            // these: it needs the tx present to enumerate its outputs, and
                            // by now it is gone. Never-confirmed only, so a below-scan-floor
                            // holding (which carries a real height) is never touched.
                            runCatching { assetManager.pruneDeadBroadcastRows() }
                                .onFailure { android.util.Log.w("SyncService", "dead-broadcast prune threw", it) }
                            runCatching { assetManager.pruneRemovedNativeAssetRows() }
                                .onFailure { android.util.Log.w("SyncService", "asset prune threw", it) }

                            // Sovereign owned-script phantom prune — the CHANG "21 for a
                            // supply-10 asset" over-count. Deletes asset rows at addresses we
                            // don't own (recipient markers from transfers we sent, which
                            // getAssetBalances still sums). This exact check used to run on the
                            // now-retired backend refresh; re-homed here onto the standing path.
                            // Live delete (dry-run confirmed on-device); ownership is the sole
                            // delete authority so an owned row can never be destroyed here.
                            runCatching {
                                // Delete not-owned recipient-marker phantoms (the CHANG over-count
                                // residue). Ownership-only — owned rows are never touched here (a
                                // dropped native tx is pruneRemovedNativeAssetRows' debounced job; a
                                // below-floor backend holding reads a persistent native -1 and is a
                                // REAL holding). The on-device dry-run confirmed the not-owned set.
                                val res = assetManager.pruneUnownedAssetRows(dryRun = false)
                                android.util.Log.i("SyncService",
                                    "pruneUnowned ran: ${res.candidates.size} candidates, deleted=${res.deleted}")
                            }.onFailure { android.util.Log.w("SyncService", "pruneUnowned threw", it) }

                            // One-time (per install) heal of pre-existing owned-CHANGE-address
                            // asset orphans (the legacy Chang over-count, C9). Gated on the same
                            // prune gate — synced-this-session + connected + wallet loaded — plus
                            // a pref so it only ever runs once (or once per real, non-dry pass).
                            val healPrefs = getSharedPreferences("dgb_asset_heal", MODE_PRIVATE)
                            if (!healPrefs.getBoolean("legacy_done", false)) {
                                runCatching {
                                    val changeSet = assetManager.buildChangeScriptHexes()
                                    val res = assetManager.healLegacyChangeAddressOrphans(changeSet, dryRun = legacyHealDryRun)
                                    // Only mark done on a real (non-dry) pass, so the dry-run can be
                                    // observed across sessions until deletion is enabled.
                                    if (!legacyHealDryRun) healPrefs.edit().putBoolean("legacy_done", true).apply()
                                    android.util.Log.i("SyncService", "legacyHeal ran: ${res.candidates.size} candidates")
                                }.onFailure { android.util.Log.d("SyncService", "legacyHeal threw", it) }
                            }
                        }
                    } finally {
                        assetMaintenanceRunning.set(false)
                    }
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
                        var recreatedThisPass = false
                        zeroPeerStreak++
                        if (zeroPeerStreak >= ZERO_PEER_RECREATE_THRESHOLD) {
                            android.util.Log.w("SyncService",
                                "0 peers for $zeroPeerStreak cycles — light reconnect isn't recovering, " +
                                "forcing a clean peer-manager recreate")
                            // Flush, then refresh the window: this is a genuine recreate, and
                            // without the refresh the rebuild floors the chain to birth — while
                            // without the flush it resumes a save interval behind the live scan.
                            flushLiveStateBeforeRecreate()
                            runCatching { reloadSavedBlocksNearTip() }
                            try { NativeBridge.forceReconnect() } catch (_: Throwable) {}
                            recreatedThisPass = true
                            zeroPeerStreak = 0
                        }
                        android.util.Log.i("SyncService", "No peers connected, re-injecting filter peers and reconnecting")
                        injectPeers()
                        injectCustomNode()
                        NativeBridge.startSync()
                        // Only after an actual recreate: restoring the ledger snaps the
                        // resume cursor, which is pointless when the manager was untouched.
                        if (recreatedThisPass) runCatching { restoreCfLedgerAndSnap() }
                    }
                } else {
                    zeroPeerStreak = 0
                    if (torProxyActive) torReconnectFailures = 0
                    // Peers connected but sync may have stalled (download peer
                    // disconnected, remaining peers aren't driving the sync).
                    // Kick startSync to reassign a download peer.
                    if (!hasReachedSynced && currentSyncProgress() < 1.0f) {
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
                    // CF-FIRST GATE (fund visibility). `height` is the BLOCK-HEADER
                    // tip, and in COMPACT_FILTERS_ONLY that is NOT what determines
                    // whether the wallet has actually looked at a block for payments.
                    // The convoy deliberately runs the header frontier AHEAD of the
                    // compact-filter scan, so header-tip alone reports "synced" while
                    // the scan is still far below.
                    //
                    // Measured on a Note 8, 2026-08-02, and the reason this exists:
                    //   At chain tip (height=23961817 est=23961817) — marking complete
                    //   cf-ledger: scannedThrough=23901000
                    // i.e. Complete was declared with 60,817 blocks NEVER SCANNED. A
                    // payment anywhere in that window would have been invisible while
                    // the UI said the balance was final.
                    //
                    // getLowestNeededHeight() is the lowest height the scan still needs,
                    // so (it - 1) is the highest fully-scanned height. Require that to be
                    // at the tip too. Fail CLOSED: a 0/failed read means "unknown", which
                    // must never be treated as caught up.
                    val scanFrontier = try { NativeBridge.getLowestNeededHeight() }
                                       catch (_: Throwable) { 0L }
                    val scanAtTip = scanFrontier > 0L && (scanFrontier - 1L) >= estHeight - CF_TIP_SLACK
                    val atRealTip = height >= LATEST_CHECKPOINT_HEIGHT &&
                                    height >= estHeight - 5 &&
                                    scanAtTip
                    if (!atRealTip) atTipConsecutivePolls = 0
                    if (height >= LATEST_CHECKPOINT_HEIGHT && height >= estHeight - 5 && !scanAtTip) {
                        // RATE-LIMITED: this condition holds for the WHOLE remaining scan —
                        // potentially hours — and the poll loop runs every 10s. Logging it
                        // per poll is the same flood this file already learned to avoid for
                        // per-block cfilter lines (it starved logd and plausibly the binder
                        // buffer on the acceptance rig). Log only when the unscanned count
                        // moves materially, so progress stays visible without the spam.
                        val unscanned = estHeight - scanFrontier + 1
                        if (lastUnscannedLogged == 0L ||
                            kotlin.math.abs(unscanned - lastUnscannedLogged) >= UNSCANNED_LOG_DELTA) {
                            lastUnscannedLogged = unscanned
                            android.util.Log.i("SyncService",
                                "headers at tip ($height) but CF scan frontier is $scanFrontier " +
                                "($unscanned blocks unscanned) — NOT marking complete")
                        }
                    }
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
                            if (syncCompletionInFlight.compareAndSet(false, true)) {
                                try {
                                    if (persistSyncCompletionState()) {
                                        hasReachedSynced = true
                                        // Co-located with hasReachedSynced: this poll-loop
                                        // fallback is a REAL this-session completion site.
                                        syncedThisSession = true
                                        walletManager.updateSyncState(
                                            io.digibyte.core.model.SyncState.Complete,
                                        )
                                        android.util.Log.i(
                                            "SyncService",
                                            "At chain tip (height=$height est=$estHeight) for " +
                                                "${atTipConsecutivePolls * 10}s — marking complete",
                                        )
                                    } else {
                                        atTipConsecutivePolls = 0
                                        android.util.Log.w(
                                            "SyncService",
                                            "At tip but transaction checkpoint failed — keeping sync incomplete",
                                        )
                                    }
                                } finally {
                                    syncCompletionInFlight.set(false)
                                }
                            }
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
                updateNotification(currentSyncProgress(), peers)
                } catch (t: Throwable) {
                    // Swallow and continue. Losing a single 10s tick is fine;
                    // killing the poll forever is not. Log with stack so the
                    // underlying issue is still visible in bug reports.
                    android.util.Log.e("SyncService", "peer-keepalive tick threw — continuing", t)
                }
            }
        }

    /**
     * BIP 158 watchdog. The wallet always runs compact-filters-only sync
     * (bloom/BIP37 is removed as a data path — the address set never goes on
     * the wire). The compact-filter peer pool is small (the seeder currently
     * advertises ~3 peers), so if all of them are unreachable through Tor or
     * down, the wallet would otherwise sit "Connecting…" forever.
     *
     * This watchdog waits BIP158_FALLBACK_TIMEOUT_MS after sync start; if the
     * cfheaders chain hasn't progressed past the configured birth height, its
     * only recovery is a one-time re-anchor at the block floor
     * (reanchorCompactFilterChainAtFloor). If that isn't warranted or doesn't
     * land within its grace window, the watchdog stays on compact filters and
     * gives up gracefully for the session — it never degrades to bloom.
     */
    /**
     * Block-header-tip-stall watchdog — the ONLY proactive re-kick for header sync.
     *
     * Every native getheaders sender is reactive (sync-start, relayed inv/orphan,
     * forward-only continuation). Once the wallet idles at a stale estimatedHeight,
     * a tip with live-but-silent peers (half-dead socket answering pings, a
     * non-announcing or lagging download peer) freezes forever — no tx confirms for
     * days, surviving restarts. Every existing recovery is disqualified: the
     * peers==0 watchdogs never fire (peers stay connected) and the BIP158 watchdog
     * certifies the frozen state "healthy" (or needs a climbing header chain it
     * doesn't have). Runs independently for the whole session so the BIP158
     * watchdog's early "healthy" return can't take it down with it.
     *
     * Tier 1 (scan frozen >= TIP_STALL_TIMEOUT_MS, peers connected): re-issue a
     *   full-locator getheaders — un-sticks behind-and-stopped and connectable
     *   dead-branch. Benign 0-header no-op on a healthy at-tip wallet.
     * Tier 2 (still frozen a full window after Tier 1): recreate the manager
     *   (forceReconnect + re-inject + startSync) — a fresh handshake cohort, for a
     *   dead-branch whose current peers won't serve the real chain. Throttled.
     *
     * PACED-CONVOY RE-KEY (spec Part D). Every tier now arms on the CF SCAN frontier
     * (`getLowestNeededHeight`), not the block-header tip: the convoy holds the header
     * frontier at `scanFrontier + CF_CONVOY_WINDOW` on purpose, so a frozen tip is the
     * pacing signal, and tier 1's UNGATED getheaders / tier 2's manager recreate would
     * be pure churn — or outright destructive — mid-descent. Two convoy guards ride on
     * top: (a) a RESIDUAL dead-branch conjunct — while the window is FULL the header
     * frontier is pinned by the gate, so escalation additionally requires the raw block
     * tip to be frozen too (if it is still re-kicking, the header layer is alive and a
     * scan stall belongs to the filter layer / the B2 valve); (b) for TIER 2 ONLY, the
     * LIVENESS gate — it frees and recreates the manager, which re-Inits the CF scan
     * ledger at the remembered floor, so it stands down while the B2 valve is inside its
     * budget and re-arms once the frontier has been pinned right through it. Tier 1 and
     * the FAST canon pin are NOT suppressible: they destroy nothing, and gating them on
     * "a height is unresolved" disabled them permanently in the roaming/dead-branch
     * wedges they exist to cure (fix wave C2). See [isConvoySuppressed].
     *
     * Resets on any CF scan advance; DGB's ~15s blocks are scanned as they arrive so a
     * healthy at-tip wallet resets it constantly and never arms it (20 min ≈ 80 blocks).
     */
    private fun startTipStallWatchdog() {
        tipStallWatchdogJob?.cancel()
        tipStallWatchdogJob = serviceScope.launch {
            // PACED-CONVOY RE-KEY (spec Part D). The convoy DELIBERATELY freezes the
            // block-header frontier at scanFrontier + CF_CONVOY_WINDOW, so
            // getLastBlockHeight() is no longer a liveness signal — it is the pacing
            // signal. The CF SCAN frontier (getLowestNeededHeight) is what actually
            // proves the wallet is making progress, so arm/reset on THAT. The raw tip
            // is still tracked, for the residual dead-branch conjunct below.
            //
            // NOT getCfScanLedgerCounts()[0]: that index is scannedThrough, which LAGS
            // after a B2 abandonment (abandonedBelow is raised without _cfLedgerAdvance
            // running), so a watchdog keyed on it would read a genuinely-progressing
            // convoy as frozen and fire tier 1 — the exact misfire this re-key prevents.
            //
            // All of the tracking (signal selection, the tier-1 latch, and the
            // frontier-RE-INIT handling that keeps a post-recovery re-climb from reading
            // as an unbounded stall) lives in the pure [TipStallState] so it is testable
            // on the host JVM — NativeBridge is JNI and cannot be mocked there.
            var state = initialTipStallState(
                tip = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L },
                scan = try { NativeBridge.getLowestNeededHeight() } catch (_: Throwable) { 0L },
                nowMs = System.currentTimeMillis(),
            )
            // Read ONCE from native — both are compile-time constants over there, so a
            // per-poll JNI round trip would buy nothing. See [nativeConvoyWindow].
            val convoyWindow = nativeConvoyWindow()
            val suppressionMaxMs = nativeConvoySuppressionMaxMs()
            var lastTier2Ms = 0L
            var lastFastMs = 0L          // fast-tier (orphan / can't-hold-filter-peer) throttle
            var pinRotation = 0          // rotate through the validated filter pool when pinning
            var pinnedThisStall = false  // did we pin a canon filter peer for this stall?
            while (true) {
                kotlinx.coroutines.delay(TIP_STALL_WATCHDOG_POLL_MS)
                val tip = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L }
                val scan = try { NativeBridge.getLowestNeededHeight() } catch (_: Throwable) { 0L }
                val peers = try { NativeBridge.getPeerCount() } catch (_: Throwable) { 0 }
                val pendingCycles = try { NativeBridge.getConvoyAbandonmentPending() } catch (_: Throwable) { 0 }
                val nowMs = System.currentTimeMillis()

                state = state.step(tipNow = tip, scanNow = scan, nowMs = nowMs)
                if (state.progressed) {
                    // Recovered (or legitimately re-initialised at a new floor) — release
                    // any pinned canon peer so the pool re-diversifies. The tier-1 latch
                    // is cleared inside [TipStallState.step].
                    if (pinnedThisStall) {
                        runCatching { NativeBridge.clearPinnedPeer() }
                        pinnedThisStall = false
                    }
                }

                val tier1Fired = state.tier1Fired
                val scanStalledMs = state.scanStalledMs(nowMs)
                val blockTipStalledMs = state.blockTipStalledMs(nowMs)
                val windowFull = isConvoyWindowFull(tip, scan, convoyWindow)

                if (shouldForceReconnectOnStall(
                        peerCount = peers,
                        scanStalledMs = scanStalledMs,
                        blockTipStalledMs = blockTipStalledMs,
                        convoyWindowFull = windowFull,
                        tier1Fired = tier1Fired,
                        abandonmentPendingCycles = pendingCycles,
                        suppressionMaxMs = suppressionMaxMs,
                    ) && nowMs - lastTier2Ms >= TIP_STALL_TIER2_THROTTLE_MS
                ) {
                    android.util.Log.w(
                        "SyncService",
                        "tip-stall: CF scan frontier still frozen at ${state.lastScan} (block tip $tip, " +
                            "W_hdr full=$windowFull) for ${scanStalledMs / 1000}s after " +
                            "re-request ($peers peers) — recreating peer manager (tier 2)"
                    )
                    lastTier2Ms = nowMs
                    recreatePeerManagerResumingNearTip("recovery")
                    state = state.copy(tier1Fired = false) // re-arm tier 1 against the fresh manager
                } else if (shouldRerequestHeadersOnStall(
                        peerCount = peers,
                        scanStalledMs = scanStalledMs,
                        blockTipStalledMs = blockTipStalledMs,
                        convoyWindowFull = windowFull,
                    ) && !tier1Fired
                ) {
                    android.util.Log.w(
                        "SyncService",
                        "tip-stall: CF scan frontier frozen at ${state.lastScan} (block tip $tip, " +
                            "W_hdr full=$windowFull) for ${scanStalledMs / 1000}s with $peers " +
                            "peers — proactively re-requesting headers (tier 1)"
                    )
                    runCatching { NativeBridge.rerequestHeadersFromTip() }
                    state = state.copy(tier1Fired = true)
                } else if (shouldFastRecoverOnStall(
                        peerCount = peers,
                        scanStalledMs = scanStalledMs,
                        blockTipStalledMs = blockTipStalledMs,
                        convoyWindowFull = windowFull,
                        thresholdMs = TIP_STALL_FAST_MS,
                    ) && nowMs - lastFastMs >= TIP_STALL_FAST_MS
                ) {
                    // FAST tier — connected but the CF scan frontier hasn't advanced for a
                    // few minutes (multi-algo DGB mines every ~15s, so this is genuinely
                    // stuck, not slow — and under the convoy the scan, not the paced block
                    // tip, is the thing that must keep moving).
                    // Two causes, both handled: (1) a short ORPHAN — a full-locator getheaders
                    // walks back + reorgs off it; (2) ROAMING — the wallet holds peers but not a
                    // filter-capable one, so PIN a validated canon CF peer (rotating) to lock it
                    // on instead of churning the junk pool. The pin is released on advance above.
                    lastFastMs = nowMs
                    runCatching { NativeBridge.rerequestHeadersFromTip() }
                    val fp = nextValidatedFilterPeer(pinRotation++)
                    if (fp != null) {
                        android.util.Log.i(
                            "SyncService",
                            "tip-stall FAST: scan frozen ${scanStalledMs / 1000}s, $peers peers — re-request " +
                                "headers + pinning canon filter peer ${fp.first}:${fp.second}"
                        )
                        runCatching { NativeBridge.setPinnedPeer(fp.first, fp.second, false) }
                        pinnedThisStall = true
                    } else {
                        android.util.Log.i(
                            "SyncService",
                            "tip-stall FAST: scan frozen ${scanStalledMs / 1000}s, $peers peers — re-request " +
                                "headers (no validated filter peer in pool to pin)"
                        )
                    }
                }
            }
        }
    }

    /**
     * Live native `CF_CONVOY_WINDOW`, read from the `.so` rather than mirrored in
     * Kotlin (fix-wave I-3). A Kotlin copy that drifts LOW makes [isConvoyWindowFull]
     * read "window not full", which drops the tip-frozen conjunct and arms tier 1 /
     * tier 2 during a HEALTHY paced descent.
     */
    private fun nativeConvoyWindow(): Long {
        val w = try {
            NativeBridge.getConvoyWindow().toLong()
        } catch (t: Throwable) {
            android.util.Log.w("SyncService",
                "convoy: getConvoyWindow() unavailable — falling back to the Kotlin mirror " +
                "$CF_CONVOY_WINDOW_FALLBACK; if the native window was retuned, the watchdogs " +
                "will misread the gate", t)
            return CF_CONVOY_WINDOW_FALLBACK
        }
        if (w <= 0L) {
            android.util.Log.w("SyncService",
                "convoy: getConvoyWindow() returned $w — falling back to $CF_CONVOY_WINDOW_FALLBACK")
            return CF_CONVOY_WINDOW_FALLBACK
        }
        return w
    }

    /**
     * Live suppression CEILING in wall clock, DERIVED from the native
     * `CF_CONVOY_REARM_MAX` (fix-wave I-3, re-based on a clock by fix wave C2).
     *
     * The ceiling has to track the native re-arm budget in BOTH directions. Too SMALL
     * and the tip-stall watchdog escalates into a still-productive B2 valve (tier 2
     * recreates the manager mid-descent); too LARGE — the unbounded form this replaces
     * — and every destructive tier is stood down forever in exactly the wedge states it
     * exists to cure. Reading the budget from the `.so` and sizing the clock off it
     * (see [convoySuppressionMaxMs]) keeps both ends honest without a hand-mirrored
     * constant that can drift.
     */
    private fun nativeConvoySuppressionMaxMs(): Long {
        val n = try {
            NativeBridge.getConvoyRearmMax()
        } catch (t: Throwable) {
            android.util.Log.w("SyncService",
                "convoy: getConvoyRearmMax() unavailable — falling back to a suppression ceiling of " +
                "${CONVOY_SUPPRESSION_MAX_MS_FALLBACK}ms; if the native re-arm budget was raised, " +
                "the watchdogs may escalate into a still-productive B2 valve", t)
            return CONVOY_SUPPRESSION_MAX_MS_FALLBACK
        }
        if (n <= 0) {
            android.util.Log.w("SyncService",
                "convoy: getConvoyRearmMax() returned $n — falling back to " +
                "${CONVOY_SUPPRESSION_MAX_MS_FALLBACK}ms")
            return CONVOY_SUPPRESSION_MAX_MS_FALLBACK
        }
        return convoySuppressionMaxMs(n)
    }

    /**
     * Rotate through the validated filter-peer pool (`dgb_filter_peers`, the canon set)
     * for pinning when real-time sync holds peers but can't hold a CF one. Returns
     * (ip, port) or null if the pool is empty (native then falls back to discovery).
     */
    private fun nextValidatedFilterPeer(rotation: Int): Pair<String, Int>? {
        val prefs = getSharedPreferences(
            "dgb_filter_peers" + networkSuffix(this@SyncService), MODE_PRIVATE
        )
        val pool = prefs.getString("peer_pool", null)?.let { parsePool(it) } ?: return null
        if (pool.isEmpty()) return null
        val (ip, port, _) = pool[((rotation % pool.size) + pool.size) % pool.size]
        return ip to port
    }

    private fun startBip158Watchdog(birthHeight: Long) {
        bip158WatchdogJob?.cancel()
        // Polls every BIP158_WATCHDOG_POLL_MS, tracking whether the cfheaders
        // chain is keeping pace with the block chain. Three exit cases (bloom is
        // removed as a data path — none of them ever fall back to it):
        //   1. cfTip caught up to within 100 blocks of blockTip → healthy, return.
        //   2. blockTip is meaningfully ahead and cfheaders is stuck → try the
        //      one-time re-anchor recovery, then stay on compact filters either way.
        //   3. Neither yet — keep polling (header sync still catching up, or
        //      awaiting the first cfheaders append after a re-anchor).
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
            // Testnet26 nodes have bloom disabled and RESET on a bloom filterload, so
            // bloom is never a valid fallback there — the watchdog must stay on compact
            // filters (re-anchor recovery below still applies). Mainnet is unchanged.
            val testnet = isTestnet(this@SyncService)
            // A configured own-node forces COMPACT_FILTERS_ONLY (syncModeFor) so no
            // bloom filterload ever goes on the wire — mirror the testnet treatment
            // exactly so the watchdog can never flip to bloom while the toggle is on.
            val customNode = CustomNodePrefs.isEnabled(this@SyncService)
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
            // watchdog from reading that transient 0 as "dead" and giving up early.
            var reanchorAtMs = 0L
            // CF-wedge (cfheaders-frozen) recovery state. cfNetMax is the session
            // running-MAX cfTip — NOT the current value — so the native chain
            // oscillating 0↔N on each continuity re-anchor can't reset the frozen
            // timer. cfNetProgressMs is the wall-clock of the last net gain. The
            // recovery fires at most once per session (like reanchoredThisSession).
            var cfNetMax = cfTipAtStart
            var cfNetProgressMs = startedAt
            var cfFrozenRecoveredThisSession = false
            // Clean-slate corrupt-chain heal state: how many full-wipe heals have run
            // this session (bounded by MAX_CF_CORRUPT_HEALS), the last heal's timestamp
            // (throttle), and a rotation cursor so each heal pins a DIFFERENT canon peer.
            var corruptHeals = 0
            var lastCorruptHealMs = 0L
            var corruptHealRotation = 0
            // Live native suppression ceiling, read ONCE (compile-time constant natively).
            // See [nativeConvoySuppressionMaxMs] — every DESTRUCTIVE branch below is gated
            // on it, so a stale hand-mirrored copy would either escalate into a valve that
            // is still legitimately working or (the C2 regression) never escalate at all.
            val suppressionMaxMs = nativeConvoySuppressionMaxMs()
            // PACED-CONVOY RE-KEY (spec Part D). Every DESTRUCTIVE branch below
            // (frozen-CF recovery, corrupt-chain heal, post-timeout re-anchor) deletes
            // persisted filter/ledger state, so each is now additionally gated on the CF
            // SCAN frontier being frozen. Under the convoy, cfTip freezing while the
            // block tip climbs is a NORMAL, designed decoupling — the old keying read it
            // as a wedge and threw the whole descent away. Tracked as a session
            // running-max for the same reason cfNetMax is — EXCEPT that a regression to a
            // real floor is adopted as a legitimate re-init (see [stepScanFrontier]).
            // The explicit resets after this loop's own destructive branches cover only
            // the re-inits IT causes; a deep reorg re-inits the ledger natively
            // (BRPeerManager.c:4035) with no branch involved, and a forward-only tracker
            // would then read the whole clean re-climb as one unbounded freeze.
            var scanNetMax = try { NativeBridge.getLowestNeededHeight() } catch (_: Throwable) { 0L }
            var scanProgressMs = startedAt
            while (true) {
                kotlinx.coroutines.delay(BIP158_WATCHDOG_POLL_MS)
                val cfTipNow = try { NativeBridge.getCFChainTipHeight() } catch (_: Throwable) { 0 }
                val blockTip = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L }
                val gap = blockTip - cfTipNow.toLong()
                val elapsedMs = System.currentTimeMillis() - startedAt
                val cfAdvancedSinceStart = cfTipNow > cfTipAtStart

                // How far the header chain is from the network tip. Computed here
                // (before the healthy check, not just in the deficit branch below)
                // because "block chain is at the network tip" is part of healthy.
                val estHeight = try { NativeBridge.getEstimatedBlockHeight() } catch (_: Throwable) { 0L }
                val blocksCaughtUp = estHeight > 0L && blockTip >= estHeight - BLOCK_CATCHUP_GRACE

                // Healthy = cfTip within HEALTHY_CF_GAP_BLOCKS of blockTip AND either
                // it advanced this session OR the block chain is at the network tip.
                // The blocksCaughtUp disjunct fixes the false "Privacy degraded": a
                // wallet already fully filter-synced at launch never advances cfTip
                // (nothing new to fetch), so the advance-only check mislabeled it
                // "stuck" and degraded a synced wallet to bloom. See isFilterSyncHealthy.
                if (isFilterSyncHealthy(gap, cfAdvancedSinceStart, blocksCaughtUp)) {
                    android.util.Log.i("SyncService",
                        "BIP158 watchdog: healthy (cfTip $cfTipAtStart→$cfTipNow, " +
                        "blockTip=$blockTip, gap=$gap, blocksCaughtUp=$blocksCaughtUp, after ${elapsedMs}ms)")
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

                // CF-wedge recovery — checked BEFORE the `advanced` short-circuit
                // below (which is fooled by the native chain rebuilding 0→N after a
                // continuity re-anchor and reads it as "progress") and independent of
                // blocksCaughtUp (the wedge happens WHILE headers import, so that
                // short-circuit is structurally blind to it). Track the session
                // running-MAX cfTip: if it stops climbing for CF_FROZEN_RECOVERY_MS
                // while headers are still importing, cfheaders is stuck in a
                // continuity re-anchor loop that never converges — recover ONCE by
                // dropping the diverged persisted chain and forcing a clean manager
                // recreate (fresh peers + reset native CF continuity budget), which
                // re-fetches cfheaders from the floor.
                if (cfTipNow > cfNetMax) { cfNetMax = cfTipNow; cfNetProgressMs = nowMs }

                // CF SCAN frontier progress — the convoy-era liveness signal shared by
                // all three destructive branches below, plus the B2 valve's cycle count
                // (a gaveUp hole PINS the scan frontier by construction, so scan-frozen
                // alone would hand the valve's own stall to the branch that deletes the
                // ledger it is deciding on). The suppression is BOUNDED — see
                // [isConvoySuppressed].
                val scanNow = try { NativeBridge.getLowestNeededHeight() } catch (_: Throwable) { 0L }
                val scanStep = stepScanFrontier(scanNetMax, scanProgressMs, scanNow, nowMs)
                scanNetMax = scanStep.frontier
                scanProgressMs = scanStep.lastChangeMs
                val scanStalledMs = nowMs - scanProgressMs
                val pendingCycles = try { NativeBridge.getConvoyAbandonmentPending() } catch (_: Throwable) { 0 }

                if (shouldRecoverFrozenCf(
                        blockClimbing, nowMs - cfNetProgressMs, cfNetMax, cfFrozenRecoveredThisSession,
                        scanStalledMs = scanStalledMs, abandonmentPendingCycles = pendingCycles,
                        suppressionMaxMs = suppressionMaxMs)) {
                    cfFrozenRecoveredThisSession = true
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: cfTip WEDGED at net-max $cfNetMax for " +
                        "${(nowMs - cfNetProgressMs) / 1000}s while blockTip climbs ($blockTip) — " +
                        "dropping diverged filter chain + recreating manager to re-fetch cfheaders")
                    // A wedged filter chain is a FILTER-CHAIN problem. The scan ledger
                    // records which ranges this wallet already scanned against its own
                    // watch set, which is still true — and it is what lets the recreate
                    // resume near tip instead of at the birth floor. See CfRecoveryPolicy.
                    val policy = io.digibyte.core.sync.CfRecoveryPolicy.decide(
                        io.digibyte.core.sync.CfRecoveryPolicy.Reason.FILTER_CHAIN_WEDGED)
                    if (policy.dropFilterChain) {
                        FilterHeaderStore.delete(this@SyncService)
                        pendingFilterHeaders = null
                        filterHeadersDirty = false
                    }
                    if (policy.dropScanLedger) {
                        CfScanLedgerStore.delete(this@SyncService)
                        pendingCfLedger = null
                    }
                    recreatePeerManagerResumingNearTip("cf-chain wedged")
                    // Only meaningful when the ledger WAS dropped and the frontier re-inits
                    // at the floor; harmless otherwise, and the running-max must not keep
                    // the scan-frozen gate satisfied through a clean re-climb.
                    if (policy.dropScanLedger) scanNetMax = 0
                    scanProgressMs = nowMs
                    continue
                }

                // cfheaders is actively riding the header chain — healthy.
                if (advanced) {
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
                // BIP158 always fall back. (estHeight/blocksCaughtUp computed above,
                // before the healthy check.)
                if (!blocksCaughtUp) {
                    // Bloom (BIP37) is removed as a data path: a header-sync stall never
                    // falls back to a bloom filterload. Stay on compact filters and keep
                    // retrying — the block-header path recovers via peer rotation and the
                    // cfheaders re-anchor below, never by leaking the address set to bloom.
                    android.util.Log.d("SyncService",
                        "BIP158 watchdog: header sync still catching up " +
                        "(blockTip=$blockTip, est=$estHeight, cfTip=$cfTipNow) — staying on filters (${elapsedMs}ms)")
                    continue
                }

                // POISONED-CHAIN HEAL — the clean-slate escalation. If, AFTER the
                // one-time re-anchor below has already fired and been given its grace,
                // cfheaders is STILL frozen at a fixed height with headers at the tip
                // and filter peers connected, the wallet's own persisted filter chain
                // is corrupt (a prior build wrote bad chain data — the class of wedge
                // that left many wallets stuck at a fixed cfheaders block forever). The
                // ordinary re-anchor gives up after one try and never switches peers;
                // this wipes ALL persisted filter state and re-fetches a CLEAN chain
                // from a freshly-pinned canon peer, rotating the peer each attempt.
                val peersNow = try { NativeBridge.getPeerCount() } catch (_: Throwable) { 0 }
                if (shouldHealCorruptFilterChain(
                        blocksCaughtUp = blocksCaughtUp,
                        peerCount = peersNow,
                        cfFrozenMs = nowMs - cfNetProgressMs,
                        reanchored = reanchoredThisSession,
                        msSinceReanchor = nowMs - reanchorAtMs,
                        healsSoFar = corruptHeals,
                        scanStalledMs = scanStalledMs,
                        abandonmentPendingCycles = pendingCycles,
                        suppressionMaxMs = suppressionMaxMs,
                    ) && nowMs - lastCorruptHealMs >= CF_CORRUPT_HEAL_COOLDOWN_MS
                ) {
                    corruptHeals++
                    lastCorruptHealMs = nowMs
                    val fp = nextValidatedFilterPeer(corruptHealRotation++)
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: filter chain WEDGED at $cfTipNow after re-anchor " +
                        "(heal $corruptHeals/$MAX_CF_CORRUPT_HEALS) — persisted chain looks corrupt; " +
                        "wiping ALL filter state + clean re-fetch" +
                        (if (fp != null) " via pinned canon ${fp.first}:${fp.second}" else " (no canon peer cached)"))
                    // Still wedged THROUGH a re-anchor: the persisted chain is not merely
                    // stale, and the scan record derived alongside it is no longer
                    // trustworthy either — CfRecoveryPolicy.FILTER_CHAIN_CORRUPT is the one
                    // reason that legitimately takes the ledger too.
                    // Clear EVERY persisted filter-chain source (the one-time re-anchor
                    // only deletes the file store): file, in-memory pending copy, dirty flag.
                    FilterHeaderStore.delete(this@SyncService)
                    pendingFilterHeaders = null
                    filterHeadersDirty = false
                    CfScanLedgerStore.delete(this@SyncService)
                    pendingCfLedger = null
                    // Reset the native CF chain to the floor, then force a clean manager
                    // recreate that re-fetches cfheaders from a fresh peer cohort.
                    runCatching { NativeBridge.reanchorCompactFilterChainAtFloor() }
                    recreatePeerManagerResumingNearTip("recovery")
                    // Prefer a fully-synced canon peer for the clean re-fetch (best-effort;
                    // injectPeers already re-seeds the 16 canon nodes into the pool).
                    if (fp != null) runCatching { NativeBridge.setPinnedPeer(fp.first, fp.second, false) }
                    // Reset the frozen tracker: the clean re-fetch is a LEGITIMATE slow
                    // re-climb from the floor. Without this, cfNetMax stays pinned at the
                    // old wall so the growing frozen timer would re-heal mid-climb in a
                    // loop and never finish. Any forward progress now updates the timer.
                    cfNetMax = 0
                    cfNetProgressMs = nowMs
                    lastCfTip = 0
                    // Same reset for the scan frontier: the heal deletes CfScanLedgerStore,
                    // so the ledger re-inits at the floor and the frontier legitimately
                    // restarts low. Without this the stale scanNetMax would keep the
                    // scan-frozen gate satisfied through the whole clean re-climb.
                    scanNetMax = 0
                    scanProgressMs = nowMs
                    continue
                }

                // Headers are caught up to the network tip but cfheaders still
                // isn't advancing. Before giving up for the session, try a one-time
                // re-anchor: a legacy wallet can have cfTip persisted far below the
                // block floor (the gap was never re-downloaded), which retention
                // can't bridge. Re-anchoring discards the stuck chain and restarts
                // filters at the floor. The skipped gap was already bloom-scanned —
                // gated on hasReachedSynced, which is that guarantee.
                if (elapsedMs >= BIP158_FALLBACK_TIMEOUT_MS) {
                    when (decidePostTimeoutAction(
                        hasReachedSynced, reanchoredThisSession, nowMs - reanchorAtMs,
                        scanStalledMs = scanStalledMs, abandonmentPendingCycles = pendingCycles,
                        suppressionMaxMs = suppressionMaxMs,
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
                                // Drop the persisted chain (file + legacy key) and the
                                // pending in-memory copy so a kill before the first
                                // re-anchored append can't restore the stuck cfTip.
                                val reanchorPolicy = io.digibyte.core.sync.CfRecoveryPolicy.decide(
                                    io.digibyte.core.sync.CfRecoveryPolicy.Reason.REANCHORED)
                                if (reanchorPolicy.dropFilterChain) {
                                    FilterHeaderStore.delete(this@SyncService)
                                    pendingFilterHeaders = null
                                    filterHeadersDirty = false
                                }
                                // Re-anchoring rebuilds the CHAIN from a floor. It says
                                // nothing about which ranges this wallet already scanned,
                                // and that record is what keeps the resume point near tip.
                                if (reanchorPolicy.dropScanLedger) {
                                    CfScanLedgerStore.delete(this@SyncService)
                                    pendingCfLedger = null
                                    scanNetMax = 0
                                }
                                scanProgressMs = nowMs
                                android.util.Log.i("SyncService",
                                    "BIP158 watchdog: re-anchored filter chain at block floor " +
                                    "(cfTip was $cfTipNow, below floor) — staying on filters")
                                continue
                            }
                            // re-anchor returned false (cfTip not actually below the
                            // floor) — nothing left to try; stay on filters below.
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
                        PostTimeoutAction.STAY_ON_FILTERS -> {
                            // Nothing left to try this session — logged below.
                        }
                    }
                    // Bloom (BIP37) is removed as a data path. cfheaders stuck is recovered
                    // ONLY by the one-time re-anchor above — never by a bloom filterload,
                    // which would leak the whole address set. Stay on compact filters.
                    android.util.Log.d("SyncService",
                        "BIP158 watchdog: cfheaders stuck at $cfTipNow (gap=$gap) — staying on " +
                        "filters (bloom removed; re-anchor is the only recovery)")
                    continue
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
     * Exits silently once peers > 0 — the BIP158 watchdog handles compact-filter
     * health separately (re-anchor recovery only, no bloom fallback); this one
     * is solely about clearnet degradation.
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
            injectPeers()
            injectCustomNode()
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

        // Re-exclude asset-bearing outputs from the spendable DGB set. The native
        // exclusion list does not survive process restart, and the wallet is spendable the
        // moment it loads — well before the first 30s asset sweep would rebuild it. An
        // output carrying implicit change looks like ordinary DGB until this runs, so a
        // send inside that window could destroy an asset. Needs no peers: it runs off the
        // asset rows we already hold locally.
        runCatching { assetManager.replayAssetOutpointExclusions() }
            .onFailure { android.util.Log.w("SyncService", "asset exclusion replay failed", it) }

        // Load saved blocks and peers from previous session before syncing
        val prefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)

        // Validate + decode the stored hex blobs before handing raw bytes to the
        // native block/peer deserializer. A malformed blob (odd length, non-hex,
        // absurd size) is DROPPED and skipped rather than fed to native, where
        // corrupt bytes can SIGSEGV — a native crash no try/catch can catch.
        // Structurally corrupt-but-valid-hex blobs are caught by the BootGuard
        // crash-loop breaker (wipes sync state and re-syncs; seed preserved).
        var savedLedger = CfScanLedgerStore.load(this@SyncService)
        // File-backed (I2 fix): saved_blocks moved out of dgb_sync_data (hex String
        // pinned in the SharedPreferencesImpl in-memory map) into a plain file,
        // mirroring FilterHeaderStore. SavedBlockStore.load() also performs the
        // one-time legacy-hex migration and drops the malformed/oversized case.
        var blockBytes = SavedBlockStore.load(this@SyncService)
        val hasTransactionCheckpoint =
            prefs.getBoolean("transactions_checkpointed", false) || prefs.contains("saved_transactions")
        val cfResetReason = cfRestoreResetReason(
            savedLedger,
            blockBytes,
            hasReachedSynced,
            hasTransactionCheckpoint,
        )
        if (cfResetReason != null) {
            // Capture the tip we are resetting FROM before the removes below clear it — the
            // breadcrumb below records how far back the reset threw the chain.
            val savedTipAtReset = prefs.getLong("saved_blocks_tip", 0L)
            android.util.Log.w(
                "SyncService",
                "CF restore preflight requires a header/filter rebuild ($cfResetReason); " +
                    "wallet keys and known transactions are preserved",
            )
            prefs.edit()
                .remove("saved_blocks")
                .remove("saved_blocks_tip")
                .remove("saved_filter_headers")
                .remove("has_synced")
                .commit()
            SavedBlockStore.delete(this@SyncService)
            FilterHeaderStore.delete(this@SyncService)
            CfScanLedgerStore.delete(this@SyncService)
            CfAbandonmentStore.clear(this@SyncService)
            pendingFilterHeaders = null
            filterHeadersDirty = false
            pendingCfLedger = null
            val birth = NativeBridge.getWalletBirthCheckpointHeight()
            getSharedPreferences("dgb_settings", MODE_PRIVATE).edit().apply {
                if (birth > 0L) putLong("cf_birth_height", birth) else remove("cf_birth_height")
            }.commit()
            // Durable diagnostic breadcrumb (DGB-1005): the reset reason is otherwise only in
            // Logcat, which is gone on reboot and unreachable for a report filed without device
            // access. Persist WHICH preflight condition fired, WHEN, and how far back it threw the
            // chain (from savedTip → birth), so a returning user's report — or a future
            // diagnostics view — can pinpoint the cause without a live logcat capture.
            getSharedPreferences("dgb_diag", MODE_PRIVATE).edit()
                .putString("last_cf_reset_reason", cfResetReason.name)
                .putLong("last_cf_reset_at_ms", System.currentTimeMillis())
                .putLong("last_cf_reset_birth", birth)
                .putLong("last_cf_reset_from_tip", savedTipAtReset)
                .apply()
            hasReachedSynced = false
            savedLedger = null
            blockBytes = null
        }
        // Pass the POST-preflight window explicitly (see reloadSavedBlocksNearTip):
        // a preflight that set blockBytes = null must not be undone by the helper
        // re-reading the file the preflight just deleted.
        reloadSavedBlocksNearTip(blockBytes)
        val peerBytes = decodeSavedBlobOrDrop(prefs, "saved_peers")
        if (peerBytes != null) {
            val loaded = NativeBridge.loadSavedPeers(peerBytes)
            android.util.Log.i("SyncService", "Loaded $loaded saved peers from disk")
        }

        // Re-dial penalties from the previous session. Without these a cold start
        // re-dials peers the last session already learned were behind — the churn the
        // penalty set exists to stop, reintroduced once per launch. Held at bridge level
        // so a later manager recreate inherits them; entries whose window has lapsed are
        // dropped on read, so a wallet that sat closed for an hour starts clean.
        prefs.getString("saved_peer_penalties", null)
            ?.let { io.digibyte.core.sync.PeerPenaltyPersist.decodeHex(it) }
            ?.let { blob ->
            runCatching { NativeBridge.loadPeerPenalties(blob) }
                .onSuccess { android.util.Log.i("SyncService", "Restored $it peer penalty/ies from disk") }
                .onFailure { android.util.Log.w("SyncService", "peer-penalty restore failed", it) }
        }

        // Release the peer-manager creation gate now that saved blocks/peers are
        // in the C core. Until this, any racing early startSync (onResume, the
        // active-screen wake, the keepalive) is deferred — otherwise it would
        // build the manager with no saved blocks and floor the chain at the
        // birth checkpoint (~480k-block re-sync every launch). Called
        // unconditionally so fresh wallets (no saved blocks) also proceed.
        NativeBridge.markSavedBlocksLoadComplete()

        // Restore-crash bracket: the risky post-unlock restore window (BootGuard
        // .beginRestore, set at WalletManager.restoreFromDisk()'s call sites) is
        // now provably clear of the corrupt-blob crash vector — loadSavedBlocks
        // (and loadSavedPeers) just ran without crashing and the C core has
        // consumed them. Clearing here — rather than right after
        // recoverWalletFromBytes in restoreFromDisk() — is deliberate: that's
        // still BEFORE the saved_blocks bytes are ever handed to the native
        // block deserializer, so it would miss the actual crash this bracket
        // exists to catch. A no-op if no restore is pending this launch (e.g.
        // sync starting after a plain UI-only unlock, or a fresh wallet).
        BootGuard.markRestoreHealthy(this@SyncService)

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

        // Inject filter-capable peers from the seeder API before starting sync.
        // This ensures the wallet has multiple compact-filter peers to try, not
        // just digiscope.me.
        injectPeers()
        // Inject the user's own node (if configured) as a priority compact-filter
        // peer. No-op unless the toggle is on; resolves DNS off the native peer lock.
        injectCustomNode()

        // ─── BIP 158 privacy-first sync ─────────────────────────────────────────
        // Sync mode is unconditionally COMPACT_FILTERS_ONLY — syncModeFor (CustomNode.kt)
        // ignores the `sync_mode` pref/customNodeEnabled/isTestnet args entirely and
        // always returns CF-only; the address set never goes on the wire under any
        // condition. The Settings → Sync Mode toggle/screen that used to select BOTH/
        // BLOOM_ONLY is removed. The `sync_mode` pref key below is read only for the
        // (dead) function-signature arg and otherwise ignored.
        val settings = getSharedPreferences("dgb_settings", MODE_PRIVATE)
        val syncMode = syncModeFor(
            pref = settings.getInt("sync_mode", NativeBridge.SyncMode.BOTH),
            customNodeEnabled = CustomNodePrefs.isEnabled(this@SyncService),
            isTestnet = isTestnet(this@SyncService)
        )
        NativeBridge.setSyncMode(syncMode)
        if (syncMode != NativeBridge.SyncMode.BLOOM_ONLY) {
            val savedFilters = FilterHeaderStore.load(this@SyncService)
            if (savedFilters != null) {
                val ok = NativeBridge.setCompactFilterChain(savedFilters)
                android.util.Log.i("SyncService",
                    "BIP158: restored filter chain (${savedFilters.size} bytes, ok=$ok)")
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
            // The 100-block margin exists to re-cover a shallow reorg around a SAVED tip.
            // It must NOT be applied to the birth checkpoint: on a fresh wallet that
            // checkpoint IS the lowest resident block, so `checkpoint - 100` asks for 100
            // heights that can never be resolved locally. They were duly surfaced as an
            // abandoned band — greeting every brand-new wallet with a non-dismissible
            // "scan for missing transactions" banner for blocks that predate the wallet
            // and cannot contain its funds. Measured on a Note 8 2026-08-02:
            //   [CF-SCAN] ABANDONED 100 height(s) [23899900..23899999] — unscannable
            val birthHeight = compactFilterBirthHeight(
                wasSynced = hasReachedSynced,
                savedTip = savedTip,
                walletBirth = NativeBridge.getWalletBirthCheckpointHeight(),
                persistedBirth = if (settings.contains("cf_birth_height")) {
                    settings.getLong("cf_birth_height", 0L)
                } else null,
            )

            // Auto-fetch is armed UNCONDITIONALLY, at every depth. The old
            // defense-in-depth branch here refused to arm for a birth floor deeper
            // than the native retention ceiling, which was exactly backwards: on the
            // deepest restores — the ones that need pacing most — it left the convoy
            // gate inert (autoFetchCFiltersEnabled stayed 0) and let header sync run
            // UNPACED, causing the very OOM the refusal claimed to avoid. Depth-based
            // refusal is gone (a backup you can't restore from isn't a backup); the
            // paced convoy makes any depth restorable, and arming it is what bounds
            // the memory. Unservable individual heights are handled downstream by the
            // B2 abandonment valve, not by refusing the restore.
            NativeBridge.enableAutoCompactFilterFetch(birthHeight)
            // Re-pin every Receive-screen address into the native BIP158 watch set so a
            // receive to an address that fell outside the derived gap window is still
            // scanned in every block (fixes not-confirming / undetected receives).
            val watched = (getSharedPreferences("dgb_watched_addrs", MODE_PRIVATE)
                .getStringSet("addrs", emptySet()) ?: emptySet()).toMutableSet()
            // NOTE: the DigiDollar receive address is deliberately NOT added here.
            //
            // v4.0.20 added it, claiming a DD receive would otherwise stay invisible until a
            // manual "Scan for missing funds". Both halves of that were wrong, and measured:
            // BRWalletAddWatchedAddress REJECTS a DD address (Base58Check over 34 bytes fails
            // BRAddressIsValid), so the entry was silently discarded on every sync start while
            // the log line below counted it — over-reporting the watch set by one and actively
            // misleading anyone debugging it. And it was never needed: a DD token output is a
            // plain P2TR script whose 34-byte element (OP_1 0x20 <X(Q)>) is already emitted by
            // taprootExternalChain[0], since the DD address encodes that same output key.
            // See WalletManager.getDigiDollarReceiveAddress and filter_elements_kat.
            if (watched.isNotEmpty()) {
                try { NativeBridge.addWatchedAddresses(watched.toTypedArray()) } catch (t: Throwable) {
                    android.util.Log.e("SyncService", "addWatchedAddresses threw", t)
                }
                android.util.Log.i("SyncService", "BIP158: pinned ${watched.size} watched receive address(es)")
            }
            android.util.Log.i("SyncService",
                "BIP158: mode=$syncMode, auto-fetch from height $birthHeight " +
                "(savedTip=$savedTip, syncedResume=$hasReachedSynced)")
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

        // Ledger restore + resume-cursor snap. MUST stay here, immediately after
        // startSync — see restoreCfLedgerAndSnap for the full ordering rationale.
        // Pass the POST-preflight ledger explicitly so a preflight reset is not
        // undone by the helper re-loading from disk.
        restoreCfLedgerAndSnap(savedLedger)

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
        val syncModeNow = syncModeFor(
            pref = settings.getInt("sync_mode", NativeBridge.SyncMode.BOTH),
            customNodeEnabled = CustomNodePrefs.isEnabled(this@SyncService),
            isTestnet = isTestnet(this@SyncService)
        )
        if (syncModeNow != NativeBridge.SyncMode.BLOOM_ONLY) {
            val savedTipForWatchdog = NativeBridge.getSavedBlocksTip()
            val birthHeightForWatchdog = compactFilterBirthHeight(
                wasSynced = hasReachedSynced,
                savedTip = savedTipForWatchdog,
                walletBirth = NativeBridge.getWalletBirthCheckpointHeight(),
                persistedBirth = if (settings.contains("cf_birth_height")) {
                    settings.getLong("cf_birth_height", 0L)
                } else null,
            )
            startBip158Watchdog(birthHeightForWatchdog)
            startTipStallWatchdog()
        }
    }

    /**
     * Persist the native re-dial penalty set so the next launch doesn't start by dialling
     * peers this session already learned were behind. Tiny (32 entries max, 26 bytes each)
     * so the hex-in-prefs shape is fine here — unlike the filter-header chain, which grew
     * without bound in prefs and had to move to a file.
     *
     * Null means nothing live to save (no manager, or every window lapsed); the previous
     * blob is then cleared rather than left to be restored stale.
     */
    private fun persistPeerPenalties() {
        runCatching {
            val prefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this), MODE_PRIVATE)
            val blob = NativeBridge.serializePeerPenalties()
            when (val action = io.digibyte.core.sync.PeerPenaltyPersist.decide(blob)) {
                // Null means the native side couldn't answer (no live peer manager, or the
                // probe threw) — NOT that nothing is penalized: an empty set still carries a
                // 4-byte count header. Clearing on null would throw away penalties we had
                // already banked because of a momentary hiccup.
                is io.digibyte.core.sync.PeerPenaltyPersist.Action.Keep ->
                    android.util.Log.i("SyncService",
                        "peer penalties unavailable this tick (${blob?.size ?: -1} bytes) — keeping stored set")

                is io.digibyte.core.sync.PeerPenaltyPersist.Action.Clear -> {
                    prefs.edit().remove("saved_peer_penalties").apply()
                    android.util.Log.i("SyncService", "peer penalties: none live, stored set cleared")
                }

                is io.digibyte.core.sync.PeerPenaltyPersist.Action.Store -> {
                    prefs.edit().putString("saved_peer_penalties", action.hex).apply()
                    android.util.Log.i("SyncService",
                        "peer penalties persisted (${blob?.size ?: 0} bytes)")
                }
            }
        }.onFailure { android.util.Log.w("SyncService", "peer-penalty persist failed", it) }
    }

    /**
     * Recreate the native peer manager so it resumes NEAR TIP instead of flooring to the
     * wallet birth checkpoint.
     *
     * `forceReconnect()` + `startSync()` on their own rebuild the manager from the stale
     * cold-start `g_savedBlocks` — loaded once at launch and never refreshed from the
     * advancing chain — so `manager->lastBlock` drops to the birth checkpoint and auto-fetch
     * re-arms at `cf_birth_height`. Measured on a Note 8: a scan at 24,052,509 fell to
     * 22,650,000 and spent roughly six hours climbing back.
     *
     * Ordering is the whole fix, and it lives in [RecreateSequence] so it is covered by unit
     * tests rather than only observable on a device mid-failure: refresh the window BEFORE
     * the rebuild consumes it, restore the CF ledger AFTER the new manager exists. Peer
     * injection stays between the reconnect and the restart, exactly where each call site
     * had it.
     */
    /**
     * Make the disk copy the freshest copy, immediately before a recreate destroys the
     * native manager.
     *
     * [reloadSavedBlocksNearTip] and [restoreCfLedgerAndSnap] both read the last PERSISTED
     * snapshot, but the freshest state this process holds is in memory: the saved-blocks
     * window sits in [lastSavedBlocksData] until its next save boundary, and the CF scan
     * ledger sits in [pendingCfLedger] until the coalesced writer's [filterHeaderSaveIntervalMs]
     * tick. `forceReconnect()` frees the manager, so whatever is only in memory at that
     * moment is gone — and the recreate would then restore a frontier up to a full save
     * interval behind where the scan actually was, with the resume cursor snapped down to
     * match. That give-back is small next to the birth-height floor this work removed, but
     * it is charged on EVERY recovery, so it accumulates in exactly the same direction.
     *
     * Same two writes [onDestroy] performs, for the same reason, minus the teardown. Both
     * are guarded: a failed flush costs at most the un-flushed interval, whereas refusing to
     * rebuild would leave the wallet with a dead manager.
     */
    private fun flushLiveStateBeforeRecreate() {
        lastSavedBlocksData?.let { (bytes, epoch) ->
            runCatching { persistBlocks(bytes, epoch, synchronous = true) }
                .onFailure { android.util.Log.w("SyncService", "pre-recreate block flush failed", it) }
        }
        runCatching { flushFilterHeaders() }
            .onFailure { android.util.Log.w("SyncService", "pre-recreate ledger flush failed", it) }
    }

    private suspend fun recreatePeerManagerResumingNearTip(reason: String) {
        val result = io.digibyte.core.sync.RecreateSequence.run(
            flushPersistedState = { flushLiveStateBeforeRecreate() },
            reloadBlocksNearTip = { reloadSavedBlocksNearTip() },
            forceReconnect = { NativeBridge.forceReconnect() },
            startSync = {
                injectPeers()
                injectCustomNode()
                NativeBridge.startSync()
            },
            restoreLedgerAndSnap = { restoreCfLedgerAndSnap() },
        )
        android.util.Log.i(
            "SyncService",
            "recreate ($reason): window=${if (result.windowReloaded) "near-tip" else "none"}" +
                if (result.failures.isEmpty()) "" else " failures=${result.failures}"
        )
    }

    /**
     * Hand the persisted near-tip block window to the C core and seed the
     * monotonic persistence guard with its tip. Extracted verbatim from
     * [startSyncWithTor] so the mid-session peer-manager recreate paths can re-run
     * the SAME restore instead of rebuilding the manager from the stale cold-start
     * snapshot (which floors the chain at the wallet birth height — the
     * "my wallet resynced from scratch" report). Call BEFORE a recreate.
     *
     * [blockBytes] defaults to a fresh [SavedBlockStore.load] for callers that hold
     * no window of their own (the recreate paths). Cold start MUST pass its own
     * post-preflight value — INCLUDING `null` when the CF restore preflight decided
     * to discard it — so that reset decision is never undone by re-reading a file
     * the preflight just deleted.
     *
     * NOTE — the default reads the LAST PERSISTED SNAPSHOT, not the freshest state
     * this process knows. [SavedBlockStore] only ever holds a window the C core
     * actually EMITTED via `onSaveBlocks` (4000-block boundaries during descent,
     * ~20s cadence at the tip) and that then survived [persistBlocks]' monotonic
     * guard; the freshest window the process has seen is the in-memory
     * [lastSavedBlocksData]. So a mid-session caller taking the default hands the
     * core a window up to one save boundary BEHIND where the chain actually is.
     * Whether to flush [lastSavedBlocksData] (or force a save) before a recreate is
     * a deliberate decision for the recreate call sites — it is NOT made here.
     *
     * Re-entrant by design: `loadSavedBlocks` frees a previously loaded window only
     * while the bridge still owns it — `startSync` NULLs `g_savedBlocks` when it
     * transfers ownership to the peer manager (jni_peer.c; regression-guarded by
     * `saved_blocks_reentrant_kat`), so a mid-session call cannot double-free.
     *
     * Deliberately does NOT call `markSavedBlocksLoadComplete()`: releasing the
     * peer-manager creation gate stays on the cold-start path, AFTER the saved
     * PEERS load, exactly where it is today (moving it here would open the gate one
     * step early). The native flag is a set-once latch that is already released
     * before any recreate path can run, so recreate callers never need it.
     *
     * @return true if a window was actually loaded into the core; false when the
     *   window is missing or the blob deserialized to nothing.
     */
    private fun reloadSavedBlocksNearTip(
        blockBytes: ByteArray? = SavedBlockStore.load(this@SyncService),
    ): Boolean {
        if (blockBytes == null) return false
        val loaded = NativeBridge.loadSavedBlocks(blockBytes)
        android.util.Log.i("SyncService", "Loaded $loaded saved blocks from disk")
        // Seed the monotonic persistence guard with the on-disk tip so the
        // first save this session can't overwrite a higher persisted window
        // with a lower one — the regression this guard exists to prevent.
        val onDiskTip = parseSavedBlocksTopHeight(blockBytes)
        val prefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
        if (onDiskTip > prefs.getLong("saved_blocks_tip", 0L)) {
            prefs.edit().putLong("saved_blocks_tip", onDiskTip).apply()
        }
        return loaded > 0
    }

    /**
     * Restore the CF scan ledger and snap the resume cursor to the restored scan
     * frontier. Extracted verbatim from [startSyncWithTor]; call AFTER a recreate
     * (and, on cold start, after [NativeBridge.startSync]).
     *
     * [savedLedger] defaults to a fresh [CfScanLedgerStore.load] for callers that
     * hold no ledger of their own (the recreate paths). Cold start MUST pass its
     * own post-preflight value — INCLUDING `null` when the CF restore preflight
     * decided to discard it — so that reset decision is never undone by re-reading
     * a file the preflight just deleted.
     *
     * NOTE — the default reads the LAST PERSISTED SNAPSHOT, not the freshest state
     * this process knows. `onSaveCfLedger` only records the newest ledger into the
     * in-memory [pendingCfLedger]; the coalesced writer ([startFilterHeaderWriter])
     * flushes it to [CfScanLedgerStore] at most once per [filterHeaderSaveIntervalMs]
     * (20s), and defers even that when `persistWalletTransactionsCheckpoint()` fails
     * (re-marking dirty for the next tick). A mid-session caller taking the default
     * can therefore restore a frontier ~20s or more BEHIND the live native scan —
     * and because the cursor snap below then snaps to that LOWER frontier, the next
     * `onSaveCfLedger` persists the regressed value: silent scan-progress loss,
     * which is the exact failure class this work exists to eliminate. Whether to
     * flush [pendingCfLedger] before a recreate is a deliberate decision for the
     * recreate call sites — it is NOT made here.
     */
    private fun restoreCfLedgerAndSnap(
        savedLedger: ByteArray? = CfScanLedgerStore.load(this@SyncService),
    ) {
        // CF scan ledger (Phase-1 observe-only): restore AFTER startSync so the
        // native peer manager exists (restoreCfScanLedger is guarded and returns
        // false otherwise). The native ledger is Init'd during startSync, so an
        // earlier restore (e.g. at the filter-chain restore site) would be wiped.
        if (savedLedger != null) {
            val ok = NativeBridge.restoreCfScanLedger(savedLedger)
            android.util.Log.i("SyncService", "cf-ledger: restored (${savedLedger.size} bytes, ok=$ok)")
        }

        // Resume cursor reconciliation (paced-convoy fetch, spec Part B1-resume):
        // enableAutoCompactFilterFetch above armed the forward-fetch cursor at
        // birthHeight-1 BEFORE the restore just above could set scannedThrough far
        // higher. Snap the cursor up to the restored scan frontier now — MUST run
        // after the restore (there is nothing to snap to before it), or the next
        // forward fetch re-requests already-scanned history from birthHeight and
        // drags scannedThrough back down, silently throwing away persisted scan
        // progress (and under the paced convoy's ~10s KeepAlive drive, it would
        // re-do that every tick).
        val cursorBefore = NativeBridge.getAutoFetchCFiltersThrough()
        val cursorAfter = NativeBridge.snapAutoFetchThroughToScanFrontier()
        android.util.Log.i("SyncService",
            "cf-ledger: resume cursor snap $cursorBefore -> $cursorAfter")
    }

    override fun onDestroy() {
        foregroundSyncLive.set(false)
        // Flush the latest block window synchronously before teardown so a
        // graceful stop doesn't drop it to serviceScope.cancel(). The monotonic
        // guard ensures this never regresses a higher persisted tip.
        lastSavedBlocksData?.let { (bytes, epoch) ->
            runCatching { persistBlocks(bytes, epoch, synchronous = true) }
        }
        flushFilterHeaders()
        // Before stopSync frees the peer manager: keep what we learned about bad peers.
        persistPeerPenalties()
        // stopSync() takes the native peer-manager lock (PEER_GUARD), which the
        // keepalive sweep can hold for up to ~K×10s pinging half-dead sockets —
        // calling it synchronously here runs on the main thread (Service lifecycle
        // callbacks are main-thread) and can block long enough to ANR. Fire it off
        // on a plain background thread, best-effort: serviceScope is cancelled
        // immediately below, so a coroutine launched on it wouldn't reliably run.
        Thread {
            runCatching { NativeBridge.stopSync() }
        }.start()
        networkCallback?.let { cb ->
            runCatching {
                getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        recoveryScope.cancel()
        serviceScope.cancel()
        // shutdown(), NOT shutdownNow(): the worker may be inside keepAlivePeers holding the
        // native peer lock, and interrupting a thread in JNI does nothing useful anyway. The
        // thread is a daemon, so a stuck sweep cannot keep the process alive.
        runCatching { nativeKeepaliveExecutor.shutdown() }
        super.onDestroy()
    }

    // ── Sync-progress read path (lock-free status-reads refactor) ─────────────

    /** Persist + cache the sync-start anchor. Written on the same prefs as
     *  has_synced so restore is a single path (onStartCommand). */
    private fun setSyncStartHeight(h: Long) {
        syncStartHeight = h
        getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
            .edit().putLong("sync_start_height", h).apply()
    }

    /**
     * Sync progress 0.0..1.0, replacing the retired native getSyncProgress()
     * pull. Once the first onSyncProgress callback of the session has landed,
     * the pushed float (an internally consistent native snapshot) is
     * authoritative. Before that — a cold poll — compute it Kotlin-side from the
     * lock-free mirrored heights + the (persisted) syncStart + peerCount>0 as
     * the hasDownloadPeer proxy. The reads never touch PEER_GUARD, so this can
     * run on the main thread without the ANR the old guarded pull could cause.
     */
    private fun currentSyncProgress(): Float {
        if (sawSyncProgressCallback) return syncProgressFlow.value
        val last = NativeBridge.getLastBlockHeight()
        val est = NativeBridge.getEstimatedBlockHeight()
        val hasDownloadPeer = NativeBridge.getPeerCount() > 0
        return computeSyncProgress(last, est, syncStartHeight, hasDownloadPeer)
    }

    /**
     * Verbatim port of BRPeerManagerSyncProgress (BRPeerManager.c) with
     * startHeight bound to syncStart (the native startHeight==0 case).
     * hasDownloadPeer is the peerCount>0 proxy.
     */
    private fun computeSyncProgress(
        last: Long, est: Long, syncStart: Long, hasDownloadPeer: Boolean,
    ): Float {
        val startHeight = syncStart
        return when {
            !hasDownloadPeer && syncStart == 0L -> 0.0f
            !hasDownloadPeer || last < est -> {
                if (last > startHeight && est > startHeight)
                    (0.001 + 0.999 * (last - startHeight).toDouble() / (est - startHeight).toDouble()).toFloat()
                else 0.001f
            }
            else -> 1.0f
        }
    }

    // ── NativeCallback — called from C JNI threads ────────────────────────────

    // Initialized in onStartCommand from persisted flag so progress callbacks
    // don't revert "Connected" back to "Syncing 0%" on restart near the chain tip.
    @Volatile private var hasReachedSynced = false

    private val syncCallback = object : NativeCallback {

        override fun onSyncProgress(progress: Float, blockHeight: Long) {
            // First real callback of this session — the PUSH path is now
            // authoritative for progress; the cold-poll provisional window closes.
            sawSyncProgressCallback = true

            // progress <= 0 marks a fresh sync run: 0.0 = sync start, -1.0 =
            // rescan start (bridge_syncStarted). Anchor syncStartHeight at the
            // current tip and persist it so a cold restart mid-sync computes real
            // progress from the true floor (the "reverts to Syncing 0%" class).
            if (progress <= 0f && blockHeight > 0) {
                setSyncStartHeight(blockHeight)
            }

            // progress == -1 is a signal from C core that a rescan is starting.
            if (progress < 0f) {
                hasReachedSynced = false // rescan resets sync status
                walletManager.updateSyncState(SyncState.Rescanning)
                return
            }

            // Cache the pushed (internally consistent) float for the lock-free
            // progress readers (currentSyncProgress).
            syncProgressFlow.value = progress

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
                    // isOutgoingUnconfirmed = !isReceive: this fires at tx
                    // ARRIVAL (unconfirmed). For our own OUTGOING send
                    // (isReceive == false) the owned change-marker must NOT be
                    // inserted yet — the 30s sweep inserts it once the send
                    // confirms; inserting here would let a stuck-but-valid
                    // send's change-marker survive as a phantom the sweep's
                    // skip can never remove (it was already persisted once,
                    // at broadcast). A RECEIVE (isReceive == true) is not
                    // gated (isOutgoingUnconfirmed == false) and still inserts
                    // immediately.
                    assetManager.processIncomingAssetTx(txHashHex = txHash, blockHeight = 0L,
                        isOutgoingUnconfirmed = !isReceive, persistAfterDetect = true)
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
            updateNotification(currentSyncProgress(), peerCount)

            // Persist newly connected peer address from the native side.
            // NativeBridge doesn't currently expose the address directly, so
            // we update the prune window to keep Room tidy.
            serviceScope.launch {
                val cutoff = System.currentTimeMillis() / 1000L - PEER_STALE_SECONDS
                peerDao.pruneOlderThan(cutoff)
            }
        }

        override fun onPeerDisconnected(peerCount: Int) {
            updateNotification(currentSyncProgress(), peerCount)
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
            // SECOND CF-FIRST GATE. The C core fires syncStopped off the BLOCK-HEADER
            // chain, which in COMPACT_FILTERS_ONLY reaches the tip long before the
            // filter scan does. Gating only the poll loop would leave this path free to
            // latch Complete with a large unscanned window — the fix would LOOK applied
            // and still strand transactions. Same bound and same fail-closed rule as the
            // poll loop: an unknown (0) frontier is never "caught up".
            val scanFrontier = try { NativeBridge.getLowestNeededHeight() }
                               catch (_: Throwable) { 0L }
            if (height > 0 && (scanFrontier <= 0L || (scanFrontier - 1L) < height - CF_TIP_SLACK)) {
                android.util.Log.w("SyncService",
                    "onSyncComplete fired at header height=$height but CF scan frontier " +
                    "is $scanFrontier (${height - scanFrontier + 1} blocks unscanned) — " +
                    "ignoring; filters still have work")
                return
            }
            if (!syncCompletionInFlight.compareAndSet(false, true)) return
            // Persist transactions before the synced marker, then run post-sync work.
            // Peers stay connected so the user can send/receive while the app
            // is open. The service dies naturally when the activity is destroyed.
            // WorkManager handles background catch-ups after that.
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (!persistSyncCompletionState()) {
                        android.util.Log.w(
                            "SyncService",
                            "onSyncComplete transaction checkpoint failed — keeping sync incomplete",
                        )
                        return@launch
                    }
                    hasReachedSynced = true
                    syncedThisSession = true
                    walletManager.updateSyncState(SyncState.Complete)
                    android.util.Log.i(
                        "SyncService",
                        "Sync complete — durable transaction checkpoint committed",
                    )
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

                // Confirmation-reconcile: a tx first detected while pending (CF
                // match) can strand at "Unconfirmed" because, in CF-only mode,
                // its confirming block's cfilter is never re-requested once the
                // scan window passes it — e.g. a DigiDollar receive stuck
                // "Pending" with its $ credit withheld by the dust-pending gate.
                // If any wallet tx is still unconfirmed at sync-complete, ask the
                // node for the real heights and promote them (registerRawTransaction
                // now promotes a known-pending tx), which also releases the
                // withheld DD/asset credit. Gated on pending>0 AND debounced so
                // the node is only queried when something is actually stuck;
                // self-limiting (a promoted tx is no longer pending).
                    maybeRunConfirmationReconcile("sync complete")
                } finally {
                    syncCompletionInFlight.set(false)
                }
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
                // Sovereign: surface the just-detected asset via native decode of
                // this tx — NO backend hit (onAssetDetected is exactly when the
                // indexer is most likely behind). A fresh receive is confirmed
                // false here, so its owned outputs persist as NATIVE rows.
                runCatching {
                    assetManager.processIncomingAssetTx(txHashHex = txHash, blockHeight = 0L,
                        isOutgoingUnconfirmed = false)
                }.onFailure { android.util.Log.d("SyncService", "native detect-after-asset threw", it) }
            }
        }
        override fun onSaveBlocks(data: ByteArray, replace: Int) {
            // Persist off the C core callback thread to avoid blocking peer manager.
            // Cache the latest window so onDestroy can flush it synchronously, and
            // route through persistBlocks() for the monotonic anti-regression guard.
            // The epoch is snapshotted HERE (on receipt), alongside the data, so a
            // concurrent SavedBlockStore.delete() (rescan/wipe/CF-reset) racing with
            // the queued IO write OR the eventual onDestroy flush reliably drops it
            // rather than resurrecting a wiped file. onDestroy MUST reuse this same
            // stored epoch, not re-read SavedBlockStore.currentEpoch() fresh at
            // flush time — see the lastSavedBlocksData doc comment.
            val copy = data.copyOf()
            val epoch = SavedBlockStore.currentEpoch()
            lastSavedBlocksData = copy to epoch
            serviceScope.launch(Dispatchers.IO) { persistBlocks(copy, epoch, synchronous = false) }
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
            // BIP 158 filter-header chain advanced. Record the latest chain only; the
            // coalesced writer (startFilterHeaderWriter) flushes it to a plain file at
            // most once per interval. Do NOT hex-encode + putString here — that pinned
            // an ever-growing String in the prefs in-memory map (a 512MB heap leak that
            // OOM-looped long-history wallets so they never finished syncing).
            pendingFilterHeaders = data.copyOf() to FilterHeaderStore.currentEpoch()
            filterHeadersDirty = true
        }

        override fun onSaveCfLedger(data: ByteArray) {
            // CF scan ledger advanced (Phase-1 observe-only). Record the latest ledger
            // only; the coalesced writer flushes it to a plain file at most once per
            // interval — mirrors onSaveFilterHeaders. Does NOT change sync behavior.
            pendingCfLedger = data.copyOf() to CfScanLedgerStore.currentEpoch()
            filterHeadersDirty = true
        }
    }

    // ── Compact-filter peer discovery ───────────────────────────────────────────
    // (Legacy name/pref key retained: "bloom" below refers to the SharedPreferences
    // key `dgb_bloom_peers<net>`, which is the live compact-filter peer cache under
    // a name from before BIP157/158 shipped. Renaming it touches 4 pref owners in
    // lockstep — out of scope for this stage; see the bloom-removal spec.)

    /**
     * Fetch filter-capable peers from the seeder API and inject them into the
     * C core's peer list. Uses a cached response (SharedPreferences) and
     * refreshes from the network at most once per hour.
     */
    /**
     * Inject a batch of filter-capable peers into the native peer manager.
     *
     * This is a secondary/supplementary pool alongside [injectFilterPeers]'s
     * small validated CF set: it draws from the seeder's general peer
     * listing (also filter-capability-filtered, see [fetchFromSeeder]) for
     * additional peer diversity/resilience, using a different caching and
     * rotation strategy (below) rather than injecting everything every call.
     *
     * Pool strategy (avoids hammering the seeder every 10s on a flaky network):
     *   1. Maintain a persistent pool of every filter-capable peer we've ever
     *      been told about, stored as JSON in SharedPreferences.
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
    /** CF-first: fetch the dedicated validated filter-peer list (capability=filter) and inject
     *  ALL of them (no BLOOM_BATCH_SIZE rotation — the validated set is only ~16) so the native
     *  filter-first pre-pass always has its primary set in manager->peers. Caches the last good
     *  list in its own bucket so an offline launch still primes the native set. Injected services
     *  carry the 0x40 (NODE_COMPACT_FILTERS) bit, which is what the native CF-first dialer keys on. */
    private fun injectFilterPeers() {
        val prefs = getSharedPreferences("dgb_filter_peers" + networkSuffix(this@SyncService), MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastFetch = prefs.getLong("last_fetch", 0L)

        var pool: List<Triple<String, Int, Long>> =
            prefs.getString("peer_pool", null)?.let { parsePool(it) } ?: emptyList()

        if (now - lastFetch > BLOOM_REFRESH_INTERVAL_MS || pool.isEmpty()) {
            val fresh = fetchFromSeeder("filter")
            if (fresh != null && fresh.isNotEmpty()) {
                pool = fresh
                prefs.edit()
                    .putString("peer_pool", serializePool(pool))
                    .putLong("last_fetch", now)
                    .apply()
                android.util.Log.i("SyncService", "Filter peers fetched: ${fresh.size}")
            }
        }

        if (pool.isEmpty()) {
            android.util.Log.w("SyncService",
                "No filter peers (seeder empty + no cache) — native falls back to discovery")
            return
        }

        for ((ip, port, services) in pool) {
            NativeBridge.injectPeerByIp(ip, port, services)
        }
        android.util.Log.i("SyncService", "Injected ${pool.size} filter peers (primary CF set)")
    }

    /** Read + validate a stored sync blob; return its bytes, or null if absent or
     *  malformed. A malformed blob is dropped from prefs so it can't crash the
     *  native deserializer again (the wallet just re-syncs). Pure validation lives
     *  in [decodeSyncBlobOrNull]; this only adds the prefs read + drop. */
    private fun decodeSavedBlobOrDrop(prefs: android.content.SharedPreferences, key: String): ByteArray? {
        val hex = prefs.getString(key, null)
        val bytes = decodeSyncBlobOrNull(hex)
        if (hex != null && bytes == null) {
            android.util.Log.w("SyncService", "Dropping malformed '$key' sync blob (${hex.length} chars) — will re-sync")
            prefs.edit().remove(key).apply()
        }
        return bytes
    }

    private fun injectPeers() {
        if (isTestnet(this@SyncService)) {
            // Testnet26 has no mainnet-shaped seeder infra — api.digiscope.me
            // only knows mainnet peers, so the mainnet filter-pool fetch AND the
            // Dandelion-capable-peer fetch (also served from that same seeder)
            // are both skipped entirely on testnet. Inject the two hardcoded
            // testnet26 peers instead; the refreshed testnet DNS seeds
            // (BRTestNetParams, native) supply the rest of the pool. The
            // mainnet branch below is unreached here and therefore unchanged.
            injectTestnetPeers()
            return
        }
        // CF-first: inject the FULL dedicated filter-peer list (capability=filter) as the
        // primary CF peer set every sync-start, so the native filter-first pre-pass always has
        // the known validated filter peers to dial (instead of a rotating 20-slice of the general
        // pool that may contain none of them). The native dialer suppresses the DNS shotgun
        // while any of these are dialable or connected.
        injectFilterPeers()
        // Dandelion peers piggyback here so they're injected at every sync-start
        // path (all of them call injectPeers). Runs first so the early
        // returns below can't skip it; self-throttled by its own last_fetch timer.
        injectDandelionPeers()
        val prefs = getSharedPreferences("dgb_bloom_peers" + networkSuffix(this@SyncService), MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val existing = prefs.getString("peer_pool", null)
        // Triple = (ip, port, servicesHex). servicesHex carries the seeder's
        // capability bits (0x40 = compact filters) so filter peers are tagged
        // when injected; 0 = unknown → native CF-tagged default (INJECT_DEFAULT_SERVICES).
        val pool: MutableList<Triple<String, Int, Long>> =
            if (existing != null) parsePool(existing) else mutableListOf()
        val lastFetch = prefs.getLong("last_fetch", 0L)

        val stale = now - lastFetch > BLOOM_REFRESH_INTERVAL_MS
        if (stale || pool.isEmpty()) {
            // capability=filter: the wallet is CF-only end to end, so this
            // secondary/general pool must never source a bloom-only peer either —
            // it's supplementary dial diversity on top of injectFilterPeers'
            // small validated set, not a mixed-capability fallback.
            val fresh = fetchFromSeeder("filter")
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
                    "Fetched filter peers: ${fresh.size} new, pool now ${pool.size}"
                )
            } else if (pool.isEmpty()) {
                android.util.Log.w(
                    "SyncService",
                    "Filter-peer seeder empty and no cached pool — wallet may struggle to connect"
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

    /**
     * Inject the user's own node as a priority compact-filter peer (services
     * 0x41 = NODE_NETWORK|NODE_COMPACT_FILTERS). Re-run every sync start. Resolves
     * the hostname off the native peer lock (injectPeerByIp resolves under PEER_GUARD)
     * and passes an IPv4 literal (its live-manager re-add path uses inet_pton) — DNS
     * never touches the native peer lock. No-op unless the toggle is on, the address
     * parses, and it resolves to an IPv4.
     */
    private suspend fun injectCustomNode() {
        if (!CustomNodePrefs.isEnabled(this@SyncService)) {
            NativeBridge.clearPinnedPeer()
            return
        }
        // On any enabled-but-can't-pin early return below, clear the pin: the pin
        // is now remembered at the native bridge level (survives manager recreate),
        // so a stale one would otherwise linger and be re-applied to future managers.
        val raw = CustomNodePrefs.hostPort(this@SyncService) ?: run {
            NativeBridge.clearPinnedPeer()
            return
        }
        val defaultPort = if (isTestnet(this@SyncService)) CustomNode.TESTNET_DEFAULT_PORT
                          else CustomNode.MAINNET_DEFAULT_PORT
        val node = CustomNode.parse(raw, defaultPort) ?: run {
            android.util.Log.w("SyncService", "custom node enabled but address unparseable: '$raw'")
            NativeBridge.clearPinnedPeer()
            return
        }
        val ip = withContext(Dispatchers.IO) {
            try {
                java.net.InetAddress.getAllByName(node.host)
                    .firstOrNull { it is java.net.Inet4Address }?.hostAddress
            } catch (e: Exception) {
                android.util.Log.w("SyncService", "custom node DNS resolve failed: ${node.host}", e)
                null
            }
        } ?: run {
            NativeBridge.clearPinnedPeer()
            return
        }
        // Session escape hatch override: while ownNodeAdditiveSessionOverride is
        // true (set by ACTION_OWN_NODE_ADDITIVE_SESSION, cleared by a deliberate
        // ACTION_APPLY_OWN_NODE re-apply), stay non-exclusive here regardless of
        // the persisted prefs value — otherwise ANY later call into this function
        // (0-peer keepalive recovery, Tor fallback watchdog) would re-derive
        // exclusive=true fresh from prefs and silently re-pin the node exclusive,
        // defeating the escape mid-session.
        val effectiveExclusive = CustomNodePrefs.isExclusive(this@SyncService) && !ownNodeAdditiveSessionOverride
        NativeBridge.injectPeerByIp(ip, node.port, 0x41L)
        NativeBridge.setPinnedPeer(ip, node.port, effectiveExclusive)
        android.util.Log.i(
            "SyncService",
            "own node injected + pinned as priority CF peer: $ip:${node.port} (${node.host}) " +
                "exclusive=$effectiveExclusive" +
                (if (ownNodeAdditiveSessionOverride) " (session escape active)" else "")
        )
    }

    /**
     * Own-node health poll (own-node-pairing track). Called from the existing
     * runPeerKeepalive tick (~every 30s) — does NOT start its own timer. Resolves the
     * configured host to an IPv4 literal and queries native status off the main
     * thread: compactFilterPeerStatus takes PEER_GUARD (manager->lock) in the C core,
     * same constraint as injectCustomNode's resolve above, so this must never run on
     * Dispatchers.Main.
     */
    private fun refreshOwnNodeHealth() {
        if (!CustomNodePrefs.isEnabled(this@SyncService)) {
            _ownNodeHealth.value = OwnNodeHealth.UNPAIRED
            return
        }
        val raw = CustomNodePrefs.hostPort(this@SyncService) ?: return
        val defaultPort = if (isTestnet(this@SyncService)) CustomNode.TESTNET_DEFAULT_PORT
                          else CustomNode.MAINNET_DEFAULT_PORT
        val node = CustomNode.parse(raw, defaultPort) ?: return
        serviceScope.launch(Dispatchers.IO) {
            val ip = try {
                java.net.InetAddress.getAllByName(node.host)
                    .firstOrNull { it is java.net.Inet4Address }?.hostAddress
            } catch (e: Exception) {
                android.util.Log.w("SyncService", "own-node health: DNS resolve failed: ${node.host}", e)
                null
            } ?: return@launch
            _ownNodeHealth.value = when (NativeBridge.compactFilterPeerStatus(ip, node.port)) {
                3 -> OwnNodeHealth.SERVING             // connected + answered cfheaders
                1 -> OwnNodeHealth.CONNECTING          // in pool, socket not yet up
                // 0 UNKNOWN (not in pool) OR 2 CONNECTED_NOT_SERVING both map to DARK.
                // The 2 case is deliberate: a node running WITHOUT peerblockfilters=1
                // connects but never serves filters — surfacing that as dark/⚠ is
                // exactly the misconfiguration this status exists to catch.
                else -> OwnNodeHealth.DARK
            }
        }
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
     *  Absent/unparseable services_hex → 0 (native falls back to the CF-tagged
     *  INJECT_DEFAULT_SERVICES default, jni_peer.c — never bloom-only). */
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
     *  caches parse with services = 0 (native falls back to the CF-tagged
     *  INJECT_DEFAULT_SERVICES default, jni_peer.c — never bloom-only). */
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
            // What the network actually said about the last publish. Until v4.0.42 this
            // could not be asked at all — the bridge passed a NULL callback, so a refused
            // send was indistinguishable from an accepted one and this loop re-published it
            // forever. -1 means no verdict yet, which must never read as success.
            val outcome = runCatching { NativeBridge.getPublishResult(txid) }.getOrDefault(-1)
                .takeIf { it >= 0 }
                ?.let { io.digibyte.core.sync.PublishOutcome.of(it) }
            if (outcome != null && outcome.kind != io.digibyte.core.sync.PublishOutcome.Kind.ACCEPTED) {
                android.util.Log.w(
                    "SyncService",
                    "stranded send ${txid.take(12)}: last publish was ${outcome.kind} " +
                        "(retry=${outcome.shouldRetry}, terminal=${outcome.isTerminal})"
                )
            }
            // Definitive double-spend: the C core marks a tx invalid when its
            // inputs were already spent by another (confirmed) tx. Such a tx can
            // never confirm — drop the phantom and forget the record rather than
            // re-fluffing forever. (Only acts on this authoritative signal, never
            // on the relay guess, so a valid-but-slow send is never dropped.)
            if (!runCatching { NativeBridge.isTransactionValid(txid) }.getOrDefault(true)) {
                // Read the tx's own outputs BEFORE removal — once removed,
                // NativeBridge can no longer look it up.
                val outputs = runCatching { NativeBridge.getTransactionOutputsForHash(txid) }
                    .getOrNull()?.toList()
                if (runCatching { NativeBridge.removeTransaction(txid) }.getOrDefault(false)) {
                    store.remove(txid)
                    dropped = true
                    if (outputs != null) {
                        // Clean any asset rows this dead send would otherwise
                        // leave behind as phantoms (e.g. an owned change
                        // marker that never confirms because the send lost
                        // to a double-spend).
                        val owned = runCatching { assetManager.buildOwnedScriptHexes() }
                            .getOrDefault(emptySet())
                        if (owned.isNotEmpty()) {
                            runCatching { assetManager.clearDeadAssetSend(txid, owned, outputs) }
                                .onFailure { android.util.Log.d("SyncService", "double-spend asset cleanup threw", it) }
                        }
                    }
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
     *  paired with the [SavedBlockStore] epoch captured AT RECEIPT — mirrors
     *  [pendingFilterHeaders]'s `Pair<ByteArray, Long>` shape exactly, and for the
     *  same reason: [onDestroy] MUST reuse this snapshotted epoch, not re-read
     *  [SavedBlockStore.currentEpoch] fresh at flush time. A fresh read at flush
     *  time is always equal to itself, so it silently defeats the epoch guard —
     *  e.g. [io.digibyte.core.WalletManager.wipeWallet] calls
     *  [SavedBlockStore.delete] (bumping the epoch) WITHOUT stopping this service;
     *  if [onDestroy] then re-reads the (now-bumped) current epoch instead of the
     *  pre-wipe one it captured, the guard never rejects the stale write and the
     *  just-deleted file is resurrected with a wiped wallet's block window
     *  (I2 review finding). Cached so [onDestroy] can flush it synchronously
     *  before teardown. The C core only emits saves on 4000-block boundaries / at
     *  the tip, so this is the last boundary window — sub-boundary catch-up isn't
     *  captured here, but the recent bundled checkpoint makes re-syncing that gap
     *  near-instant. */
    /** Last time the periodic stranded-send sweep ran. 0 = never this process. */
    @Volatile private var lastStrandedRebroadcastMs: Long = 0L

    @Volatile private var lastSavedBlocksData: Pair<ByteArray, Long>? = null

    /**
     * Write the serialized saved-blocks window to disk behind a monotonic guard:
     * never replace a higher persisted tip with a lower one (the bug that forced
     * ~480k-block re-syncs). [synchronous] uses commit() for the onDestroy flush
     * so it survives serviceScope cancellation.
     */
    /** Coalesced filter-header writer: at most one file write per interval, always
     *  the latest chain. Idempotent; runs for the service lifetime. */
    private fun startFilterHeaderWriter() {
        if (filterHeaderWriterJob?.isActive == true) return
        filterHeaderWriterJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(filterHeaderSaveIntervalMs)
                if (filterHeadersDirty) {
                    filterHeadersDirty = false // cleared first; a concurrent callback re-sets it
                    pendingFilterHeaders?.let { (bytes, ep) -> FilterHeaderStore.write(this@SyncService, bytes, ep) }
                    pendingCfLedger?.let { (bytes, ep) ->
                        if (persistWalletTransactionsCheckpoint()) {
                            CfScanLedgerStore.write(this@SyncService, bytes, ep)
                        } else {
                            filterHeadersDirty = true
                        }
                    }
                }
            }
        }
    }

    /** Synchronously flush the latest filter-header chain and CF scan ledger — the final
     *  save on teardown, and the pre-recreate flush (see [flushLiveStateBeforeRecreate]). */
    private fun flushFilterHeaders() {
        if (filterHeadersDirty) {
            filterHeadersDirty = false
            pendingFilterHeaders?.let { (bytes, ep) -> runCatching { FilterHeaderStore.write(this, bytes, ep) } }
            pendingCfLedger?.let { (bytes, ep) ->
                if (persistWalletTransactionsCheckpoint()) {
                    runCatching { CfScanLedgerStore.write(this, bytes, ep) }
                } else {
                    // The checkpoint gate refused, so the ledger was NOT written. Re-mark
                    // dirty so the coalesced writer retries — exactly what its own loop does
                    // on the same failure. Without this, a flush that fails its gate drops
                    // the ledger AND the flag that would have recovered it, which on a
                    // mid-session recreate means resuming from an older scan frontier.
                    filterHeadersDirty = true
                }
            }
        }
    }

    private fun persistWalletTransactionsCheckpoint(): Boolean = runCatching {
        val prefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this), MODE_PRIVATE)
        val transactionCount = NativeBridge.getTransactionCount()
        val editor = prefs.edit().putBoolean("transactions_checkpointed", true)
        if (transactionCount > 0) {
            val txData = NativeBridge.getSerializedTransactions() ?: return@runCatching false
            editor.putString("saved_transactions", bytesToHex(txData))
        }
        editor.commit()
    }.getOrDefault(false)

    private fun persistSyncCompletionState(): Boolean {
        if (!persistWalletTransactionsCheckpoint()) return false
        // DGB-1005: the cold-start CF restore preflight floors the chain to the birth checkpoint
        // (~1.25M-block re-sync) if `has_synced` is set but the CF scan ledger is absent
        // (MISSING_LEDGER). The ledger reaches disk only via the debounced coalesced writer or the
        // onDestroy flush, and an OS kill of a backgrounded app — common on low-RAM devices —
        // never reaches onDestroy. So flush the latest filter headers + CF ledger NOW, and refuse
        // to commit `has_synced` while we still hold un-durable ledger state. Deferring costs a
        // re-mark on the next completion tick; a `has_synced` that outlives its ledger costs the
        // user a full re-sync on the next launch.
        runCatching { flushFilterHeaders() }
            .onFailure { android.util.Log.w("SyncService", "completion CF-ledger flush failed", it) }
        if (!shouldMarkSynced(
                hasPendingLedger = pendingCfLedger != null,
                ledgerDurable = CfScanLedgerStore.load(this) != null,
            )
        ) {
            android.util.Log.w(
                "SyncService",
                "sync completion deferred: CF ledger not durable yet — avoiding a birth-checkpoint " +
                    "reset on next launch; will re-mark on the next completion tick",
            )
            return false
        }
        val synced = getSharedPreferences(
            "dgb_sync_data" + networkSuffix(this),
            MODE_PRIVATE,
        ).edit().putBoolean("has_synced", true).commit()
        if (!synced) return false
        getSharedPreferences("dgb_settings", MODE_PRIVATE)
            .edit().remove("cf_birth_height").commit()
        return true
    }

    /** Write the serialized saved-blocks window to the file store ([SavedBlockStore])
     *  behind a monotonic guard: never replace a higher persisted tip with a lower
     *  one (the bug that forced ~480k-block re-syncs). The tip itself is still a
     *  cheap Long in `dgb_sync_data` (I2 fix moved only the multi-MB blob to a
     *  file). [synchronous] uses commit() for the onDestroy flush so the tip
     *  survives serviceScope cancellation; the file write itself is always a
     *  synchronous tmp-write+rename regardless of [synchronous]. */
    private fun persistBlocks(data: ByteArray, snapshotEpoch: Long, synchronous: Boolean) {
        val prefs = getSharedPreferences("dgb_sync_data" + networkSuffix(this@SyncService), MODE_PRIVATE)
        val newTop = parseSavedBlocksTopHeight(data)
        val persistedTop = prefs.getLong("saved_blocks_tip", 0L)
        if (!shouldPersistBlocks(newTop, persistedTop)) {
            android.util.Log.i("SyncService",
                "saved_blocks: skipping regressive write (new tip $newTop < persisted $persistedTop)")
            return
        }
        SavedBlockStore.write(this@SyncService, data, snapshotEpoch)
        if (newTop >= 0L) {
            val editor = prefs.edit().putLong("saved_blocks_tip", newTop)
            if (synchronous) editor.commit() else editor.apply()
        }
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

    /** True if any wallet tx is still unconfirmed — its blockHeight is the
     *  TX_UNCONFIRMED sentinel (Int.MAX_VALUE), field index 3 of a
     *  `txHash|amount|fee|blockHeight|timestamp|sent|received` [NativeBridge.getTransactionDetails]
     *  line. The signal that a confirmation-reconcile is worth a node round-trip. */
    private fun hasUnconfirmedTransactions(): Boolean =
        NativeBridge.getTransactionDetails().trim().lines().any { line ->
            line.split("|").getOrNull(3)?.toLongOrNull() == Int.MAX_VALUE.toLong()
        }

    /**
     * Ask the node for the real heights of any still-unconfirmed wallet tx and promote them.
     *
     * In CF-only mode the ONLY live event that attaches a confirming height is a cfilter
     * match → getdata(full block) → block relay. That round-trip has no retry: the cfilter
     * cursor advances when the request is SENT, not when it is answered, with no in-flight
     * tracking, timeout, or rewind on peer disconnect. Lose it once and the tx strands at
     * TX_UNCONFIRMED forever — and for a DigiDollar receive that means its $ credit stays
     * withheld by the dust-pending gate, so the receive reads as $0 rather than "pending".
     * `registerRawTransaction` supplies the node's height directly, which is exactly why
     * "Scan for missing transactions" always recovered it and the live path never did.
     *
     * This used to run ONLY inside onSyncComplete. An already-synced wallet receiving live
     * gets no further onSyncComplete, so the backstop was dormant precisely when it was
     * needed — the user had to press the button. It now also runs on the keepalive tick.
     *
     * Cheap and self-limiting: gated on pending>0, debounced, and a promoted tx is no longer
     * pending, so a healthy wallet never queries the node at all.
     */
    private suspend fun maybeRunConfirmationReconcile(reason: String) {
        runCatching {
            val now = System.currentTimeMillis()
            if (hasUnconfirmedTransactions() &&
                now - lastConfirmReconcileMs > CONFIRM_RECONCILE_DEBOUNCE_MS) {
                lastConfirmReconcileMs = now
                android.util.Log.i("SyncService",
                    "$reason with unconfirmed tx(s) — running confirmation-reconcile")
                // TXID-DRIVEN, as this call site has always claimed. `reconcile()` also
                // enumerates the whole owned address set and POSTs it in 500-address
                // chunks — a complete map of the wallet, disclosed automatically on a
                // keepalive tick, with no user present. Promoting our own stuck-"Pending"
                // transactions is what this path is for and discloses only txids we
                // broadcast ourselves.
                val service = ChainReconciliationService(
                    DgbNodeClient(this@SyncService), assetManager,
                    appContext = this@SyncService,
                )
                val promoted = service.confirmPendingTransactions()
                service.reconcileAssetRowsLocallyIfPresent()
                if (promoted > 0) {
                    android.util.Log.i("SyncService", "confirmation-reconcile promoted $promoted tx(s)")
                }
            }
        }.onFailure { android.util.Log.w("SyncService", "confirmation-reconcile failed", it) }
    }

    companion object {
        const val CHANNEL_ID       = "dgb_sync_channel"
        const val NOTIFICATION_ID  = 1

        /**
         * True while this service is alive, i.e. while a foreground sync owns the single
         * global native peer manager.
         *
         * Exists for SyncWorker. That worker is a PERIODIC WorkManager job scheduled
         * unconditionally in DigiByteApp.onCreate with only a network constraint — nothing
         * gates it on whether a foreground sync is running — and it ends by calling
         * NativeBridge.stopSync(), which is BRPeerManagerDisconnect() on the SAME global
         * manager. Firing during a long restore, it drops every peer out from under the
         * scan. The worker reads this flag and skips its own teardown when we own the
         * manager.
         *
         * @Volatile-equivalent via AtomicBoolean: written on the main thread (service
         * lifecycle callbacks) and read from the worker's coroutine.
         */
        val foregroundSyncLive = java.util.concurrent.atomic.AtomicBoolean(false)
        const val ERR_NO_PEERS     = 1001
        /** Sent by the Settings own-node UI to apply a host/port/exclusive change
         *  immediately (forceReconnect → re-inject bloom + custom node → startSync)
         *  instead of waiting for the next keepalive cycle. */
        const val ACTION_APPLY_OWN_NODE = "io.digibyte.service.APPLY_OWN_NODE"
        /** Session-only escape hatch from the dark-node banner: re-pins the same
         *  configured node non-exclusive (additive with public peers) for THIS
         *  session without touching prefs — the persisted exclusive setting still
         *  applies on the next launch. See [OwnNodeHealth.DARK]. */
        const val ACTION_OWN_NODE_ADDITIVE_SESSION = "io.digibyte.service.OWN_NODE_ADDITIVE_SESSION"
        /** Wallet-screen Tor banner "Retry now": user-initiated Tor restart after a
         *  degradation. Deliberately manual - the watchdog decision NOT to auto-restart
         *  (it would kill working direct connections) stands; this only runs on a tap. */
        const val ACTION_RETRY_TOR = "io.digibyte.service.RETRY_TOR"
        /** Debounce for the post-sync confirmation-reconcile (5 min): a flaky
         *  network firing onSyncComplete repeatedly must not hammer the node. */
        private const val CONFIRM_RECONCILE_DEBOUNCE_MS = 5 * 60 * 1000L
        /** Peers not seen in 24 hours are pruned from the DB. */
        private const val PEER_STALE_SECONDS = 86_400L
        /** Capability-aware seeder API. Returns filter-capable peers when available,
         *  falling through to bloom-capable peers when not. The wallet's existing
         *  parser ignores the extra per-peer fields (peer_capability, capabilities,
         *  services_hex, etc.) since it only reads ip + port.
         *  MAINNET ONLY — api.digiscope.me knows nothing about testnet26 peers.
         *  Never fetched when [io.digibyte.core.isTestnet] is true; see
         *  [injectPeers]. */
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
        /** How long to wait for BIP158 cfheaders progress before giving up for
         *  this session. The pool is thin (3 filter peers) so we need a
         *  generous window for Tor bootstrap + slow handshakes; 120s was the
         *  user-chosen ceiling. Past this deadline, a poll interval with no cf
         *  progress AND a real cfTip→blockTip gap tries the one-time re-anchor
         *  (see [decidePostTimeoutAction]); there is no bloom fallback. */
        private const val BIP158_FALLBACK_TIMEOUT_MS = 120_000L

        /** How close blockTip must be to the peers' estimated network height
         *  to consider block-header sync "caught up." While headers are still
         *  importing toward the tip (a deep-behind wallet or post-rescan
         *  checkpoint reset), cfheaders legitimately can't advance — the
         *  watchdog stays on filters and keeps waiting. */
        private const val BLOCK_CATCHUP_GRACE = 50L

        /** Poll cadence for the BIP158 watchdog. Tight enough to catch a
         *  freshly-opened cfTip→blockTip gap shortly after headers catch
         *  past the saved-blocks tip, loose enough to avoid log spam. */
        private const val BIP158_WATCHDOG_POLL_MS = 15_000L

        // Tip-stall watchdog: poll the block tip every 60s (the stall it detects is
        // 20 min, so a fast poll is unnecessary), and throttle the Tier-2 manager
        // recreate to at most once/hour so a genuinely unreachable network can't
        // thrash reconnects.
        private const val TIP_STALL_WATCHDOG_POLL_MS = 60_000L
        private const val TIP_STALL_TIER2_THROTTLE_MS = 60 * 60 * 1000L

        // Fast tier: the tip hasn't advanced for 3 min while peers are connected. DGB
        // mines every ~15s (multi-algo), so 3 min ≈ 12 missed blocks = genuinely stuck,
        // not slow — safe to act (re-request headers + pin a canon CF peer). Far quicker
        // than the 20-min tier, for the short-orphan and can't-hold-a-filter-peer cases.
        private const val TIP_STALL_FAST_MS = 3 * 60 * 1000L

        /** Process-wide flag set when the Tor watchdog gives up waiting for
         *  bootstrap and forces a clearnet fallback. The wallet UI collects
         *  this to surface a "Tor unavailable" banner. Resets to false on
         *  every process start (companion-object lifecycle == process
         *  lifecycle) so the next launch re-tries Tor. */
        val torFailureActive: kotlinx.coroutines.flow.StateFlow<Boolean>
            get() = _torFailureActive
        private val _torFailureActive =
            kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Own-node pairing health, as last observed by [refreshOwnNodeHealth]. UNPAIRED
         *  when the own-node toggle is off (also the default at process start). DARK
         *  covers both "not in the native peer pool at all" (0 UNKNOWN — unreachable,
         *  never dialed) AND the deliberate case of a peer that connects but never
         *  answers cfheaders (2 CONNECTED_NOT_SERVING — a node missing
         *  peerblockfilters=1). Surfacing that misconfiguration as dark/⚠ rather than
         *  a soft "connecting" is the point: it's the failure mode operators actually
         *  hit when pairing a node they forgot to reconfigure. */
        enum class OwnNodeHealth { UNPAIRED, CONNECTING, SERVING, DARK }
        val ownNodeHealth: kotlinx.coroutines.flow.StateFlow<OwnNodeHealth>
            get() = _ownNodeHealth
        private val _ownNodeHealth =
            kotlinx.coroutines.flow.MutableStateFlow(OwnNodeHealth.UNPAIRED)

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
        private const val LATEST_CHECKPOINT_HEIGHT = 24_050_000L

        /** How far the compact-filter SCAN may trail the network tip and still count as
         *  caught up. The scan legitimately lags the header tip by a few blocks while the
         *  newest filters are in flight, so demanding exact equality would never latch
         *  Complete on a live chain. Kept deliberately TIGHT — this is a fund-visibility
         *  bound, not a UX smoother: every block of slack is a block the wallet claims to
         *  have checked for payments and has not. */
        private const val CF_TIP_SLACK = 10L

        /** Only re-log the "not marking complete" line when the unscanned count moves by
         *  at least this much. The condition can hold for hours; the poll loop is 10s. */
        private const val UNSCANNED_LOG_DELTA = 500L

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

        /** How often the self-healing watchdog checks that the keepalive is alive
         *  and ticking, respawning it if not. 30s catches a dead keepalive well
         *  before the wallet is meaningfully stranded, without polling so often
         *  it churns. Independent of onStartCommand (which foreground-idle apps
         *  never re-fire) and far faster than the 15-min WorkManager catch-up. */
        private const val KEEPALIVE_WATCHDOG_INTERVAL_MS = 30_000L

        /** Respawn attempts tolerated while the previous keepalive job refuses to complete,
         *  before we stop replacing it. Each un-completed job holds a Dispatchers.Default thread
         *  that cancellation cannot reclaim, so an unbounded respawn loop starves every other
         *  coroutine in the service — including the ones driving the CF scan. Two is enough to
         *  ride out a genuinely slow-but-finishing cycle without turning a stall into a
         *  thread-pool exhaustion. */
        private const val WEDGED_RESPAWN_LIMIT = 2

        /** Debounce for the network-regained reconnect: onAvailable can fire several times
         *  in a burst during a WiFi/cellular handoff. One reconnect per 15s is plenty. */
        private const val NETWORK_RECOVERY_THROTTLE_MS = 15_000L

        /** Hard bound on a network-regained reconnect so a hung native socket call can't
         *  wedge the recovery coroutine. The blocked JNI thread (if any) stays parked on the
         *  large IO pool, but the coroutine moves on and a later onAvailable can retry. */
        private const val NETWORK_RECOVERY_TIMEOUT_MS = 30_000L

        /** Poll interval for the proactive 0-peer watchdog (runZeroPeerWatchdog). */
        private const val ZERO_PEER_WATCHDOG_POLL_MS = 45_000L

        /** Consecutive unhealthy-keepalive + 0-peer polls the watchdog requires before reviving
         *  recovery, so a brief keepalive stall / peer-rotation gap never trips it. 2 × 45s ≈ 90s
         *  sustained — a reliable wedge signal (the live wedge sat 8 min). */
        private const val ZERO_PEER_WATCHDOG_TRIGGER_POLLS = 2

        /** Demand-side peer-cap controller. Full count while catching up, reduced once synced. */
        private const val PEER_CAP_POLL_MS = 15_000L
        /** Hold SyncState.Complete this long before reducing, so a momentary tip-touch can't drop
         *  peers then immediately re-dial (Complete is already grace-gated upstream; this is belt). */
        private const val PEER_CAP_REDUCE_GRACE_MS = 60_000L
        private const val CATCHUP_PEER_COUNT = 8   // matches native PEER_MAX_CONNECTIONS default
        private const val SYNCED_PEER_COUNT = 3
        /** Max header-tip gap (wallet vs network estimate) still counted as "at the tip" for the
         *  peer-cap reduction — ~a few minutes of blocks, so genuine catch-up (gap ≫ this) keeps 8. */
        private const val PEER_CAP_TIP_DELTA = 25L

        /** Consecutive 10s keepalive ticks at 0 peers before escalating from a light
         *  reconnect (re-inject + startSync) to a clean peer-manager recreate
         *  (forceReconnect). 3 ticks ≈ 30s — gives the light path a few tries first,
         *  then digs out a manager stuck after long Doze idle. */
        /**
         * How often the periodic stranded-send sweep may run.
         *
         * 90s is chosen against the failure it recovers from: a publish cancelled by an
         * unrelated peer's connect timeout. The user should not sit watching a send that
         * silently died, but a republish is real traffic and a valid-but-slow send must be
         * given room to confirm on its own before being re-sent — DigiByte blocks are ~15s,
         * so 90s is several blocks of patience.
         */
        private const val STRANDED_REBROADCAST_INTERVAL_MS = 90_000L

        private const val ZERO_PEER_RECREATE_THRESHOLD = 3
    }
}
