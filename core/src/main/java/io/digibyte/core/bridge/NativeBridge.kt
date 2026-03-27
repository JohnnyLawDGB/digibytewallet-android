package io.digibyte.core.bridge

/**
 * JNI bridge to the DigiByte C core (digibytewallet-core).
 * All cryptographic operations and peer-to-peer networking happen in native code.
 * Raw keys NEVER cross this boundary — only addresses, signed transactions, and status.
 */
object NativeBridge {
    init { System.loadLibrary("core-lib") }

    // === Wallet operations ===
    /** Generate BIP39 mnemonic. entropyBits: 128 = 12 words, 256 = 24 words */
    external fun generateMnemonic(entropyBits: Int): String?

    /** Create wallet from mnemonic phrase. Returns true on success. */
    external fun createWallet(phrase: String): Boolean

    /** Recover wallet from mnemonic, syncing from creationTimestamp (Unix epoch seconds). */
    external fun recoverWallet(phrase: String, creationTimestamp: Long): Boolean

    /** Authorize a session (called after biometric unlock). Token is Keystore-decrypted auth blob. */
    external fun unlockSession(authToken: ByteArray): Boolean

    /** Lock the session — zeros all derived keys from C core memory. */
    external fun lockSession()

    /** Get a receive address. format: 0=legacy(D), 1=p2sh-segwit(S), 2=bech32(dgb1). */
    external fun getReceiveAddress(index: Int, format: Int): String?

    /** Get a change address. format: 0=legacy(D), 1=p2sh-segwit(S), 2=bech32(dgb1). */
    external fun getChangeAddress(index: Int, format: Int): String?

    /** Get current wallet balance in satoshis. */
    external fun getBalance(): Long

    // === Transaction operations ===
    /** Create an unsigned transaction. Returns serialized tx bytes or null on failure. */
    external fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray?

    /** Sign a transaction. Returns signed tx bytes or null on failure. */
    external fun signTransaction(unsignedTx: ByteArray): ByteArray?

    /** Publish (broadcast) a signed transaction. Returns txid hex string or null on failure. */
    external fun publishTransaction(signedTx: ByteArray): String?

    // === Fee estimation ===
    /** Get estimated fee in sat/KB. priority: 0=high(next block), 1=medium, 2=low(economy). */
    external fun getEstimatedFee(priority: Int): Long

    // === Tor / SOCKS5 proxy ===
    /** Set SOCKS5 proxy for peer connections. Call BEFORE startSync(). */
    external fun setSocksProxy(host: String, port: Int)

    /** Clear SOCKS5 proxy — peers will connect directly. */
    external fun clearSocksProxy()

    // === Peer / sync operations ===
    /** Start SPV sync — connects to peers and begins header/transaction sync. */
    external fun startSync()

    /** Stop SPV sync — disconnects from all peers. */
    external fun stopSync()

    /** Rescan blockchain from the wallet's creation checkpoint.
     *  Triggers BRPeerManagerRescan — reconnects with fresh bloom filter
     *  to find transactions matching the wallet's addresses. */
    external fun rescan()

    /** Get sync progress as float 0.0 to 1.0. */
    external fun getSyncProgress(): Float

    /** Get number of currently connected peers. */
    external fun getPeerCount(): Int

    /** Get estimated network block height. */
    external fun getEstimatedBlockHeight(): Long

    /** Get last synced block height. */
    external fun getLastBlockHeight(): Long

    /** Register callback handler for native events. */
    external fun setCallbackHandler(handler: NativeCallback)

    // === Message signing ===
    /**
     * Sign an arbitrary message with the wallet's key at the given address index.
     * format: 1=bech32. Returns a Base64-encoded signature, or null if the session
     * is locked or the native implementation is not yet available.
     */
    external fun signMessage(message: String, addressFormat: Int): String?

    // === Wallet state ===
    /** Returns true if the C core wallet is initialized (g_wallet != NULL). */
    external fun isWalletLoaded(): Boolean

    // === Validation ===
    /** Validate a DigiByte address. Returns true if valid for current network. */
    external fun isValidAddress(address: String): Boolean

    /** Load previously saved blocks into the C core before startSync.
     *  Data format: serialized by bridge_saveBlocks in jni_peer.c */
    external fun loadSavedBlocks(data: ByteArray): Int

    /** Load previously saved peers into the C core before startSync. */
    external fun loadSavedPeers(data: ByteArray): Int

    /** Get number of transactions known to the C core wallet. */
    external fun getTransactionCount(): Int

    /** Get transaction details as pipe-separated string.
     *  Format per line: "txHash|amount|fee|blockHeight|timestamp" */
    external fun getTransactionDetails(): String

    // === Asset detection ===
    /** Returns true if the raw serialized transaction contains a DigiAsset OP_RETURN. */
    external fun isAssetTransaction(rawTx: ByteArray): Boolean

    /** Returns the raw script bytes of the first OP_RETURN output, or null if none. */
    external fun getOpReturnData(rawTx: ByteArray): ByteArray?
}
