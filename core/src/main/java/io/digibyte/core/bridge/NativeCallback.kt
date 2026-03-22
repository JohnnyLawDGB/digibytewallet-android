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
}
