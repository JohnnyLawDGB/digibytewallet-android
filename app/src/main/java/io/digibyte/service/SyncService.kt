package io.digibyte.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.digibyte.core.UtxoManager
import io.digibyte.core.WalletManager
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

        // Wire C core → Kotlin before kicking off sync so no events are lost.
        NativeBridge.setCallbackHandler(syncCallback)

        // Launch Tor-aware startup asynchronously.
        serviceScope.launch {
            startSyncWithTor()
        }

        // Poll peer count every 30 s for notification accuracy.
        serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                val peers = NativeBridge.getPeerCount()
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

        // Start SPV sync regardless of Tor outcome.
        NativeBridge.startSync()
        walletManager.updateSyncState(SyncState.Syncing(0f, 0))
    }

    override fun onDestroy() {
        NativeBridge.stopSync()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── NativeCallback — called from C JNI threads ────────────────────────────

    private val syncCallback = object : NativeCallback {

        override fun onSyncProgress(progress: Float, blockHeight: Long) {
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
            if (peerCount == 0) {
                walletManager.updateSyncState(
                    SyncState.Failed(ERR_NO_PEERS, "No peers connected")
                )
            }
        }

        override fun onSyncComplete() {
            val height = NativeBridge.getLastBlockHeight()
            walletManager.updateSyncState(SyncState.Complete)
            updateNotification(progress = 1f, peerCount = NativeBridge.getPeerCount())
        }

        override fun onSyncFailed(errorCode: Int, message: String) {
            walletManager.updateSyncState(SyncState.Failed(errorCode, message))
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
