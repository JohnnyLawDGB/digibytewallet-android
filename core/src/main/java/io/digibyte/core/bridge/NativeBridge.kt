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

    /** Create wallet from mnemonic as ByteArray (UTF-8). Avoids JVM String heap leak. */
    external fun createWalletFromBytes(phraseBytes: ByteArray): Boolean

    /** Recover wallet from mnemonic as ByteArray (UTF-8). Avoids JVM String heap leak. */
    external fun recoverWalletFromBytes(phraseBytes: ByteArray, creationTimestamp: Long): Boolean

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
    /** Inject a peer by IP address into the saved peers list for priority connection.
     *  Call BEFORE startSync() to ensure the peer is tried on the next connection cycle. */
    external fun injectPeerByIp(ip: String, port: Int)

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

    // === Transaction persistence ===
    /** Serialize all wallet transactions to a byte array for persistence. */
    external fun getSerializedTransactions(): ByteArray?

    /** Load previously saved transactions before recoverWallet/createWallet. */
    external fun loadSerializedTransactions(data: ByteArray): Int

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

    // === BIP84 Derivation ===
    /** Returns the BIP84 derivation path string, e.g. "m/84'/20'/0'" */
    external fun getDerivationPath(): String

    /** Returns true if the wallet has UTXOs on the legacy m/0H key tree */
    external fun hasLegacyFunds(): Boolean

    /** Diagnostic: returns all wallet addresses (BIP84 external + internal +
     *  legacy chains) newline-separated, for on-chain cross-checking when
     *  debugging "expected balance higher than detected" scenarios. */
    external fun dumpAllAddresses(): String

    /** Injects a node-verified transaction into the wallet. Used by the
     *  chain reconciliation service to repair state when the SPV bloom
     *  scan misses a tx but we see it on-chain. Caller must merkle-proof
     *  verify against trusted headers first. Returns true if the tx was
     *  registered (new + belongs to wallet), false otherwise (dup, bad
     *  parse, unsigned, or foreign). */
    external fun registerRawTransaction(
        rawTx: ByteArray,
        blockHeight: Long,
        blockTimestamp: Long
    ): Boolean

    // ── Universal Restore — stateless key derivation ──────────────────────────
    //
    // These functions probe arbitrary derivation paths during seed restore
    // (BIP44 DGB, BIP44 wrong-coin, BIP49, legacy m/0H with "Bitcoin seed" or
    // "DigiByte seed" HMAC). They do NOT touch wallet state and are safe to
    // call before a wallet is created. Seeds are zeroed in native memory
    // before the native buffer is released.

    /** Derive the 64-byte BIP39 seed from a mnemonic + optional passphrase.
     *  Caller must `fill(0)` the returned ByteArray when done. */
    external fun mnemonicToSeed(phraseBytes: ByteArray, passphrase: String?): ByteArray?

    /** Derive external + internal addresses under a hardened path prefix.
     *  Returns external[0..gapExternal-1] followed by internal[0..gapInternal-1].
     *  Empty strings at positions where derivation failed (rare).
     *  [addressFormat]: 0=P2PKH (D-prefix), 1=P2WPKH bech32 (dgb1q...),
     *  2=P2SH-P2WPKH (S-prefix wrapped segwit). */
    external fun deriveAddresses(
        seedBytes: ByteArray,
        hmacKey: String,
        prefixPath: IntArray,
        gapExternal: Int,
        gapInternal: Int,
        addressFormat: Int
    ): Array<String>?

    /** Derive a WIF-encoded private key at an arbitrary full path. Used by
     *  the legacy-path sweeper when signing inputs from non-native
     *  derivation addresses. Returns null on bad input. */
    external fun derivePrivateKeyWIF(
        seedBytes: ByteArray,
        hmacKey: String,
        fullPath: IntArray
    ): String?

    /** Build and sign a sweep transaction that moves all listed UTXOs into a
     *  single output to [destAddress]. Used by LegacySweepService after the
     *  Universal Restore scan finds funds on non-native derivation paths.
     *
     *  All arrays must be the same length (one entry per input UTXO).
     *  [prefixPath] is the hardened derivation prefix for the source path;
     *  per-input [chainIndices] and [addressIndices] complete each full path.
     *  [scriptPubKeysHex] is the scriptPubKey of each UTXO (hex-encoded).
     *
     *  Returns the signed tx as a hex string, or null on any failure
     *  (bad parse, sign mismatch, dust threshold, or unsupported script
     *  type — notably BIP49 P2SH-P2WPKH inputs are NOT yet handled by the
     *  underlying BRTransactionSign). */
    /** Build, sign, and serialize a DigiAsset transfer transaction from
     *  pre-selected inputs and pre-constructed outputs (including the
     *  DA OP_RETURN). Asset inputs MUST come first in the inputs list —
     *  the DA instruction stream walks inputs in order.
     *
     *  outputAddresses[i] may be the empty string ("") to indicate that
     *  outputScriptsHex[i] is the raw scriptPubKey to use for that output
     *  (e.g. for the OP_RETURN). Otherwise the address is resolved via
     *  BRAddressScriptPubKey and outputScriptsHex[i] is ignored.
     *
     *  Returns signed tx hex, or null on any failure (invalid address,
     *  hex parse error, signing failure, etc.). */
    external fun buildAndSignAssetTransferTx(
        inputTxidsHex: Array<String>,
        inputVouts: IntArray,
        inputAmounts: LongArray,
        inputScriptPubKeysHex: Array<String>,
        outputAddresses: Array<String>,
        outputAmounts: LongArray,
        outputScriptsHex: Array<String>,
    ): String?

    external fun buildAndSignLegacySweep(
        seedBytes: ByteArray,
        hmacKey: String,
        prefixPath: IntArray,
        txidsHex: Array<String>,
        vouts: IntArray,
        amounts: LongArray,
        chainIndices: IntArray,
        addressIndices: IntArray,
        scriptPubKeysHex: Array<String>,
        destAddress: String,
        feePerKb: Long,
    ): String?
}
