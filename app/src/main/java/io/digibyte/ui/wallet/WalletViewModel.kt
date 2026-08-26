package io.digibyte.ui.wallet

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.PriceData
import io.digibyte.core.PriceProvider
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.UtxoManager
import io.digibyte.core.WalletManager
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.WalletConfigDao
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.ui.components.TxKind
import io.digibyte.ui.components.classifyTxKind
import io.digibyte.core.model.SyncProgressInfo
import io.digibyte.core.model.SyncStage
import io.digibyte.core.model.SyncState
import io.digibyte.core.model.deriveSyncFrontier
import io.digibyte.core.networkSuffix
import io.digibyte.core.sync.AbandonedBand
import io.digibyte.core.sync.CfAbandonmentStore
import io.digibyte.core.sync.ChainTipPolicy
import io.digibyte.core.sync.ChainTipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.digibyte.core.tor.TorManager
import io.digibyte.core.tor.TorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val application: Application,
    private val utxoManager: UtxoManager,
    private val transactionDao: TransactionDao,
    private val walletManager: WalletManager,
    private val priceProvider: PriceProvider,
    private val torManager: TorManager,
    private val walletConfigDao: WalletConfigDao,
    private val assetManager: io.digibyte.core.asset.AssetManager,
    private val outgoingTxStore: OutgoingTxStore,
) : ViewModel() {

    private val prefs = application.getSharedPreferences("dgb_sync_data" + networkSuffix(application), 0)

    /** Live balance in satoshis — polls C core every 5 seconds.
     *  Initialized from last-known snapshot so the UI isn't blank on restart. */
    private val _balance = MutableStateFlow(prefs.getLong("last_balance", 0L))
    val balance: StateFlow<Long> = _balance.asStateFlow()

    /** Live DigiDollar balance in cents — polled alongside the DGB balance. */
    private val _ddBalance = MutableStateFlow(prefs.getLong("last_dd_balance", 0L))
    val ddBalance: StateFlow<Long> = _ddBalance.asStateFlow()

    /** Live transaction list from C core, most-recent first. */
    private val _transactions = MutableStateFlow<List<TransactionEntity>>(emptyList())
    val transactions: StateFlow<List<TransactionEntity>> = _transactions.asStateFlow()

    /** Per-txid display kind (DGB / DigiDollar / DigiAsset) for the activity list,
     *  computed alongside each tx poll. Rows default to DGB when a txid is absent. */
    private val _txKinds = MutableStateFlow<Map<String, TxKind>>(emptyMap())
    val txKinds: StateFlow<Map<String, TxKind>> = _txKinds.asStateFlow()

    // Pre-formatted type-appropriate amount per non-DGB tx: DigiDollar → "$X.XX",
    // DigiAsset → "N Tokens". Absent → the row shows the plain DGB amount.
    private val _txTypedAmounts = MutableStateFlow<Map<String, String>>(emptyMap())
    val txTypedAmounts: StateFlow<Map<String, String>> = _txTypedAmounts.asStateFlow()

    /** Pull-to-refresh spinner state for the wallet screen. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Pull-to-refresh "wake up": if disconnected, force a clean peer-manager
     *  recreate (recovers a manager stuck after long idle); either way kick
     *  startSync to catch up to the tip. Holds the spinner until peers reconnect
     *  or ~8s elapse. */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val peers = runCatching { NativeBridge.getPeerCount() }.getOrDefault(0)
                if (peers == 0) runCatching { NativeBridge.forceReconnect() }
                runCatching { NativeBridge.startSync() }
                var waited = 0
                while (waited < 8000 &&
                       runCatching { NativeBridge.getPeerCount() }.getOrDefault(0) == 0) {
                    delay(500); waited += 500
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Live SPV peer count from the C core peer manager. Updated on the
     *  same 5s cadence as balance/tx polling. SendScreen uses this to gate
     *  the Review & Send button (broadcasting with zero peers silently
     *  strands the tx locally) and ReceiveScreen shows a soft warning. */
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    /** SPV sync state propagated from WalletManager. */
    val syncState: StateFlow<SyncState> = walletManager.syncState

    /** The wallet's stored creation/recovery timestamp. Null until loaded
     *  (or when the wallet was created on this device fresh, not recovered).
     *  Drives the scan-window honesty banner — if non-null and we're still
     *  syncing, the UI tells the user explicitly that transactions older
     *  than this date are not being recovered. */
    private val _recoveryFromTimestamp = MutableStateFlow<Long?>(null)
    val recoveryFromTimestamp: StateFlow<Long?> = _recoveryFromTimestamp.asStateFlow()

    /** Live block height — polled in pollNativeBalance. */
    private val _currentBlock = MutableStateFlow(0L)
    private val _targetBlock = MutableStateFlow(0L)

    /** Compact-filter chain tip (getCFChainTipHeight) — the FUNCTIONAL sync frontier in
     *  CF-only mode: tx/deposit detection only reaches this height. Polled in
     *  pollNativeBalance; 0 until the first cfheaders response. Gates the sync state so the
     *  wallet never shows "Synced" while cfheaders lags the (fast) header chain. */
    private val _cfTip = MutableStateFlow(0L)

    /** Compact-filter SCAN frontier (`getLowestNeededHeight()`) — under the paced
     *  convoy this is the ONLY height that indicates progress. The convoy
     *  deliberately holds the block-header and cfheader frontiers within
     *  CF_CONVOY_WINDOW (10000) of it, so on a deep restore both of those report
     *  ~100% while millions of blocks are still unscanned. 0 before the native
     *  ledger exists; [deriveSyncFrontier] then falls back to cfTip/header height.
     *  Deliberately NOT `getCfScanLedgerCounts()[0]` — that index is
     *  `scannedThrough`, which LAGS after a B2 abandonment. */
    private val _scanFrontier = MutableStateFlow(0L)

    /** The abandoned compact-filter band still awaiting recovery, or null. Read
     *  from [CfAbandonmentStore] on each poll so a reconcile that sets the
     *  recovered signal clears the banner within one tick. */
    private val _abandonedBand = MutableStateFlow<AbandonedBand?>(null)

    /** Last CF scan frontier observed while the B2 valve was mid-decision
     *  (`getConvoyAbandonmentPending() > 0`) — i.e. the height the valve had the
     *  frontier PINNED at. That height is the bottom of the band it is about to
     *  abandon, and native keeps no record of it (`getAbandonedCount()` is
     *  `abandonedBelow - start`, the whole scanned range, NOT the abandoned
     *  heights). Captured here so the banner can name a real range instead of a
     *  misleading count. 0 = never observed → the band records low-unknown. */
    @Volatile private var pendingAbandonmentLowHint: Long = 0L

    /** Stable sync-target tip, used as the progress denominator so the UI
     *  percent anchors to a stable value rather than peer-quorum
     *  estimated_height (which churns as peers come and go with different tip
     *  claims mid-sync). Now derived NATIVELY — a monotonic high-water mark of
     *  the validated header height and the peer estimate, updated in
     *  [pollNativeBalance]'s height poll — with no api.digiscope.me HTTP call.
     *  0 means unknown; callers fall back to [_targetBlock] in that case. */
    private val _externalTip = MutableStateFlow(0L)

    /** Rolling samples of (timestamp_ms, blockHeight) for ETA computation.
     *  Capped at 24 entries (~2 minutes at 5s cadence), oldest evicted.
     *
     *  CROSSES THREADS — every access must hold [scanSamplesLock]. The writer runs on
     *  Dispatchers.IO (the height poll loop); the reader, [computeEta], runs from the
     *  `combine` flow on Dispatchers.Main.immediate. ArrayDeque is NOT thread-safe, and
     *  `toList()` allocates an array of the CURRENT size then copies into it — so a
     *  concurrent `removeFirst()` can null a slot mid-copy and return a list whose `size`
     *  is >= 2 while `first()` is null.
     *
     *  MEASURED (Note 8, 2026-08-03, mid deep-restore) — it killed the wallet on the UI thread:
     *    FATAL EXCEPTION: main
     *    java.lang.NullPointerException: Attempt to invoke virtual method
     *    'java.lang.Object kotlin.Pair.getFirst()' on a null object reference
     *      at WalletViewModel.computeEta(WalletViewModel.kt:301)
     *  ...with the `samples.size < 2` guard on the preceding line having just passed.
     *
     *  Do NOT "fix" a recurrence with firstOrNull(): that hides the race instead of removing
     *  it, and the same torn read can silently corrupt the ETA arithmetic without crashing. */
    private val scanSamplesLock = Any()
    private val scanSamples = ArrayDeque<Pair<Long, Long>>()

    /** The wall-clock time we last observed the wallet's block height
     *  advance. SyncService can prematurely mark state=Complete when
     *  connected peers briefly agree on a stale tip (they've all seen
     *  the same height during an initial announce burst). The bloom
     *  filter rescan keeps running in the background past that point
     *  and eventually finds the user's real history — but our UI had
     *  already hidden the progress indicator because stage=Synced.
     *  Tracking "last advancement time" lets us surface Syncing as long
     *  as blocks are still being processed, regardless of what
     *  SyncState says. */
    @Volatile private var lastBlockAdvanceTs: Long = 0L

    /** Latched "we were Complete at some point" flag. Gates the
     *  anti-flash balance guard: before first Complete we protect the
     *  UI from flashing to 0 during initial discovery; after first
     *  Complete we always trust the native balance so sends actually
     *  debit the displayed amount. */
    @Volatile private var hasReachedSyncedOnce: Boolean = false

    /** First wall-clock time we observed peers=0 in the current stall.
     *  0 means "not currently stalled". Set to `now` on the first zero,
     *  reset to 0 when peers recover. */
    @Volatile private var peersZeroSinceMs: Long = 0L

    /** Last time we poked SyncService because of a stall. Rate-limits
     *  watchdog-triggered restarts to avoid a hot loop when the service
     *  keeps dying. */
    @Volatile private var lastWatchdogKickMs: Long = 0L

    /** True while the wallet Activity is in the RESUMED lifecycle state — the
     *  user is actively looking at this screen, not backgrounded and not behind
     *  a pushed-over destination. Drives the active-screen connection-readiness
     *  nudge in [pollNativeBalance]; see [shouldWakePeers]. Reported from
     *  WalletScreen via a lifecycle observer. */
    @Volatile private var screenActive: Boolean = false

    /** Reports the wallet screen's RESUMED state into the poll loop. */
    fun setScreenActive(active: Boolean) {
        screenActive = active
    }

    /** Composite UI-facing sync progress object — consumed by WalletScreen
     *  to render the verbose during-scan UI (stage / progress / ETA /
     *  match count / running balance / scan-window banner). */
    val syncProgressInfo: StateFlow<SyncProgressInfo> = combine(
        syncState,
        _peerCount,
        _balance,
        _transactions,
        _currentBlock,
        _targetBlock,
        _recoveryFromTimestamp,
        _externalTip,
        _cfTip,
        _scanFrontier,
        _abandonedBand
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val state = values[0] as SyncState
        val peers = values[1] as Int
        val balance = values[2] as Long
        val txs = values[3] as List<TransactionEntity>
        val current = values[4] as Long
        val target = values[5] as Long
        val recoveryTs = values[6] as Long?
        val externalTip = values[7] as Long
        val cfTip = values[8] as Long
        val scanFrontier = values[9] as Long
        val abandonedBand = values[10] as AbandonedBand?

        // CF-gated sync frontier — the single source of truth shared with the
        // Network Info screen and the DigiRunner overlay (see deriveSyncFrontier
        // in core.model). Keeping this derivation in one place is what stops the
        // main screen and Network Info from disagreeing about "Synced".
        val frontier = deriveSyncFrontier(
            state = state,
            peerCount = peers,
            currentHeight = current,
            targetHeight = target,
            externalTip = externalTip,
            cfTip = cfTip,
            scanFrontier = scanFrontier,
            abandonedBandUnrecovered = abandonedBand != null,
        )

        // Latch hasReachedSyncedOnce — gates the anti-flash balance guard in
        // pollNativeBalance so that post-sync sends correctly debit the shown balance.
        // ALSO latches when an un-recovered abandoned band is the only thing
        // withholding Synced: the scan itself HAS finished, so the native balance is
        // authoritative, and without this a wallet with a surfaced band could never
        // show a genuine spend-to-zero (the guard would pin the stale higher value
        // forever — the original "4 DGB shown but all spent" symptom).
        if (frontier.stage == SyncStage.Synced || frontier.abandonedBandHolding) {
            hasReachedSyncedOnce = true
        }

        // ETA and blocks-remaining from the SAME frontier the bar uses (the CF scan
        // frontier under the convoy), so "% · N remaining" can't be three different
        // heights' opinions. etaReference MUST mirror the sampler in
        // pollNativeBalance exactly — a rate computed from scan-frontier samples but
        // projected from the header height is meaningless.
        val etaReference = when {
            scanFrontier > 0 -> scanFrontier
            cfTip > 0 -> cfTip
            else -> current
        }
        val eta: Long? = computeEta(etaReference, frontier.targetBlock)
        val behind = (frontier.targetBlock - frontier.currentBlock).coerceAtLeast(0L)

        SyncProgressInfo(
            stage = frontier.stage,
            currentBlock = frontier.currentBlock,
            targetBlock = frontier.targetBlock,
            progressFraction = frontier.progressFraction,
            matchCount = txs.size,
            runningBalanceSat = balance,
            etaSeconds = eta,
            peerCount = peers,
            recoveryFromTimestamp = recoveryTs,
            blocksBehind = behind,
            abandonedBand = abandonedBand,
            abandonedBandHolding = frontier.abandonedBandHolding,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        SyncProgressInfo(SyncStage.Connecting, 0, 0, 0f, 0, 0, null, 0, null))

    /** Linear-projection ETA from rolling block-rate. Null when we don't
     *  have enough data (less than two samples > 10s apart, or rate
     *  trending zero). */
    private fun computeEta(current: Long, target: Long): Long? {
        if (target <= 0 || current <= 0 || current >= target) return null
        val now = System.currentTimeMillis()
        val samples = synchronized(scanSamplesLock) { scanSamples.toList() }
        if (samples.size < 2) return null
        val first = samples.first()
        val elapsedMs = now - first.first
        if (elapsedMs < 10_000L) return null
        val blocksAdvanced = current - first.second
        if (blocksAdvanced <= 0) return null
        val blocksPerMs = blocksAdvanced.toDouble() / elapsedMs
        if (blocksPerMs <= 0) return null
        val remaining = (target - current).toDouble()
        val msRemaining = remaining / blocksPerMs
        return (msRemaining / 1000.0).toLong()
    }

    /** Live Tor state — used by WalletScreen to show the Tor indicator badge. */
    val torState: StateFlow<TorState> = torManager.state

    /** True iff the most recent post-upgrade reconcile attempt failed.
     *  WalletScreen renders an amber banner with a Retry button when set —
     *  surfaces silent reconcile failures (network down, backend overloaded,
     *  endpoint stripped by a redeploy) that would otherwise leave the user
     *  staring at a wrong/zero balance with no idea why. */
    val postUpgradeReconcileFailed: StateFlow<Boolean> =
        io.digibyte.core.reconcile.PostUpgradeReconciler.lastAttemptFailed

    /** True iff the Tor watchdog gave up this session and forced a clearnet
     *  fallback. Tells the UI to surface a "Tor unavailable" banner. Resets
     *  on every process start so each launch re-tries Tor. */
    val torFailureActive: StateFlow<Boolean> =
        io.digibyte.service.SyncService.torFailureActive

    /** Own-node pairing health (own-node-pairing track), as last observed by
     *  SyncService's ~30s keepalive-tick poll. UNPAIRED when the own-node
     *  toggle is off; DARK drives the dark-node banner below. */
    val ownNodeHealth: StateFlow<io.digibyte.service.SyncService.Companion.OwnNodeHealth> =
        io.digibyte.service.SyncService.ownNodeHealth

    /** Whether the configured own node is currently the EXCLUSIVE peer (all
     *  public peers refused). Read fresh from prefs each access rather than a
     *  dedicated StateFlow — Settings is the only place this changes, and this
     *  screen recomposes periodically anyway (ownNodeHealth ticks ~30s), so a
     *  plain re-check on render is simple and sufficient. Gates the dark-node
     *  banner's "Use public peers" action: it's meaningless in additive mode,
     *  where the wallet already has public peers alongside the own node. */
    val customNodeExclusive: Boolean
        get() = io.digibyte.core.settings.CustomNodePrefs.isExclusive(application)

    /** Session escape: temporarily use public peers (exclusive OFF this run;
     *  the persisted exclusive pref is untouched, so the next launch honors
     *  the user's saved choice again). Routed through SyncService — mirrors
     *  the watchdog kick above and Settings' applyOwnNodeNow — so no native
     *  peer calls happen directly from the VM. */
    fun temporarilyUsePublicPeers() {
        try {
            val intent = android.content.Intent(
                application,
                io.digibyte.service.SyncService::class.java
            ).setAction(io.digibyte.service.SyncService.ACTION_OWN_NODE_ADDITIVE_SESSION)
            androidx.core.content.ContextCompat.startForegroundService(application, intent)
        } catch (t: Throwable) {
            android.util.Log.e("WalletVM", "temporarilyUsePublicPeers: startForegroundService threw", t)
        }
    }

    /** Manually retry the post-upgrade reconcile from the banner's button.
     *  Same code path as the auto trigger — if it succeeds, the flag clears
     *  and the banner disappears. */
    fun retryPostUpgradeReconcile() {
        viewModelScope.launch {
            io.digibyte.core.reconcile.PostUpgradeReconciler.runIfNeeded(
                application, assetManager,
            )
        }
    }

    /** Dismiss the banner without running reconcile — user explicitly
     *  acknowledges the warning. Won't suppress future failures. */
    fun dismissReconcileFailedBanner() {
        io.digibyte.core.reconcile.PostUpgradeReconciler.clearFailedFlag(application)
    }

    /** Latest price data from PriceProvider (null until first fetch). */
    private val _price = MutableStateFlow<PriceData?>(null)
    val price: StateFlow<PriceData?> = _price.asStateFlow()

    /** Display currency: USD, BTC, or PHP. Persisted to SharedPreferences. */
    enum class DisplayCurrency { USD, BTC, PHP }
    val displayCurrency = MutableStateFlow(
        try {
            DisplayCurrency.valueOf(prefs.getString("display_currency", "USD") ?: "USD")
        } catch (e: Exception) { DisplayCurrency.USD }
    )

    fun cycleCurrency() {
        displayCurrency.value = when (displayCurrency.value) {
            DisplayCurrency.USD -> DisplayCurrency.BTC
            DisplayCurrency.BTC -> DisplayCurrency.PHP
            DisplayCurrency.PHP -> DisplayCurrency.USD
        }
        prefs.edit().putString("display_currency", displayCurrency.value.name).apply()
    }

    /** Fiat balance string — changes based on selected display currency. */
    val fiatBalance: StateFlow<String> = combine(balance, price, displayCurrency) { sats, priceData, currency ->
        val dgb = sats / 100_000_000.0
        when (currency) {
            DisplayCurrency.USD -> {
                if (priceData == null || priceData.priceUsd <= 0.0) return@combine "$ --"
                val fmt = NumberFormat.getCurrencyInstance(Locale.US)
                fmt.format(dgb * priceData.priceUsd)
            }
            DisplayCurrency.BTC -> {
                // DGB/BTC — show as satoshis equivalent via USD cross rate
                if (priceData == null || priceData.priceUsd <= 0.0) return@combine "BTC --"
                val btcPrice = try {
                    // Approximate: 1 BTC ≈ $60,000+ — use ratio
                    val dgbUsd = dgb * priceData.priceUsd
                    val btcApprox = dgbUsd / 60000.0 // rough estimate
                    String.format("%.8f BTC", btcApprox)
                } catch (e: Exception) { "BTC --" }
                btcPrice
            }
            DisplayCurrency.PHP -> {
                if (priceData == null || priceData.pricePhp <= 0.0) return@combine "₱ --"
                val php = dgb * priceData.pricePhp
                val fmt = NumberFormat.getNumberInstance().apply {
                    minimumFractionDigits = 2
                    maximumFractionDigits = 2
                }
                "₱${fmt.format(php)}"
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "$ --")

    init {
        fetchPricePeriodically()
        pollNativeBalance()
        loadRecoveryTimestamp()
        // The stable sync-target tip ([_externalTip]) is now derived natively in
        // pollNativeBalance()'s height poll (a monotonic max of the validated
        // header height and the peer estimate) — no digiscope.me /api/chain/tip
        // HTTP call. See the tipCandidate latch there.
    }

    /** One-shot fetch of the wallet's stored creation/recovery timestamp,
     *  feeding the scan-window banner. */
    private fun loadRecoveryTimestamp() {
        viewModelScope.launch {
            val cfg = withContext(Dispatchers.IO) { walletConfigDao.get() }
            // creationTimestamp is the value the user picked on the
            // recovery date screen (or "now" for fresh wallets). For a
            // fresh wallet, treat values within the last few minutes as
            // "no scan window concern" — the wallet doesn't have older
            // history that would be missed.
            val ts = cfg?.creationTimestamp ?: 0L
            val nowSec = System.currentTimeMillis() / 1000
            _recoveryFromTimestamp.value = if (ts > 0 && (nowSec - ts) > 600) ts else null
        }
    }

    /** Process-death watchdog. Poked on every balance-poll tick. If the
     *  peer count has been 0 for [STALL_THRESHOLD_MS] continuously we
     *  assume SyncService has been reaped (OOM kill, aggressive OEM task
     *  manager, etc.) and kick startForegroundService to bring it back.
     *
     *  The UI process is clearly alive when this runs — it's the one
     *  polling — so even if the service is dead this call can still
     *  schedule its recreation. SyncService.onStartCommand is idempotent
     *  (the early-return on `syncAlreadyLaunched` is per-instance, so a
     *  fresh instance will re-wire everything).
     *
     *  Rate-limited by [WATCHDOG_COOLDOWN_MS] so that if the service keeps
     *  dying we don't hot-loop. */
    private fun checkPeerWatchdog(peers: Int) {
        val now = System.currentTimeMillis()
        if (peers > 0) {
            if (peersZeroSinceMs != 0L) peersZeroSinceMs = 0L
            return
        }
        if (peersZeroSinceMs == 0L) {
            peersZeroSinceMs = now
            return
        }
        val stalledMs = now - peersZeroSinceMs
        val cooledDown = now - lastWatchdogKickMs > WATCHDOG_COOLDOWN_MS
        if (stalledMs >= STALL_THRESHOLD_MS && cooledDown) {
            lastWatchdogKickMs = now
            android.util.Log.w(
                "WalletVM",
                "watchdog: peers=0 for ${stalledMs / 1000}s — kicking SyncService"
            )
            try {
                val intent = android.content.Intent(
                    application,
                    io.digibyte.service.SyncService::class.java
                )
                androidx.core.content.ContextCompat
                    .startForegroundService(application, intent)
            } catch (t: Throwable) {
                android.util.Log.e("WalletVM", "watchdog: startForegroundService threw", t)
            }
        }
    }

    /** Poll the C core for balance and transactions every 5 seconds.
     *  The C core tracks these from SPV sync — Room DB is secondary. */
    private fun pollNativeBalance() {
        // MUST run off the main thread. The native reads below (getPeerCount,
        // getBalance, getTransactionDetails, …) take the v3.7.1 PEER_GUARD
        // mutex. During a send the broadcast/Dandelion-stem path holds that
        // mutex while writing to peers; a main-thread getPeerCount() then
        // blocks behind it and ANRs the UI (observed: ~52s input stall on a
        // test send, v3.7.2). Dispatchers.IO turns that contention into a
        // harmless background wait — matches refresh()'s dispatcher.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                // Poll balance
                val nativeBalance = NativeBridge.getBalance()
                if (nativeBalance != _balance.value) {
                    // Anti-flash guard — ONLY during the initial rescan
                    // before we've ever been synced. The C core can
                    // briefly report balance=0 while merkleblocks are
                    // still in-flight during the very first load; we
                    // don't want the UI to blink from the persisted
                    // last_balance down to 0 and back up.
                    //
                    // But once the wallet has reached Complete at least
                    // once, trust the native balance authoritatively —
                    // otherwise a genuine send whose inputs correctly
                    // reduce the UTXO set gets stuck at the old (higher)
                    // value forever, making balance look permanently
                    // inflated. That was the original user's "4 DGB
                    // shown but all spent" symptom.
                    val hasBeenSynced = hasReachedSyncedOnce
                    val guardBlocks = !hasBeenSynced && nativeBalance == 0L && _balance.value > 0
                    if (!guardBlocks) {
                        _balance.value = nativeBalance
                        prefs.edit().putLong("last_balance", nativeBalance).apply()
                    }
                }

                // Poll DigiDollar balance (cents). DigiDollar is live on both
                // networks (mainnet softfork activated 2026-07-18); this is nonzero
                // once the wallet syncs real DD txs on whichever network it's on.
                val ddCents = NativeBridge.getDigiDollarBalance()
                if (ddCents != _ddBalance.value) {
                    _ddBalance.value = ddCents
                    prefs.edit().putLong("last_dd_balance", ddCents).apply()
                }

                // Poll peer count — publish to the StateFlow so Send/Receive
                // screens can gate their UI on live connectivity.
                val peers = NativeBridge.getPeerCount()
                if (peers != _peerCount.value) _peerCount.value = peers

                // Process-death watchdog — see docs/bugs/peer-keepalive-proc-death.md.
                // If peers has been 0 for a sustained window, SyncService may have
                // been OOM-reaped while the UI process stayed alive (confirmed via
                // am_finish_activity "proc died" logs). Poke the service via
                // startForegroundService so Android recreates it; the service is
                // idempotent so this is a no-op if it's already running.
                checkPeerWatchdog(peers)

                // Active-screen connection readiness. While the user is actively
                // on this screen, gently wake a dormant (0-peer) SPV manager on
                // this ~5s tick instead of waiting on the 10s SyncService
                // keepalive — so a send/receive isn't left waiting on a cold
                // connection. startSync() only (no forceReconnect): the keepalive
                // and onResume own the heavier recreate, and keeping this layer
                // gentle means it can't add broadcast latency or re-open the
                // v3.7.1 recreate race. Tor-guarded so we never dial direct while
                // the SOCKS proxy is still wiring up (would leak our IP). The C
                // core self-heals nonzero counts on its own, and startSync no-ops
                // above 0 peers, so this only fires at exactly 0.
                val torState = torManager.state.value
                val torComingUp = torManager.isEnabled &&
                    (torState is TorState.Starting || torState is TorState.Connecting)
                if (shouldWakePeers(screenActive, torComingUp, peers)) {
                    android.util.Log.i("WalletVM", "active screen + 0 peers — gentle startSync wake")
                    try {
                        NativeBridge.startSync()
                    } catch (t: Throwable) {
                        android.util.Log.w("WalletVM", "active-screen wake startSync threw", t)
                    }
                }

                // Poll transactions
                var currentHeight = NativeBridge.getLastBlockHeight()
                val estHeight = NativeBridge.getEstimatedBlockHeight()
                // Do NOT clamp currentHeight up to estHeight. During catch-up est
                // is always ahead of the real last block, so clamping pinned the
                // displayed "Block X of Y" to the network tip — the UI showed a
                // frozen "23,637,113 of 23,637,113 / 100%" while the real scan
                // climbed underneath, making sync look locked up. It also flat-lined
                // the ETA samples. Publish the true last-block height so the bar,
                // percent, and ETA all move honestly. (Completion shows 100% via
                // SyncState.Complete, not via current==target.)

                // Publish raw block heights for the verbose sync UI, and
                // record a rolling sample for ETA computation. Cap at 24
                // entries (~2 minutes of history at the 5s poll cadence).
                _currentBlock.value = currentHeight
                _targetBlock.value = if (estHeight > 0) estHeight else currentHeight
                // Sovereign stable tip (replaces the removed digiscope.me
                // /api/chain/tip HTTP call): a MONOTONIC high-water mark of ONLY
                // the PoW-validated header height (getLastBlockHeight). Headers
                // carry proof-of-work and only grow, so this is genuinely
                // monotonic AND un-inflatable by a peer — it gives
                // deriveSyncFrontier a stable floor so the denominator can't
                // churn downward ("99% -> 60% -> 99%") without any external call.
                // The peer estimate (estHeight, published live as _targetBlock /
                // targetHeight) is deliberately NOT latched: it is a single-peer
                // CLAIM the native core flags as inflatable, so latching it would
                // let one transient inflated estimate pin the bar below 100%
                // forever. Left live, deriveSyncFrontier maxes against it and a
                // spike self-heals on the next poll.
                if (currentHeight > _externalTip.value) _externalTip.value = currentHeight
                // CF frontier (functional sync height in CF-only). Poll every cycle so the
                // honesty gate un-latches when cfheaders catches up (must keep polling post-Complete).
                _cfTip.value = runCatching { NativeBridge.getCFChainTipHeight().toLong() }.getOrDefault(0L)

                // ── Paced-convoy progress + abandonment surfacing (spec Part E) ──
                // The CF SCAN frontier is the only honest progress signal under the
                // convoy (it holds the header/cfheader frontiers a full window ahead
                // on purpose). NOT getCfScanLedgerCounts()[0] — that is
                // scannedThrough, which LAGS after a B2 abandonment.
                val scanFrontier =
                    runCatching { NativeBridge.getLowestNeededHeight() }.getOrDefault(0L)
                _scanFrontier.value = scanFrontier

                // Capture the bottom of a band the valve is ABOUT to abandon. While
                // getConvoyAbandonmentPending() > 0 the valve owns the hole that PINS
                // the frontier, and it holds it there across CF_CONVOY_REARM_MAX
                // re-arm cycles (~22 min) before deciding — so a 5s poll sees it. This
                // is the only way to name the range: native exposes abandonedBelow
                // (the top) but nothing for the bottom, and getAbandonedCount() is
                // abandonedBelow - ledger.start, i.e. the whole scanned range rather
                // than the heights actually abandoned.
                val abandonPending =
                    runCatching { NativeBridge.getConvoyAbandonmentPending() }.getOrDefault(0)
                if (abandonPending > 0 && scanFrontier > 0) {
                    pendingAbandonmentLowHint = scanFrontier
                }
                val abandonedBelow =
                    runCatching { NativeBridge.getAbandonedBelow() }.getOrDefault(0L)
                if (abandonedBelow > 0) {
                    val recorded = CfAbandonmentStore.noteAbandonment(
                        application, abandonedBelow, pendingAbandonmentLowHint,
                    )
                    if (recorded) {
                        android.util.Log.w("WalletVM",
                            "CF band abandoned — abandonedBelow=$abandonedBelow " +
                                "lowHint=$pendingAbandonmentLowHint; surfacing recover-me banner")
                    }
                } else if (CfAbandonmentStore.noteScanCoverage(
                        application, abandonedBelow, scanFrontier,
                    ) || run {
                        // FOURTH path, for the case the two-phase witness structurally
                        // cannot reach. Observed on a Note 8 (v4.0.44): the banner showing
                        // while native reported abandonedBelow == 0. The band is a persisted
                        // Kotlin record; the native ledger had been re-initialised, so
                        // nothing was clamping — but the scan sat far ABOVE the band, so
                        // Phase 1 could never fire again and Phase 2 refuses without it.
                        // Stuck forever, on a range that had in fact been scanned.
                        //
                        // scannedThrough settles it directly: contiguous over evaluated
                        // heights, never past an outstanding or given-up hole. Qualified by
                        // the ledger's START, because contiguity is measured from there and a
                        // ledger re-initialised above the band proves nothing about it.
                        val band = CfAbandonmentStore.unrecoveredBand(application)
                        val counts = runCatching { NativeBridge.getCfScanLedgerCounts() }
                            .getOrDefault(LongArray(0))
                        val ledgerStart = runCatching { NativeBridge.getScanLedgerStart() }
                            .getOrDefault(0L)
                        band != null && counts.size >= 4 &&
                            CfAbandonmentStore.coverageIsProven(
                                bandLow = band.low,
                                bandHigh = band.high,
                                ledgerStart = ledgerStart,
                                scannedThrough = counts[0],
                                abandonedBelow = abandonedBelow,
                                gaveUp = counts[2],
                                lowKnown = band.lowKnown,
                            ) && CfAbandonmentStore.markRecovered(application)
                    }
                ) {
                    // THIRD recovery path, and the only unattended one. SyncService's
                    // frozen-CF recovery / corrupt-chain heal / post-timeout re-anchor
                    // each delete CfScanLedgerStore and re-Init the native ledger at
                    // the floor, so abandonedBelow returns to 0 and the ORDINARY scan
                    // re-covers the band — no reconcile, no rescan, nothing on either
                    // of those paths called. Without this the wallet would nag
                    // "History gap" and refuse Synced forever over a gap that has
                    // already closed. TWO-PHASE inside noteScanCoverage (fix wave R1):
                    // a floor that lands ABOVE the band must NOT clear it, and for a
                    // resume-surfaced band (top == floor-1) that is exactly what an
                    // ordinary reconnect produces — so clearing also requires having
                    // OBSERVED the frontier inside the band first.
                    android.util.Log.i("WalletVM",
                        "abandoned CF band re-covered by the ordinary scan " +
                            "(abandonedBelow=0, scanFrontier=$scanFrontier) — banner cleared")
                }
                // Re-read every tick (in-memory prefs map): a reconcile that sets the
                // recovered signal must clear the banner within one poll.
                _abandonedBand.value = CfAbandonmentStore.unrecoveredBand(application)

                // ETA samples ride the SAME frontier the bar shows, so "% · N
                // remaining" is internally consistent. Falls back exactly as
                // deriveSyncFrontier does when the scan frontier isn't up yet.
                val etaSample = when {
                    scanFrontier > 0 -> scanFrontier
                    _cfTip.value > 0 -> _cfTip.value
                    else -> currentHeight
                }
                if (etaSample > 0) {
                    synchronized(scanSamplesLock) {
                        scanSamples.addLast(System.currentTimeMillis() to etaSample)
                        while (scanSamples.size > 24) scanSamples.removeFirst()
                    }
                }
                val txDetails = NativeBridge.getTransactionDetails()
                // Log every ~60s for debugging
                if (System.currentTimeMillis() % 60000 < 5000) {
                    android.util.Log.d("WalletVM", "heights: last=$currentHeight est=$estHeight peers=$peers")
                    txDetails.trim().lines().take(5).forEach { android.util.Log.d("WalletVM", "tx: $it") }
                }
                if (txDetails.isNotEmpty()) {
                    // Exclude unconfirmed txs: their blockHeight is TX_UNCONFIRMED
                    // (native INT32_MAX = 2_147_483_647). A pending send would
                    // otherwise poison this floor and the height/progress readout
                    // would jump to ~2.1 billion. Real block heights are ~23M.
                    val txHeights = txDetails.trim().lines().mapNotNull { line ->
                        line.split("|").getOrNull(3)?.toLongOrNull()
                    }.filter { it > 0 && it < Int.MAX_VALUE.toLong() }
                    val maxTxHeight = txHeights.maxOrNull() ?: 0L

                    // Confirmations are measured against their OWN tip, deliberately kept separate
                    // from `currentHeight`. `currentHeight` is the PoW-validated native height that
                    // drives the progress bar, the ETA samples and the monotonic _externalTip that
                    // deriveSyncFrontier uses as its denominator; a remembered value must never
                    // reach any of those, because a tip read back from prefs is not something this
                    // session verified against the chain.
                    //
                    // This used to floor `currentHeight` itself to maxTxHeight, which is where the
                    // "newest transaction always shows 1 confirmation on open" bug came from: for
                    // the newest tx, maxTxHeight IS its own height, so the count collapsed to
                    // maxTxHeight - maxTxHeight + 1 = 1 until the real tip loaded. Users read that
                    // as the wallet re-verifying their transaction on every launch; nothing was
                    // re-verified, the tip it was measured against was simply wrong.
                    if (currentHeight > 0) ChainTipStore.record(application, currentHeight)
                    val confTip = ChainTipPolicy.effectiveChainTip(
                        nativeTip = currentHeight,
                        persistedTip = ChainTipStore.read(application),
                        maxTxHeight = maxTxHeight,
                    )

                    val txList = txDetails.trim().lines().mapNotNull { line ->
                        val parts = line.split("|")
                        if (parts.size >= 5) {
                            // Normalize the unconfirmed sentinel (TX_UNCONFIRMED =
                            // INT32_MAX from native) to 0 so confs computes as 0 and
                            // the detail screen's "blockHeight > 0 ? height : Pending"
                            // shows "Pending" rather than "2147483647".
                            val rawHeight = parts[3].toLongOrNull() ?: 0L
                            val txHeight = if (rawHeight in 1 until Int.MAX_VALUE.toLong()) rawHeight else 0L
                            val confs = ChainTipPolicy.confirmationsFor(txHeight, confTip)
                            val txid = parts[0]
                            val nativeAmount = parts[1].toLongOrNull() ?: 0L
                            val nativeFee = parts[2].toLongOrNull() ?: 0L
                            val nativeSent = if (parts.size >= 7) parts[5].toLongOrNull() ?: 0L else 0L
                            val nativeReceived = if (parts.size >= 7) parts[6].toLongOrNull() ?: 0L else 0L

                            // BRWalletAmountSentByTx under-counts when parent UTXO txs
                            // aren't all in BRWallet->allTx (Universal-Restore wallets and
                            // post-bloom-fallback re-downloads hit this). When ALL parents
                            // are missing it returns 0 (amount = received - sent = +change,
                            // mis-categorized as a receive); when SOME are missing it returns
                            // a partial, undercounted sum (amount = change - partialInputs, a
                            // wrong negative — e.g. a 5.2121 send rendered as -2.1133386).
                            // For any tx WE broadcast, OutgoingTxStore holds the authoritative
                            // recipient amount/fee/address, so it wins whenever a record
                            // exists — not only the all-parents-missing (nativeSent==0) case.
                            // EXCEPTION: a self-transfer sweep (recovering legacy funds into
                            // our OWN wallet) increases the balance and the C core categorizes
                            // it as a receive; overriding it would misrender it as a large
                            // negative "Sent" to our own address, so those records are skipped
                            // here (OutgoingTxStore.shouldApplyOutgoingOverride). Genuine
                            // external sends still get the corrected negative amount.
                            val recorded = outgoingTxStore.lookup(txid)
                            val applyOverride = OutgoingTxStore.shouldApplyOutgoingOverride(recorded)
                            val amount = if (applyOverride) -recorded!!.sentSats else nativeAmount
                            val fee = if (applyOverride) recorded!!.feeSats else nativeFee
                            val toAddress = if (applyOverride) recorded!!.toAddress else ""
                            val sent = if (applyOverride) recorded!!.sentSats else nativeSent
                            val received = nativeReceived

                            TransactionEntity(
                                txid = txid,
                                amount = amount,
                                fee = fee,
                                blockHeight = txHeight,
                                timestamp = parts[4].toLongOrNull() ?: 0L,
                                toAddress = toAddress,
                                fromAddress = "",
                                confirmations = confs,
                                sent = sent,
                                received = received,
                                isAssetTx = false
                            )
                        } else null
                    }
                    // Most recent on top. Primary key is timestamp (native maps an
                    // unconfirmed tx's 0 timestamp to now, so a fresh send sorts
                    // above all confirmed history); blockHeight breaks ties between
                    // txs sharing a timestamp. Not "pending first" — a restored
                    // wallet briefly loads old txs as unconfirmed, and that would
                    // float years-old history to the top until they re-confirm.
                    val sorted = txList.sortedWith(
                        compareByDescending<TransactionEntity> { it.timestamp }
                            .thenByDescending { it.blockHeight }
                    )
                    if (sorted != _transactions.value) {
                        _transactions.value = sorted
                    }

                    // Classify each visible tx for the activity list: DigiAsset if
                    // the wallet tracks an asset output for it, else DigiDollar per
                    // the native classifier, else plain DGB. Cheap — one DB read
                    // plus a hash lookup per row (list is display-capped).
                    val assetTxids = runCatching { assetManager.assetTxids() }.getOrDefault(emptySet())
                    val kinds = sorted.associate { tx ->
                        val ddType = runCatching { NativeBridge.digiDollarTxType(tx.txid) }.getOrDefault(0)
                        tx.txid to classifyTxKind(tx.txid in assetTxids, ddType)
                    }
                    if (kinds != _txKinds.value) _txKinds.value = kinds

                    // Type-appropriate amount per non-DGB row: DigiDollar → its $ value
                    // (native per-tx accessor), DigiAsset → the token count moved
                    // (ownership-bucketed, direction-aware). Owned-script set built
                    // once here, not per row. Absent entry → row shows plain DGB.
                    val ownedScripts = runCatching { assetManager.buildOwnedScriptHexes() }
                        .getOrDefault(emptySet())
                    val typedAmounts = sorted.mapNotNull { tx ->
                        val isSend = tx.amount < 0
                        val display: String? = when (kinds[tx.txid]) {
                            TxKind.DIGIDOLLAR -> {
                                val cents = runCatching { NativeBridge.digiDollarTxAmount(tx.txid, isSend) }
                                    .getOrDefault(0L)
                                if (cents > 0L) formatDigiDollar(cents) else null
                            }
                            TxKind.DIGIASSET -> runCatching {
                                assetManager.assetAmountLabelForTx(tx.txid, isSend, ownedScripts)
                            }.getOrNull()
                            else -> null
                        }
                        display?.let { tx.txid to it }
                    }.toMap()
                    if (typedAmounts != _txTypedAmounts.value) _txTypedAmounts.value = typedAmounts
                }

                delay(5_000L)
            }
        }
    }

    /** Fetch price now and then every 5 minutes. */
    private fun fetchPricePeriodically() {
        viewModelScope.launch {
            while (true) {
                runCatching { _price.value = priceProvider.fetchPrice() }
                delay(5 * 60 * 1000L)
            }
        }
    }

    /** Get a receive address for [index]. Delegates to WalletManager (bech32 by default). */
    fun getReceiveAddress(index: Int = 0, format: Int = 2): String? =
        walletManager.getReceiveAddress(index, format = format)

    /** The wallet's DigiDollar receive address (TD… testnet / DD… mainnet). Null if locked. */
    fun getDigiDollarReceiveAddress(): String? =
        walletManager.getDigiDollarReceiveAddress()

    companion object {
        /** Peer-count=0 must persist this long before the watchdog fires. */
        private const val STALL_THRESHOLD_MS = 60_000L

        /** Minimum gap between consecutive watchdog kicks. */
        private const val WATCHDOG_COOLDOWN_MS = 90_000L

        /**
         * Format satoshis to a human-readable DGB string with up to 8 decimal places.
         * Example: 123456789012 → "1,234.56789012 DGB"
         */
        fun formatSatoshis(satoshis: Long): String = "${formatSatoshisBare(satoshis)} DGB"

        /** The same figure without the " DGB" suffix, for places that show the DigiByte mark
         *  instead of the word — currently the hero balance. Kept separate rather than made a
         *  flag on [formatSatoshis] so the two call sites that still want the text ticker
         *  cannot lose it by accident. */
        fun formatSatoshisBare(satoshis: Long): String {
            val dgb = satoshis / 100_000_000.0
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 8
            }
            return fmt.format(dgb)
        }

        /** DigiDollar cents → USD string. Example: 5000 → "$50.00" */
        fun formatDigiDollar(cents: Long): String {
            val dollars = cents / 100.0
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
            return "$" + fmt.format(dollars)
        }
    }
}
