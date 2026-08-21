package io.digibyte.core.bridge

/**
 * JNI bridge to the DigiByte C core (digibytewallet-core).
 * All cryptographic operations and peer-to-peer networking happen in native code.
 * Raw keys NEVER cross this boundary — only addresses, signed transactions, and status.
 */
object NativeBridge {
    init { System.loadLibrary("core-lib") }

    // === Network selection ===
    /** Runtime mainnet/testnet selection. MUST be called before any wallet or
     *  peer-manager creation (BRSetNetwork selects chain params + address
     *  encoding). Default is mainnet (false) — the C core itself defaults to
     *  mainnet even if this is never called, so existing behavior is
     *  unchanged unless the caller explicitly passes true. */
    external fun setNetwork(isTestnet: Boolean)

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

    /** Get a receive address. format: 0=legacy(D), 1=p2sh-segwit(S), 2=bech32/P2WPKH(dgb1q),
     *  3=Taproot/P2TR(dgb1p) — BIP86 (m/86'/20'/0'). */
    external fun getReceiveAddress(index: Int, format: Int): String?

    /** Pin addresses into the wallet's permanent BIP158 watch set (every Receive-screen
     *  address). Ensures a receive to an address that later falls outside the derived
     *  gap window is still scanned in every block. Idempotent; invalid entries ignored. */
    external fun addWatchedAddresses(addrs: Array<String>)

    /** The wallet's canonical DigiDollar receive address (TD… testnet / DD… mainnet, Base58Check).
     *  Encodes the BIP86 owner key m/86'/20'/0'/0/0's tap-tweaked output key — the same key as the
     *  first P2TR address, so received DigiDollar is watched and spendable. Null if session locked. */
    external fun getDigiDollarReceiveAddress(): String?

    /** Get a change address. format: 0=legacy(D), 1=p2sh-segwit(S), 2=bech32(dgb1). */
    external fun getChangeAddress(index: Int, format: Int): String?

    /** Get current wallet balance in satoshis. */
    external fun getBalance(): Long

    /** Get current DigiDollar balance in cents (USD). 0 if none. */
    external fun getDigiDollarBalance(): Long

    // === Transaction operations ===
    /** Create an unsigned transaction. Returns serialized tx bytes or null on failure. */
    external fun createTransaction(toAddress: String, amountSatoshis: Long, feePerKb: Long): ByteArray?

    /** Sign a transaction. Returns signed tx bytes or null on failure. */
    external fun signTransaction(unsignedTx: ByteArray): ByteArray?

    /** Publish (broadcast) a signed transaction. Returns txid hex string or null on failure. */
    external fun publishTransaction(signedTx: ByteArray): String?

    /** Build+sign+publish a DigiDollar transfer to a TD… address for `cents` USD cents.
     *  Returns the txid on success, or null on any failure (bad address, insufficient DD/DGB, sign/broadcast). */
    external fun sendDigiDollar(tdAddress: String, cents: Long): String?

    /** True if `addr` is a valid DigiDollar address for the current network (TD… testnet / DD… mainnet). */
    external fun isValidDigiDollarAddress(addr: String): Boolean

    /** DigiDollar type of the wallet-known tx with this display (BE) txid, for the
     *  activity list: 1=MINT, 2=TRANSFER, 3=REDEEM, 0=not a DigiDollar tx (or unknown).
     *  Read-only/display-only — used to label a transaction row DigiDollar vs DGB. */
    external fun digiDollarTxType(txHashHex: String): Int

