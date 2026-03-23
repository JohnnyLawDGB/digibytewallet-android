package io.digibyte.core.bridge

/**
 * Test-only copy of NativeBridge for instrumented tests in the native module.
 * The canonical version lives in core/src/main/java/io/digibyte/core/bridge/.
 */
object NativeBridge {
    init { System.loadLibrary("core-lib") }

    external fun generateMnemonic(entropyBits: Int): String?
    external fun createWallet(phrase: String): Boolean
    external fun recoverWallet(phrase: String, creationTimestamp: Long): Boolean
    external fun unlockSession(authToken: ByteArray): Boolean
    external fun lockSession()
    external fun getReceiveAddress(index: Int, format: Int): String?
    external fun getChangeAddress(index: Int, format: Int): String?
    external fun getBalance(): Long
    external fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray?
    external fun signTransaction(unsignedTx: ByteArray): ByteArray?
    external fun publishTransaction(signedTx: ByteArray): String?
    external fun getEstimatedFee(priority: Int): Long
    external fun setSocksProxy(host: String, port: Int)
    external fun clearSocksProxy()
    external fun startSync()
    external fun stopSync()
    external fun rescan()
    external fun getSyncProgress(): Float
    external fun getPeerCount(): Int
    external fun getEstimatedBlockHeight(): Long
    external fun getLastBlockHeight(): Long
    external fun setCallbackHandler(handler: NativeCallback)
    external fun isValidAddress(address: String): Boolean
    external fun isAssetTransaction(rawTx: ByteArray): Boolean
    external fun getOpReturnData(rawTx: ByteArray): ByteArray?
}
