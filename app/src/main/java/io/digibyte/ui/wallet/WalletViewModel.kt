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
import io.digibyte.core.model.SyncProgressInfo
import io.digibyte.core.model.SyncStage
import io.digibyte.core.model.SyncState
import io.digibyte.core.network.ChainTipFetcher
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

    private val prefs = application.getSharedPreferences("dgb_sync_data", 0)

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

    /** Authoritative chain tip fetched from api.digiscope.me. Used as the
     *  progress denominator so the UI percent anchors to a stable value
     *  rather than peer-quorum estimated_height (which churns as peers
     *  come and go with different tip claims mid-sync). 0 means unknown;
     *  callers fall back to [_targetBlock] in that case. Refreshed every
     *  30 s from [fetchChainTipPeriodically]. */
    private val _externalTip = MutableStateFlow(0L)

    /** Rolling samples of (timestamp_ms, blockHeight) for ETA computation.
     *  Capped at 24 entries (~2 minutes at 5s cadence), oldest evicted. */
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
        _externalTip
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

        // Prefer the authoritative external tip when available; fall back to
        // the peer-quorum target only when the fetch has never succeeded
        // (offline, DNS, etc). Never let the effective target regress — once
        // we've seen the real tip we trust it over any lower peer claim.
        val effectiveTarget = if (externalTip > target) externalTip else target

        // Honest progress: if the real block height (current = getLastBlockHeight,
        // before the maxTxHeight display floor) is materially behind the tip,
        // surface a catch-up even if SyncState.Complete latched. Without this the
        // wallet could sit "Connected" while silently re-syncing hundreds of
        // thousands of blocks underneath — wrong confirmations, no progress bar.
        // The threshold is well above normal tip lag (a few blocks between 15s
        // blocks) so steady-state operation never flickers to Syncing.
        val materiallyBehind = current > 0 && effectiveTarget > 0 &&
            (effectiveTarget - current) > SYNC_BEHIND_THRESHOLD

        val stage = when {
            state is SyncState.Failed -> SyncStage.Failed
            peers <= 0 -> SyncStage.Connecting
            materiallyBehind -> SyncStage.Syncing
            state is SyncState.Complete -> SyncStage.Synced
            else -> SyncStage.Syncing
        }

        // Latch hasReachedSyncedOnce — gates the anti-flash balance
        // guard in pollNativeBalance so that post-sync sends correctly
        // debit the shown balance.
        if (stage == SyncStage.Synced) hasReachedSyncedOnce = true

        val progress = when {
            materiallyBehind && effectiveTarget > 0 ->
                (current.toFloat() / effectiveTarget.toFloat()).coerceIn(0f, 1f)
            state is SyncState.Complete -> 1.0f
            current > 0 && effectiveTarget > 0 ->
                (current.toFloat() / effectiveTarget.toFloat()).coerceIn(0f, 1f)
            state is SyncState.Syncing -> state.progress
            else -> 0.0f
        }

        // ETA: compute from rolling samples. Need ≥ 2 samples spanning > 10s
        // and a positive block-rate, otherwise null.
        val eta: Long? = computeEta(current, effectiveTarget)

        SyncProgressInfo(
            stage = stage,
            currentBlock = current,
            targetBlock = effectiveTarget,
            progressFraction = progress.coerceIn(0f, 1f),
            matchCount = txs.size,
            runningBalanceSat = balance,
            etaSeconds = eta,
            peerCount = peers,
            recoveryFromTimestamp = recoveryTs
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        SyncProgressInfo(SyncStage.Connecting, 0, 0, 0f, 0, 0, null, 0, null))

    /** Linear-projection ETA from rolling block-rate. Null when we don't
     *  have enough data (less than two samples > 10s apart, or rate
     *  trending zero). */
    private fun computeEta(current: Long, target: Long): Long? {
        if (target <= 0 || current <= 0 || current >= target) return null
        val now = System.currentTimeMillis()
        val samples = scanSamples.toList()
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

    /** True iff the BIP158 watchdog gave up this session and forced bloom
     *  fallback. Tells the UI to surface a "privacy degraded" banner.
     *  Resets on every process start so each launch re-tries filters. */
    val bloomFallbackActive: StateFlow<Boolean> =
        io.digibyte.service.SyncService.bloomFallbackActive

    /** True iff the Tor watchdog gave up this session and forced a clearnet
     *  fallback. Tells the UI to surface a "Tor unavailable" banner. Resets
     *  on every process start so each launch re-tries Tor. */
    val torFailureActive: StateFlow<Boolean> =
        io.digibyte.service.SyncService.torFailureActive

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
        fetchChainTipPeriodically()
    }

    /** Refresh the authoritative chain tip every 30 seconds. On failure the
     *  stored value is left unchanged so a momentary network blip doesn't
     *  regress the progress denominator to 0. */
    private fun fetchChainTipPeriodically() {
        viewModelScope.launch {
            while (true) {
                val tip = ChainTipFetcher.fetch()
                if (tip > 0L) _externalTip.value = tip
                delay(30_000L)
            }
        }
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

                // Poll DigiDollar balance (cents). Trivially trusted — DD is zero on
                // mainnet, nonzero only on a testnet26 build that syncs real DD txs.
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
                if (currentHeight > 0) {
                    scanSamples.addLast(System.currentTimeMillis() to currentHeight)
                    while (scanSamples.size > 24) scanSamples.removeFirst()
                }
                val txDetails = NativeBridge.getTransactionDetails()
                // Log every ~60s for debugging
                if (System.currentTimeMillis() % 60000 < 5000) {
                    android.util.Log.d("WalletVM", "heights: last=$currentHeight est=$estHeight peers=$peers")
                    txDetails.trim().lines().take(5).forEach { android.util.Log.d("WalletVM", "tx: $it") }
                }
                if (txDetails.isNotEmpty()) {
                    // First pass: find the highest tx block height as a floor.
                    // This ensures confirmations are computed correctly even before
                    // the peer manager loads saved blocks and reports a chain tip.
                    // Exclude unconfirmed txs: their blockHeight is TX_UNCONFIRMED
                    // (native INT32_MAX = 2_147_483_647). A pending send would
                    // otherwise poison this floor and the height/progress readout
                    // would jump to ~2.1 billion. Real block heights are ~23M.
                    val txHeights = txDetails.trim().lines().mapNotNull { line ->
                        line.split("|").getOrNull(3)?.toLongOrNull()
                    }.filter { it > 0 && it < Int.MAX_VALUE.toLong() }
                    val maxTxHeight = txHeights.maxOrNull() ?: 0L
                    if (maxTxHeight > currentHeight) currentHeight = maxTxHeight

                    val txList = txDetails.trim().lines().mapNotNull { line ->
                        val parts = line.split("|")
                        if (parts.size >= 5) {
                            // Normalize the unconfirmed sentinel (TX_UNCONFIRMED =
                            // INT32_MAX from native) to 0 so confs computes as 0 and
                            // the detail screen's "blockHeight > 0 ? height : Pending"
                            // shows "Pending" rather than "2147483647".
                            val rawHeight = parts[3].toLongOrNull() ?: 0L
                            val txHeight = if (rawHeight in 1 until Int.MAX_VALUE.toLong()) rawHeight else 0L
                            val confs = if (txHeight > 0 && currentHeight >= txHeight)
                                (currentHeight - txHeight + 1).toInt() else 0
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

    companion object {
        /** Blocks-behind-tip past which the UI honestly shows catch-up progress
         *  instead of "Complete", even if SyncState.Complete latched. Well above
         *  normal tip lag (a handful of 15s blocks) so steady state never
         *  flickers; far below any real re-sync (hundreds of thousands). */
        private const val SYNC_BEHIND_THRESHOLD = 100L

        /** Peer-count=0 must persist this long before the watchdog fires. */
        private const val STALL_THRESHOLD_MS = 60_000L

        /** Minimum gap between consecutive watchdog kicks. */
        private const val WATCHDOG_COOLDOWN_MS = 90_000L

        /**
         * Format satoshis to a human-readable DGB string with up to 8 decimal places.
         * Example: 123456789012 → "1,234.56789012 DGB"
         */
        fun formatSatoshis(satoshis: Long): String {
            val dgb = satoshis / 100_000_000.0
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 8
            }
            return "${fmt.format(dgb)} DGB"
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
