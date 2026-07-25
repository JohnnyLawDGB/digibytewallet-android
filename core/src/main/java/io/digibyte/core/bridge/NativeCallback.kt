package io.digibyte.core.bridge

/**
 * Callback interface for events from the native C core.
 * Methods are called from C via JNI on a background thread.
 * Implementations must be thread-safe.
 */
interface NativeCallback {
    fun onSyncProgress(progress: Float, blockHeight: Long)
    fun onTransactionReceived(txHash: String, amount: Long, isReceive: Boolean)
    fun onPeerConnected(peerCount: Int)
    fun onPeerDisconnected(peerCount: Int)
    fun onSyncComplete()
    fun onSyncFailed(errorCode: Int, message: String)
    fun onBalanceChanged(balanceSatoshis: Long)
    fun onAssetDetected(txHash: String, assetId: String, quantity: Long, isReceive: Boolean)
    fun onSaveBlocks(data: ByteArray, replace: Int)
    fun onSavePeers(data: ByteArray, replace: Int)
    /** BIP 158 filter-header chain advanced. Persist the serialized buffer so
     *  it can be restored via NativeBridge.setCompactFilterChain on next open.
     *  Called on a background thread; implementations must be thread-safe.
     *  Inert in BR_SYNC_MODE_BLOOM_ONLY (default) — never fires. */
    fun onSaveFilterHeaders(data: ByteArray) {}
    /** CF scan-ledger persistence hook (Phase-1, observe-only). Persist the
     *  serialized ledger buffer so it can be restored via
     *  NativeBridge.restoreCfScanLedger on next open. Fires whenever the ledger
     *  advances. Called on a background thread; implementations must be thread-safe. */
    fun onSaveCfLedger(data: ByteArray) {}
}