    /** Wallet-relevant DigiDollar value (USD cents) moved in the wallet-known tx
     *  with this display (BE) txid, or 0 if not a DigiDollar tx / unknown.
     *  Direction-aware: [isSend]=false sums the DD outputs the wallet OWNS (received);
     *  [isSend]=true sums the DD outputs it does NOT own (sent) — otherwise a send
     *  reports the DD change, not the amount sent. Unsigned magnitude — the UI applies
     *  +/- from the DGB direction. Display-only: shows "$X" on a DigiDollar row. */
    external fun digiDollarTxAmount(txHashHex: String, isSend: Boolean): Long

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
     *  Call BEFORE startSync() to ensure the peer is tried on the next connection cycle.
     *
     *  [servicesHex] is the peer's advertised service bits (the seeder's
     *  `services_hex`, e.g. 0x44d for a BIP157/158 filter peer where bit 0x40 =
     *  SERVICES_NODE_COMPACT_FILTERS). Threading this through lets the native
     *  filter-first peer selection recognize and hold filter-capable peers.
     *  Pass 0 when unknown — the native side falls back to
     *  INJECT_DEFAULT_SERVICES (NODE_NETWORK|NODE_COMPACT_FILTERS), so an
     *  untagged peer is still treated as CF-capable (the wallet is CF-only). */
    external fun injectPeerByIp(ip: String, port: Int, servicesHex: Long)

    /** Pin the peer manager to a single own-node. When [exclusive] is true, the
     *  next startSync() suppresses the digiscope.me / testnet26 priority-peer
     *  prepend so the wallet dials ONLY this peer. Does NOT itself add the peer
     *  to the live pool — callers must ALSO call injectPeerByIp for that. */
    external fun setPinnedPeer(ip: String, port: Int, exclusive: Boolean)

    /** Clear the pinned own-node and exclusive-dialing flag — resumes normal
     *  priority-peer + seeder-sourced pool behavior on the next startSync(). */
    external fun clearPinnedPeer()

    /** 0=UNKNOWN, 1=CONNECTING, 2=CONNECTED_NOT_SERVING, 3=SERVING (BRComputeCFPeerStatus). */
    external fun compactFilterPeerStatus(ip: String, port: Int): Int

    /** Start SPV sync — connects to peers and begins header/transaction sync. */
    external fun startSync()
    /** Flag the peer manager for a clean recreate on the next startSync() — recovers
     *  from a stuck manager (0 peers that won't reconnect after long idle/Doze).
     *  Call this immediately before startSync(). */
    external fun forceReconnect()

    /** Demand-side load-spread: set the target peer connection count. Hold the full set while
     *  catching up (fast sync + wedge buffer), then drop to a few once synced so idle wallets
     *  stop pinning slots on the shared filter-node fleet. Reducing gently disconnects the
     *  excess (never the download peer or pinned own-node); increasing tops back up. */
    external fun setMaxPeerConnections(count: Int)

    /** Stop SPV sync — disconnects from all peers. */
    external fun stopSync()

    /** Rescan blockchain from the wallet's creation checkpoint.
     *  Triggers BRPeerManagerRescan — reconnects with fresh bloom filter
     *  to find transactions matching the wallet's addresses. */
    external fun rescan()

    /** Get number of currently connected peers. */
    external fun getPeerCount(): Int

    /** Send a keepalive ping to every connected peer so idle CF filter-peer connections aren't
     *  dropped by the remote node / NAT inactivity timeout. Call periodically (~every 10-20s). */
    /** Serialize the peer re-dial penalty set (address, port, absolute deadline) so it
     *  survives a process restart. Without it every cold start re-dials peers the last
     *  session already learned were behind. Null when there is nothing live to save. */
    external fun serializePeerPenalties(): ByteArray?

    /** Restore penalties saved by [serializePeerPenalties]. Entries whose window has
     *  lapsed are dropped on read, so a blob from a wallet that sat closed for an hour
     *  restores nothing. Returns how many were restored. */
    external fun loadPeerPenalties(blob: ByteArray): Int

    external fun keepAlivePeers()

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

    /** The wallet's spendable NATIVE DigiByte UTXOs (excludes asset/DD dust) as
     *  newline-separated "txidHex|vout|amountSats|scriptPubKeyHex" lines, "" if none.
     *  Sovereign source for the DigiAsset-send DGB fee — the Room is_asset=0 partition
     *  isn't reliably populated (empty on a synced wallet → "Not enough DGB for fee"). */
    external fun getSpendableDigiByteUtxos(): String

    /** Walks the inputs of a wallet-known transaction and returns each as
     *  "prevTxidHex|prevVout" (display-order hex). Used by asset-id
     *  derivation for issuance txs (we need the first input's outpoint)
     *  and by the future M3 parent-walk to recurse toward issuance. */
    external fun getTransactionInputsForHash(txHashHex: String): Array<String>?

    /**
     * The recorded result of publishing [txHashHex], or **-1 when no verdict has arrived**.
     *
     * `BRPeerManagerPublishTx` reports acceptance and failure only through an async callback
     * that fires on a peer thread, so the result cannot be returned from the publish call —
     * it has to be asked for afterwards. 0 means a peer relayed the transaction back to us
     * (genuine acceptance); non-zero is an errno. Map it through
     * [io.digibyte.core.sync.PublishOutcome] rather than comparing here, and note that -1
     * (pending) must NEVER be read as success — that is the false-success this exists to
     * remove.
     */
    external fun getPublishResult(txHashHex: String): Int

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

    /**
     * Counters from the most recent BIP158 filter-element build, pipe-separated:
     * `addrs|elements|derived|watched|dropped|allocFailures|firstDroppedPrefix`,
     * or `""` if no build has happened yet.
     *
     * `derived`/`watched` splits the element count BY SOURCE. There is no DigiDollar
     * bucket: a DD token output is a plain P2TR script, so its element is emitted by the
     * taproot chain and counts as derived.
     *
     * `dropped` should always be 0. Non-zero means an address in the wallet has no
     * encodable scriptPubKey — i.e. `BRAddressIsValid` and `BRAddressScriptPubKey` have
     * diverged and the compact-filter match set is quietly smaller than the address set,
     * which is exactly how a missed receive hides. `firstDroppedPrefix` is 6 characters
     * only, never a full address, since this is surfaced to logs.
     *
     * Cheap and lock-free; safe to poll.
     */
    external fun getFilterElementStats(): String

    /** Sovereign, chain-derived state for an asset outpoint — see
     *  [io.digibyte.core.asset.AssetSpentState]: 0 = SPENT, 1 = HELD, -1 = UNDETECTED
     *  (funding tx unknown — leave the persisted flag unchanged), -2 = CONFLICTED (the
     *  funding tx is known but invalid, i.e. a stuck send that was re-sent; its outputs
     *  will never exist on-chain). Reads the authoritative spentOutputs set plus
     *  transaction validity, not the asset-UTXO array. */
    external fun outpointSpentState(txHashHex: String, vout: Int): Int

    /** Hold an outpoint OUT of the spendable DGB UTXO set because it carries DigiAsset
     *  units the native tx-local classifier cannot see — chiefly implicit change, the
     *  units a transfer's instructions leave unassigned, which the protocol credits to
     *  the transaction's LAST output. Without this a plain DGB send can select the
     *  output and destroy the asset silently. Idempotent; NOT persistent across process
     *  restart, so registrations are replayed from the local asset rows after the wallet
     *  loads. Returns true if this call newly excluded the outpoint. */
    external fun registerAssetOutpoint(txHashHex: String, vout: Int): Boolean

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

    /** Promote an already-registered, still-unconfirmed wallet tx to its real
     *  confirming height (txid-driven — no raw hex needed). Used by the txid-based
     *  confirmation-reconcile to recover a stuck-"Pending" tx even when the node's
     *  UTXO enumeration never surfaces it (e.g. a 0-value DigiDollar token output).
     *  Re-runs the balance update, releasing a withheld DD/asset credit. Returns
     *  true only if a known, unconfirmed tx was promoted with a confirmed height. */
    external fun confirmTransaction(
        txHashHex: String,
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
    // The wallet is CF-only: SyncService always calls setSyncMode(COMPACT_FILTERS_ONLY)
    // before startSync (syncModeFor unconditionally returns COMPACT_FILTERS_ONLY — see
    // CustomNode.kt), and the native pending-mode default (jni_peer.c g_pendingSyncMode)
    // is pinned to COMPACT_FILTERS_ONLY as defense-in-depth in case a call site is ever
    // missed. BLOOM_ONLY/BOTH remain in the enum below for ABI compatibility (native int
    // cast) but are never selected by the wallet.

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

    /** Current cfheaders chain tip height (0 if no headers received yet).
     *  Used by the watchdog to detect "no BIP158 progress." */
    external fun getCFChainTipHeight(): Int

    /** True if the lock-free status mirrors (peer count, block heights, CF tip,
     *  sync mode — read via [getPeerCount], [getLastBlockHeight], etc.) have not
     *  been refreshed within the staleness bound, or never this session. The
     *  value getters always return the last mirrored sample; this is the ONLY
     *  staleness channel (never an in-band sentinel). A frozen sync loop reads
     *  stale here — the intended supervisor signal. Reads are lock-free
     *  (atomic-only), decoupled from PEER_GUARD/teardown. */
    external fun isStatusStale(): Boolean

    /**
     * Who holds the native peer-manager lock and for how long — `"<seconds>s <func>:<line>"`, or
     * null when it is free.
     *
     * Safe to call from a thread already blocked behind that lock: the native side takes NO lock
     * and never dereferences the peer manager, it reads file-static atomics. That is essential,
     * because the intended caller is the keepalive path at the exact moment `keepAlivePeers` is
     * wedged inside JNI — anything that tried to acquire the lock would wedge identically.
     *
     * The release-time `[CF-SLOW]` profiler cannot cover this: it logs on unlock, so a lock that is
     * never released prints nothing. Three device runs showed 40+ minute holds and zero [CF-SLOW].
     */
    external fun lockHolderInfo(): String?

    /** Re-anchor the compact-filter chain at the block floor when cfTip is stuck
     *  below the downloaded chain (legacy deficit). Returns true if re-anchored. */
    external fun reanchorCompactFilterChainAtFloor(): Boolean

    /** Proactively re-issue a full-locator getheaders to all connected peers to
     *  un-stick a FROZEN block-header tip (all other getheaders senders are reactive
     *  — once idle at a stale estimatedHeight, a tip with live-but-silent peers never
     *  advances). Called by the tip-stall watchdog. Returns the peer count sent to.
     *  Benign no-op on a healthy at-tip wallet (peers reply 0 headers). */
    external fun rerequestHeadersFromTip(): Int

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

    /** CF scan-ledger counters: [scannedThrough, outstanding, gaveUp, pending]
     *  (length 4). Phase-1 observe-only. */
    external fun getCfScanLedgerCounts(): LongArray

    /** CF scan-ledger hole ranges as flattened inclusive pairs
     *  [start0,end0,start1,end1,...] (may be empty). Phase-1 observe-only. */
    external fun getCfScanLedgerHoleRanges(): LongArray

    /** Restore a previously persisted CF scan ledger. Returns false on malformed
     *  input OR if called before startSync (the native peer manager must exist). */
    external fun restoreCfScanLedger(data: ByteArray): Boolean

    /** CF scan frontier: lowest height the scan still needs a header retained
     *  for == max(scannedThrough+1, abandonedBelow). This — NOT scannedThrough
     *  alone — is the paced-convoy fetch's fetch frontier. 0 if the peer
     *  manager doesn't exist yet. */
    external fun getLowestNeededHeight(): Long

    /** CF retention hard-floor watermark: heights below this have been
     *  permanently abandoned (too deep to retain) and are never re-requested.
     *  Monotonic. 0 if the peer manager doesn't exist yet. */
    external fun getAbandonedBelow(): Long

    /**
     * One step of the abandoned-band backfill: retire whatever the resident block headers
     * already allow, then request the next stretch of headers underneath the band. Returns
     * the heights retired by THIS call.
     *
     * Safe to call on every tick and safe to call forever — it re-derives everything from
     * the scan ledger and the block set, so a missed tick, a dropped peer or a process
     * restart costs time, never correctness. Does nothing when there is no abandoned band
     * or no connected peer.
     */
    external fun backfillAbandonedBandStep(): Long

    /** SPAN below the watermark (max(abandonedBelow - start, 0)) — **not** the number of
     *  heights abandoned, and not sound as a measurement. It over-reports (the span includes
     *  heights that were scanned normally, which is why the banner renders a RANGE and never
     *  a count), and it reads ZERO right after the biggest abandonment event, because the
     *  cfheaders floor snap re-Inits the ledger and moves `start` up to the same floor.
     *  Use [getAbandonedHeightsTotal]. 0 if the peer manager doesn't exist yet. */
    external fun getAbandonedCount(): Long

    /** TOTAL heights this session has actually written off, summed from what the native
     *  ledger reports as dropped on each surfacing event. Survives the three mid-session
     *  ledger re-Inits that zero [getAbandonedCount]; resets only for a genuinely new
     *  wallet (fresh / wipe / rescan). This is the number to trust when judging whether a
     *  retention change reduced abandonment. 0 if the peer manager doesn't exist yet. */
    external fun getAbandonedHeightsTotal(): Long

    /** Returns 1 while native retry recovery owns the hole that pins the compact-filter
     *  scan frontier, and 0 otherwise. Recovery retries indefinitely rather than writing
     *  off history, so destructive watchdog actions must remain suppressed while active. */
    external fun getConvoyAbandonmentPending(): Int

    /** Resume cursor reconciliation (paced-convoy fetch, spec Part B1-resume).
     *  enableAutoCompactFilterFetch arms the forward-fetch cursor at
     *  birthHeight-1 BEFORE the persisted CF scan ledger is restored via
     *  restoreCfScanLedger, which can set scannedThrough far above birthHeight.
     *  Call this ONCE, immediately after restoreCfScanLedger, to snap the
     *  cursor up to the restored scan frontier (LowestNeededHeight-1) — else
     *  the next forward fetch re-requests already-scanned history from
     *  birthHeight and drags scannedThrough back down, silently discarding the
     *  persisted progress. Returns the resulting cursor (post-snap), 0 if the
     *  peer manager doesn't exist.
     *
     *  NOT raise-only any more (fix wave C-1): on a resume the native arming clamp
     *  lands on the SAVED-BLOCKS TIP (a resumed manager's in-memory chain is the
     *  checkpoints plus the persisted [savedTip-299 .. savedTip] run, so a deep birth
     *  height cannot resolve), which put both the cursor and autoFetchCFiltersStart ~CF_CONVOY_WINDOW
     *  ABOVE the restored scan frontier and made the next forward fetch start there —
     *  marking ~10,000 never-requested heights scanned, silently. This call now also
     *  lowers them back to the frontier and SURFACES any still-needed history below
     *  the in-memory block floor as abandoned (visible via getAbandonedBelow ->
     *  CfAbandonmentStore banner -> rescan/reconcile), so nothing is ever marked
     *  scanned without being scanned or surfaced. */
    external fun snapAutoFetchThroughToScanFrontier(): Long

    /** Current forward-fetch cursor (autoFetchCFiltersThrough). For before/after
     *  logging around snapAutoFetchThroughToScanFrontier. 0 if the peer manager
     *  doesn't exist yet. */
    external fun getAutoFetchCFiltersThrough(): Long

    /** Native `CF_CONVOY_WINDOW` (BRPeerManager.h): the max the block-header /
     *  cfheader frontier may lead the CF SCAN frontier before the fetch gate
     *  suppresses the tip-racing continuations. Read at runtime rather than mirrored
     *  in Kotlin — a Kotlin copy that drifts LOW makes the watchdogs read "window not
     *  full", drop the tip-frozen conjunct and escalate during a healthy paced
     *  descent. Pure constant reader: valid before the peer manager exists. */
    external fun getConvoyWindow(): Int

    /** Native `CF_CONVOY_REARM_MAX` (BRPeerManager.h): fresh retry cycles the B2 valve
     *  grants a gaveUp hole before it may abandon it. The spec's tuning signal tells
     *  the operator to RAISE this in the C header; read at runtime so the Kotlin
     *  suppression bound (`CF_CONVOY_REARM_MAX + 1`) tracks it instead of releasing
     *  early and escalating the tip-stall watchdog into a still-productive valve.
     *  Pure constant reader: valid before the peer manager exists. */
    external fun getConvoyRearmMax(): Int
}
