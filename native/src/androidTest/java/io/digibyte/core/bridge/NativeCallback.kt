package io.digibyte.core.bridge

/**
 * Test-only copy of NativeCallback for instrumented tests in the native module.
 * The canonical version lives in core/src/main/java/io/digibyte/core/bridge/.
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
}
