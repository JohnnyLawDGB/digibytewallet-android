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

    /** Validate a BIP39 recovery phrase, including the checksum (not just wordlist
     *  membership). Returns false for phrases whose words are all valid but whose
     *  checksum doesn't match — i.e. a typo'd/invalid seed. Touches no wallet state. */
    external fun isValidMnemonic(phrase: String): Boolean

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

    // === Dandelion++ broadcast-origin privacy ===
    /** Stem-submit a signed tx to one Dandelion-capable peer. Returns txid hex on a
     *  successful stem, or null if no capable peer was available (caller floods via
     *  [publishTransaction]). */
    external fun publishTransactionStem(signedTx: ByteArray): String?
    /** Embargo fallback: flood a previously stem-submitted tx to all peers. */
    external fun fluffTransaction(txid: String)

    /** Drop a tx (and dependents) from the wallet, restoring the balance — used
     *  to clear a phantom unconfirmed send that can never confirm (a double-spend
     *  whose inputs a confirmed tx already used). Returns true if it was present. */
    external fun removeTransaction(txid: String): Boolean

    /** False when the tx's inputs are already spent by another (confirmed) tx —
     *  a double-spend that can never confirm. True for valid or unknown txs. */
    external fun isTransactionValid(txid: String): Boolean
    /** Number of connected peers that have relayed the given tx back (0 = not yet
     *  propagated) — used to decide whether the embargo must self-fluff. */
    external fun getRelayCount(txid: String): Int
    /** Enable/disable Dandelion stem submission (default on in the core). */
    external fun setDandelionEnabled(enabled: Boolean)
    /** Mark a peer (by IP) as Dandelion-capable — sourced from the seeder + the
     *  priority peer; there is no service bit to detect it from the handshake. */
    external fun addDandelionPeer(ip: String)
    /** True if Dandelion is enabled AND a connected peer is Dandelion-capable. */
    external fun hasDandelionPeer(): Boolean

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
    /** Flag the peer manager for a clean recreate on the next startSync() — recovers
     *  from a stuck manager (0 peers that won't reconnect after long idle/Doze).
     *  Call this immediately before startSync(). */
    external fun forceReconnect()

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

    /**
     * Highest block height in the in-memory saved-blocks set, or 0 if none
     * loaded. Safe to call before startSync() (does not require the peer
     * manager). Use this — not getLastBlockHeight() — when picking a BIP 158
     * birth height during onStartCommand, where the peer manager doesn't
     * yet exist.
     */
    external fun getSavedBlocksTip(): Long

    /**
     * Height of the latest hardcoded chain checkpoint that's at least a week
     * before the loaded wallet's creation timestamp. Returns 0 if no wallet
     * is loaded. Mirrors the SPV chain-download anchor — use this as the
     * BIP 158 birth height for fresh wallets that haven't persisted any
     * blocks yet.
     */
    external fun getWalletBirthCheckpointHeight(): Long

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

    /** Release the startSync peer-manager creation gate. MUST be called once the
     *  saved-blocks load step is done (even if there were none) so a racing
     *  early startSync can't build the manager with no blocks and floor the
     *  chain at the birth checkpoint. */
    external fun markSavedBlocksLoadComplete()

    /** Suppress the one-time post-first-sync rescan, which re-floors the chain
     *  to the birth checkpoint. Call at startup for wallets that have synced
     *  before (has_synced) and resume at the saved tip. */
    external fun markInitialSyncDone()

    /** Load previously saved peers into the C core before startSync. */
    external fun loadSavedPeers(data: ByteArray): Int

    /** Get number of transactions known to the C core wallet. */
    external fun getTransactionCount(): Int

    /** Returns every wallet-known transaction hash (display-order hex).
     *  Used by AssetManager.sweepKnownTransactionsForAssets to backfill
     *  asset rows for transactions we've already ingested. Unlike
     *  [getTransactionDetails] there's no 50-recent cap. */
    external fun getAllTransactionHashes(): Array<String>?

    /** Get transaction details as pipe-separated string.
     *  Format per line: "txHash|amount|fee|blockHeight|timestamp" */
    external fun getTransactionDetails(): String

    // === Asset detection ===
    /** Returns true if the raw serialized transaction contains a DigiAsset OP_RETURN. */
    external fun isAssetTransaction(rawTx: ByteArray): Boolean

    /** Returns the raw script bytes of the first OP_RETURN output, or null if none. */
    external fun getOpReturnData(rawTx: ByteArray): ByteArray?

    /** Walks the outputs of a wallet-known transaction and returns each as
     *  "vout|satoshis|scriptHex". Returns null if the wallet hasn't seen
     *  this txid or [txHashHex] is malformed. Used by native asset
     *  detection on SPV receive to avoid round-tripping raw bytes. */
    external fun getTransactionOutputsForHash(txHashHex: String): Array<String>?

    /** Walks the inputs of a wallet-known transaction and returns each as
     *  "prevTxidHex|prevVout" (display-order hex). Used by asset-id
     *  derivation for issuance txs (we need the first input's outpoint)
     *  and by the future M3 parent-walk to recurse toward issuance. */
    external fun getTransactionInputsForHash(txHashHex: String): Array<String>?

    /** Returns the full serialized bytes of a wallet-known transaction, or
     *  null if the txid isn't in BRWallet. Fast-path for the M3 parent-walk —
     *  when a parent tx happens to be our own send, avoids a network
     *  round-trip. */
    external fun getSerializedTransactionForHash(txHashHex: String): ByteArray?

    /** Derive a DigiAsset v3 asset ID from its issuance transaction's first
     *  input outpoint. Locked path only (common case — matches La/Lh/Ld
     *  prefixes); unlocked path requires the spent output's scriptPubKey
     *  and returns null until M3 parent-walk is wired.
     *
     *  @param aggregation  0 = aggregatable, 1 = hybrid, 2 = dispersed
     *  @param divisibility 0..7 (decimal places from issuance flags) */
    external fun deriveIssuanceAssetId(
        firstInputTxidHex: String,
        firstInputVout: Int,
        locked: Boolean,
        aggregation: Int,
        divisibility: Int,
    ): String?

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
    /** Resolve a DigiByte address (legacy D... or bech32 dgb1q...) into its
     *  scriptPubKey bytes. Used by the asset-UTXO refresh path to populate
     *  UtxoEntity.scriptPubKey from listunspent responses that only carry
     *  the address string. Returns null for invalid addresses. */
    external fun addressToScriptPubKey(address: String): ByteArray?

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

    /**
     * Test-support: re-parse a serialized tx (hex) and return
     * BRTransactionIsSigned(). Used by the Layer-B signed-tx KAT.
     */
    external fun isRawTransactionSigned(rawTxHex: String): Boolean

    // ---------- BIP 158 sync mode ----------
    // All BIP 158 functions below are inert until setSyncMode is called with
    // a mode other than BLOOM_ONLY. Default remains BLOOM_ONLY so existing
    // wallets see no behavior change.

    /** Sync mode constants — keep in sync with BRSyncMode in BRPeerManager.h */
    object SyncMode {
        const val BLOOM_ONLY = 0            // legacy BIP 37 SPV
        const val COMPACT_FILTERS_ONLY = 1  // BIP 157/158 only
        const val BOTH = 2                  // both paths in parallel
    }

    /** Must be called after the peer manager exists (after wallet load) and
     *  before startSync. Switching modes mid-sync requires a stop+start. */
    external fun setSyncMode(mode: Int)
    external fun getSyncMode(): Int

    /** Mid-run privacy-fallback: flips to BLOOM_ONLY AND pushes a bloom
     *  filterload to every currently-connected peer (peers that handshook
     *  while in compact-filters mode never received our filter). Called by
     *  the sync watchdog when filter peers don't make progress within 120s. */
    external fun fallbackToBloom()

    /** Current cfheaders chain tip height (0 if no headers received yet).
     *  Used by the watchdog to detect "no BIP158 progress." */
    external fun getCFChainTipHeight(): Int

    /** Re-anchor the compact-filter chain at the block floor when cfTip is stuck
     *  below the downloaded chain (legacy deficit). Returns true if re-anchored. */
    external fun reanchorCompactFilterChainAtFloor(): Boolean

    /** Serialize the in-memory filter-header chain. Returns null if no chain
     *  exists yet or sync mode is BLOOM_ONLY. Persist this blob to durable
     *  storage so it can be restored via setCompactFilterChain on next open. */
    external fun getCompactFilterChain(): ByteArray?

    /** Restore a previously persisted filter-header chain. Returns false on
     *  malformed input. Must be called before startSync. */
    external fun setCompactFilterChain(data: ByteArray): Boolean

    /** Ask any connected filter-capable peer for cfilters over an inclusive
     *  height range. Returns number of blocks actually requested (capped at
     *  MAX_CFILTERS_RESULTS = 1000). */
    external fun requestCompactFilters(startHeight: Long, stopHeight: Long): Long

    /** Enable automatic cfilter requesting. Every successful cfheaders batch
     *  triggers a cfilter request for the new range starting at
     *  max(startHeight, lastRequested+1), capped at MAX_CFILTERS_RESULTS.
     *  Pass the wallet's birth height (0 to scan from genesis). */
    external fun enableAutoCompactFilterFetch(startHeight: Long)
    external fun disableAutoCompactFilterFetch()
}
