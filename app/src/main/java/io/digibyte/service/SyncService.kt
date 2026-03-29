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

    @Inject lateinit var walletManager: WalletManager
    @Inject lateinit var utxoManager: UtxoManager
    @Inject lateinit var transactionDao: TransactionDao
    @Inject lateinit var peerDao: PeerDao
    @Inject lateinit var assetManager: AssetManager
    @Inject lateinit var torManager: TorManager

    /** True if Tor proxy was successfully wired before this sync session started. */
    @Volatile private var torProxyActive: Boolean = false

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

        // Only launch sync ONCE — service may receive multiple onStartCommand calls
        // from redundant startForegroundService() invocations.
        if (syncAlreadyLaunched) return START_STICKY
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

        // Poll peer count every 30s — also reconnects if peers dropped to 0.
        serviceScope.launch {
            // Wait for startSyncWithTor to finish first
            walletManager.walletState.first { it is WalletState.Unlocked }
            while (isActive) {
                delay(30_000L)
                val peers = NativeBridge.getPeerCount()
                if (peers == 0) {
                    android.util.Log.i("SyncService", "No peers connected, attempting reconnect")
                    NativeBridge.startSync()
                }
                updateNotification(NativeBridge.getSyncProgress(), peers)
            }
        }

        return START_STICKY
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

        // Wait for the wallet to be created/restored (PIN entry + seed restore).
        // Poll the C core directly — WalletManager state may already be Unlocked
        // but the native wallet needs time to initialize.
        while (NativeBridge.getLastBlockHeight() == 0L && NativeBridge.getTransactionCount() == 0) {
            // getLastBlockHeight returns 0 when no peer manager exists (no wallet).
            // Once createWallet succeeds in the C core, we can proceed.
            if (walletManager.walletState.value is WalletState.Unlocked) break
            delay(500L)
        }
        android.util.Log.i("SyncService", "Wallet ready, starting sync")

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
                walletManager.updateSyncState(SyncState.Rescanning)
                return
            }

            // Once we've reached synced, don't revert to Syncing/Rescanning.
            // The only way back is via the -1 rescan signal above.
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
            hasReachedSynced = true
            walletManager.updateSyncState(SyncState.Complete)
            updateNotification(progress = 1f, peerCount = NativeBridge.getPeerCount())
            // Persist sync-complete so restarts don't flash "Syncing 0%"
            getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
                .edit().putBoolean("has_synced", true).apply()
            // Persist transactions so balance is immediately spendable on restart
            serviceScope.launch(Dispatchers.IO) {
                val txData = NativeBridge.getSerializedTransactions()
                if (txData != null) {
                    val hex = bytesToHex(txData)
                    getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
                        .edit().putString("saved_transactions", hex).apply()
                    android.util.Log.i("SyncService", "Saved ${txData.size} bytes of transactions")
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
                        isAssetTx     = true
                    )
                )

                // Queue an IPFS metadata fetch for this asset id.
                // AssetMetadataService checks its local cache first (no redundant fetches).
                // The CID is not available from the callback directly — the C core knows the
                // asset id but not the metadata CID at this point.  We hand off to
                // AssetMetadataService with a null CID; it will no-op until the CID is
                // learned later (e.g. via processAssetUtxo once the full tx is confirmed).
                assetManager.getAssetHistory(assetId) // touches the DAO, warms the flow
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
    }
}
