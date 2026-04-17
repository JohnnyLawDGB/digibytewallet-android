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
    external fun getTransactionCount(): Int
    external fun getTransactionDetails(): String
    external fun loadSavedBlocks(data: ByteArray): Int
    external fun loadSavedPeers(data: ByteArray): Int
    external fun createWalletFromBytes(phraseBytes: ByteArray): Boolean
    external fun recoverWalletFromBytes(phraseBytes: ByteArray, creationTimestamp: Long): Boolean
    external fun isWalletLoaded(): Boolean
    external fun dumpAllAddresses(): String
    external fun registerRawTransaction(rawTx: ByteArray, blockHeight: Long, blockTimestamp: Long): Boolean
    external fun mnemonicToSeed(phraseBytes: ByteArray, passphrase: String?): ByteArray?
    external fun deriveAddresses(seedBytes: ByteArray, hmacKey: String, prefixPath: IntArray, gapExternal: Int, gapInternal: Int, addressFormat: Int): Array<String>?
    external fun derivePrivateKeyWIF(seedBytes: ByteArray, hmacKey: String, fullPath: IntArray): String?
}
