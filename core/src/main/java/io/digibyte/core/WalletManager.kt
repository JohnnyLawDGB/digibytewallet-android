package io.digibyte.core

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.asset.DeadSendPredicate
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.model.SyncState
import io.digibyte.core.sync.FilterHeaderStore
import io.digibyte.core.security.EncryptedData
import io.digibyte.core.security.KeyStoreManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class WalletState {
    data object NoWallet : WalletState()
    data object Locked : WalletState()
    data object Unlocked : WalletState()
}

/**
 * Outcome of [WalletManager.clearStuckSends]: [dropped] un-mineable sends
 * removed, [kept] left alone (either confirmed on-chain or unconfirmed-but-
 * still-valid), and [assetRowsCleared] phantom owned DigiAsset UTXO rows a
 * dropped dead send fabricated (see [AssetManager.clearDeadAssetSend]).
 */
data class StuckSendResult(val dropped: Int, val kept: Int, val assetRowsCleared: Int)

class WalletManager(
    private val context: Context,
    private val keyStoreManager: KeyStoreManager,
    private val utxoManager: UtxoManager,
    // Persistent, non-native destructive routine — injectable so wipeWallet() is
    // unit-testable and the manual + auto (PIN wipe-after-N) wipes share one path.
    private val dataEraser: WalletDataEraser = AndroidWalletDataEraser(context),
    // Native pre-wipe quiesce, injectable so a pure-JVM wipe test needn't load the
    // native lib (NativeBridge's init { System.loadLibrary } would crash off-device).
    // The default lambda body only touches NativeBridge when actually invoked.
    private val quiesceNative: () -> Unit = { NativeBridge.stopSync(); NativeBridge.lockSession() },
    // Nullable + defaulted so existing pure-JVM constructions (WalletWipeTest)
    // keep compiling unchanged. No circular dependency: AssetManager (and
    // everything it depends on — DAOs, AssetMetadataService, the network
    // client) never references WalletManager, so this is a one-directional
    // edge in the Hilt graph. Only clearStuckSends() uses it (dead-send
    // phantom asset-row cleanup); every other WalletManager method is
    // unaffected if it's null.
    private val assetManager: AssetManager? = null,
) {
    private val _walletState = MutableStateFlow<WalletState>(WalletState.NoWallet)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dgb_wallet_seed", Context.MODE_PRIVATE)

    // Every address ever shown on the Receive screen, persisted so it can be re-pinned
    // into the native BIP158 watch set on each load — a receive to it can never fall
    // outside the derived gap window and be missed. See NativeBridge.addWatchedAddresses.
    private val watchedPrefs: SharedPreferences =
        context.getSharedPreferences("dgb_watched_addrs", Context.MODE_PRIVATE)

    init {
        // Check if a wallet exists on disk
        if (hasSavedWallet()) {
            _walletState.value = WalletState.Locked
        }
    }

    /** Check if an encrypted seed exists on disk. */
    fun hasSavedWallet(): Boolean = prefs.contains("encrypted_seed")

    /**
     * Create a new wallet from a mnemonic phrase.
     * Encrypts and persists the phrase to disk.
     */
    fun createWallet(mnemonic: String): Boolean {
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            val success = NativeBridge.createWalletFromBytes(mnemonicBytes)
            if (success) {
                persistSeed(mnemonicBytes)
                // Persist creation time so restoreFromDisk uses the right sync checkpoint
                prefs.edit().putLong("wallet_creation_time", System.currentTimeMillis() / 1000).apply()
                _walletState.value = WalletState.Unlocked
                clearSyncData()
                saveSeedFingerprint(mnemonicBytes)
                NativeBridge.rescan()
            }
            return success
        } finally {
            mnemonicBytes.fill(0)
        }
    }

    /**
     * Recover wallet from mnemonic and creation timestamp.
     * Encrypts and persists the phrase to disk.
     */
    fun recoverWallet(mnemonic: String, creationTimestamp: Long): Boolean {
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            val success = NativeBridge.recoverWalletFromBytes(mnemonicBytes, creationTimestamp)
            if (success) {
                persistSeed(mnemonicBytes)
                _walletState.value = WalletState.Unlocked
                clearSyncData()
                saveSeedFingerprint(mnemonicBytes)
                NativeBridge.rescan()
            }
            return success
        } finally {
            mnemonicBytes.fill(0)
        }
    }

    /**
     * Restore wallet from persisted encrypted seed on app restart.
     * Stops any running sync first to avoid use-after-free crashes.
     * Returns true if the wallet was successfully restored.
     */
    fun restoreFromDisk(): Boolean {
        val seedBytes = loadSeed() ?: return false
        try {
            // CRITICAL: stop sync before replacing the wallet — the peer manager's
            // background threads are using the old wallet pointer. Freeing it
            // while they're running causes SIGSEGV.
            NativeBridge.stopSync()
            // Wait for peer manager threads to fully drain. 200ms was insufficient —
            // SIGSEGV crashes were observed on the DefaultDispatch thread after the
            // old wallet was freed. Poll peer count to confirm disconnection, with
            // a hard cap to avoid hanging.
            var waitMs = 0
            while (NativeBridge.getPeerCount() > 0 && waitMs < 2000) {
                Thread.sleep(100)
                waitMs += 100
            }
            // Extra settle time for threads that may be mid-callback
            Thread.sleep(300)

            // Only clear saved blocks/peers if the seed has changed (e.g. after
            // uninstall/reinstall with a different mnemonic). On normal app restarts
            // the seed is the same, so we KEEP the saved blocks to resume sync.
            if (!seedFingerprintMatches(seedBytes)) {
                clearSyncData()
                saveSeedFingerprint(seedBytes)
            }

            // BIP84 upgrade detection: mark migration complete.
            // Do NOT clear saved blocks or force a rescan — saved transactions
            // already have correct parent/child relationships from the bulk-add.
            // A forced rescan corrupts send transaction amounts because
            // _BRWalletUpdateBalance rebuilds the UTXO chain incrementally,
            // causing BRWalletAmountSentByTx to return wrong values mid-rescan.
            // The wider bloom filter (830 addresses) takes effect naturally
            // on the next sync cycle without needing a full rescan.
            val migrationPrefs = context.getSharedPreferences("dgb_sync_data" + networkSuffix(context), android.content.Context.MODE_PRIVATE)
            if (!migrationPrefs.getBoolean("bip84_migrated", false)) {
                android.util.Log.i("WalletManager", "BIP84 upgrade detected — marking migration (no rescan)")
                migrationPrefs.edit()
                    .putBoolean("bip84_migrated", true)
                    .apply()
            }

            // Load saved transactions BEFORE creating wallet — recoverWallet uses them
            // so the wallet starts with full tx history and balance is immediately spendable.
            val syncPrefs = context.getSharedPreferences("dgb_sync_data" + networkSuffix(context), android.content.Context.MODE_PRIVATE)
            val savedTxHex = syncPrefs.getString("saved_transactions", null)
            if (savedTxHex != null) {
                // A bad tx cache must NEVER abort the restore or block the seed load
                // below — it's just a cache re-derivable from chain. The naive
                // chunked(2).map { it.toInt(16) } decoder this replaces threw
                // NumberFormatException on any non-hex byte, and that throw escaped
                // this function's try/finally (only seedBytes is zeroed there),
                // crashing startup on a corrupt blob. decodeSavedTransactionsOrNull
                // validates hex first and returns null instead of throwing; the
                // whole block is additionally wrapped so no other decode/native
                // exception can escape either.
                try {
                    val txBytes = decodeSavedTransactionsOrNull(savedTxHex)
                    if (txBytes != null) {
                        val loaded = NativeBridge.loadSerializedTransactions(txBytes)
                        android.util.Log.i("WalletManager", "Loaded $loaded saved transactions for restore")
                    } else {
                        android.util.Log.w("WalletManager", "Dropping corrupt saved_transactions blob (invalid hex) — continuing restore from seed")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WalletManager", "saved_transactions restore failed — dropping and continuing", e)
                }
            }

            // Use recoverWalletFromBytes with the original creation timestamp so the
            // peer manager starts syncing from the right checkpoint — not NOW.
            val creationTime = prefs.getLong("wallet_creation_time", 0L)
            val success = if (creationTime > 0) {
                NativeBridge.recoverWalletFromBytes(seedBytes, creationTime)
            } else {
                NativeBridge.recoverWalletFromBytes(seedBytes, 1774252800L)
            }
            if (success) {
                _walletState.value = WalletState.Unlocked
            }
            return success
        } finally {
            seedBytes.fill(0)
        }
    }

    /**
     * Unlock the wallet session (after biometric/PIN auth).
     * On app restart, this restores the C core wallet from disk.
     */
    fun unlock(authToken: ByteArray): Boolean {
        // If wallet is already loaded in native memory (UI-only lock from onStop),
        // just flip the state — no need to re-derive the seed.
        if (NativeBridge.isWalletLoaded() && _walletState.value is WalletState.Locked) {
            _walletState.value = WalletState.Unlocked
            return true
        }
        // Fresh process — restore wallet from encrypted seed on disk
        if (!NativeBridge.isWalletLoaded() && hasSavedWallet()) {
            restoreFromDisk()
        }
        val success = NativeBridge.unlockSession(authToken)
        if (success) {
            _walletState.value = WalletState.Unlocked
        }
        return success
    }

    /**
     * Lock the wallet — zeros keys from C core memory.
     */
    fun lock() {
        NativeBridge.lockSession()
        _walletState.value = WalletState.Locked
    }

    /**
     * UI-only lock — sets state to Locked so PIN/biometric is required,
     * but does NOT zero the native seed. SyncService continues running
     * in the background with full signing capability.
     */
    fun lockUi() {
        _walletState.value = WalletState.Locked
    }

    /**
     * Check if the native wallet is loaded in memory (UI-only lock vs fresh process).
     */
    fun isWalletReady(): Boolean = NativeBridge.isWalletLoaded()

    /**
     * UI-only unlock — flips state without touching the native layer.
     * Used after lockUi() when the wallet is still loaded in memory.
     */
    fun unlockFromUi() {
        _walletState.value = WalletState.Unlocked
    }

    /**
     * Get a new receive address.
     */
    fun getReceiveAddress(index: Int, format: Int = 2): String? {
        val addr = NativeBridge.getReceiveAddress(index, format)
        if (!addr.isNullOrBlank()) rememberWatchedAddress(addr)
        return addr
    }

    /** Persist a Receive-screen address into the watched-address store. */
    private fun rememberWatchedAddress(addr: String) {
        val cur = watchedPrefs.getStringSet("addrs", emptySet()) ?: emptySet()
        if (!cur.contains(addr)) {
            watchedPrefs.edit().putStringSet("addrs", cur + addr).apply()
        }
    }

    /** All persisted Receive-screen addresses, to re-pin into the native watch set on load. */
    fun watchedReceiveAddresses(): Set<String> =
        watchedPrefs.getStringSet("addrs", emptySet()) ?: emptySet()

    /**
     * Recover from a stuck phantom-send chain. The wallet can build a send that spends
     * the unconfirmed change of a previous send; if that base never landed on-chain
     * (the pre-fix confirmation-bug era), every send in the chain spends a coin that
     * does not exist — so all are invalid, never mine, and the durable-resend job
     * re-fires them forever. This drops every recorded outgoing send that is NOT
     * confirmed on-chain: BRWalletRemoveTransaction cascades to dependents and
     * un-spends the real UTXO the chain tied up, then we persist the corrected tx set.
     * The compact-filter sync (already at the chain tip) keeps the restored, real
     * UTXO set confirmed. Confirmed sends are never touched. Unconfirmed sends are
     * gated on [DeadSendPredicate.isDead]: a send that's merely slow to confirm but
     * still valid (no sub-dust output, no conflict) is spared rather than dropped —
     * only a genuinely un-mineable send is removed. A removed dead send also has its
     * OWNED phantom DigiAsset UTXO rows cleaned up via [AssetManager.clearDeadAssetSend]
     * (e.g. an optimistically-inserted asset-change marker for a send that never
     * confirms) — see [StuckSendResult.assetRowsCleared]. Suspend because the asset
     * cleanup is a Room/IO call; callers should invoke this off the main thread
     * (e.g. `withContext(Dispatchers.IO) { walletManager.clearStuckSends() }`).
     */
    suspend fun clearStuckSends(): StuckSendResult {
        val store = OutgoingTxStore(context)

        // Sovereign owned-address set, built ONCE for the whole pass (never a
        // third party) so dead-send phantom asset rows can be identified.
        // Null AssetManager (no DI wired, e.g. a bare test construction) means
        // no asset cleanup happens — never a reason to skip the send cleanup.
        val ownedSet = assetManager?.buildOwnedScriptHexes() ?: emptySet()

        // Source candidates from the WALLET'S ACTUAL tx list, NOT just OutgoingTxStore:
        // a dead send created before the store existed (or by a path that never recorded
        // it) still lives in BRWallet and shows in the activity list, so it MUST be
        // clearable. Row format (jni_wallet.c getTransactionDetails):
        //   txHash|amount|fee|blockHeight|timestamp|sent|received  (TX_UNCONFIRMED = INT32_MAX)
        val rows = runCatching { NativeBridge.getTransactionDetails().trim().lines() }
            .getOrDefault(emptyList())

        var dropped = 0
        var kept = 0
        var assetRowsCleared = 0
        for (line in rows) {
            // Whole per-row body is guarded: a Room/JNI exception on one stuck tx
            // must not skip the remaining rows or the final persist() below.
            runCatching {
                if (line.isBlank()) return@runCatching
                val p = line.split("|")
                if (p.size < 7) return@runCatching
                val txid = p[0]
                val h = p[3].toLongOrNull() ?: 0L
                val sent = p[5].toLongOrNull() ?: 0L

                val confirmed = h > 0L && h < Int.MAX_VALUE.toLong()
                if (confirmed) return@runCatching                 // on-chain — never touch it
                // Only OUR OWN unconfirmed sends are stuck-send candidates. Incoming
                // (received) txs have sent==0; DigiDollar/asset RECEIVES carry sub-dust
                // marker outputs, so gating on sent>0 keeps the dead-predicate from ever
                // flagging an incoming transfer as a "dead send".
                if (sent <= 0L) return@runCatching

                // Unconfirmed OUTGOING send: read outputs BEFORE any removal
                // (removeTransaction invalidates the tx from BRWallet's view).
                val outputs = NativeBridge.getTransactionOutputsForHash(txid)?.toList() ?: emptyList()
                val isValid = runCatching { NativeBridge.isTransactionValid(txid) }.getOrDefault(true)
                val dead = DeadSendPredicate.isDead(
                    isValid = isValid,
                    outputs = outputs.map {
                        DeadSendPredicate.OutSats(it.split("|").getOrNull(1)?.toLongOrNull() ?: 0L)
                    }
                )
                if (!dead) { kept++; return@runCatching } // slow but still valid — spare it, don't touch

                // Ordering is load-bearing (see dead-asset-send-clear design doc):
                // remove the transaction from the wallet FIRST. Only once native
                // removal has actually succeeded do we drop the local record and
                // clean up owned phantom asset rows. If we deleted the Room rows
                // before removal (or removal fails), the tx is still wallet-known
                // and a subsequent sweepKnownTransactionsForAssets pass would
                // silently re-insert the exact phantom rows we'd have just deleted.
                if (runCatching { NativeBridge.removeTransaction(txid) }.getOrDefault(false)) {
                    store.remove(txid)
                    dropped++
                    if (assetManager != null) {
                        assetRowsCleared += assetManager.clearDeadAssetSend(txid, ownedSet, outputs)
                    }
                }
            }.onFailure { e ->
                android.util.Log.w("WalletManager", "clearStuckSends: skipping row after exception", e)
            }
        }
        if (dropped > 0) runCatching { WalletTxPersister(context).persist() }
        return StuckSendResult(dropped = dropped, kept = kept, assetRowsCleared = assetRowsCleared)
    }

    /**
     * Full rebuild from chain. Discards the local transaction cache, the filter-header
     * chain, and the saved block headers, and floors the compact-filter scan at the
     * wallet's birth so EVERY transaction is re-detected and block-stamped against the
     * real chain on the next launch. This cures a corrupted tx graph (phantom chained
     * sends, missing block heights that break confirmation + ordering, phantom coins)
     * without touching the SEED or derived addresses — the wallet is reconstructed
     * entirely from on-chain data. Clearing saved_blocks alongside the filter chain
     * avoids the "in-memory chain too shallow" wedge (the header chain must span the
     * rescan range for the CF driver to resolve filter stop-hashes). The caller MUST
     * restart the app afterward so the wallet reloads clean and the rescan begins.
     */
    fun rebuildFromChainRescan() {
        // Stop native sync FIRST so the peer manager can't re-persist saved_blocks /
        // saved_transactions after we clear them (its final persist, if any, runs
        // before the clear below and is therefore overwritten).
        runCatching { NativeBridge.stopSync() }
        val suffix = networkSuffix(context)
        // NOTE: commit() (synchronous), NOT apply(). The caller kills the process
        // (Runtime.exit) immediately after this returns to force a clean reload; an
        // async apply() would be dropped before it flushes, leaving the corrupt cache
        // in place (the tx graph would reload unchanged and the scan would stay at the
        // tip instead of the birth floor).
        context.getSharedPreferences("dgb_sync_data$suffix", Context.MODE_PRIVATE).edit()
            .remove("saved_transactions")   // corrupt/phantom tx graph — re-derived from chain
            .remove("saved_filter_headers") // CF chain re-anchors at the birth floor
            .remove("saved_blocks")         // header chain re-syncs to span the rescan range
            .remove("has_synced")
            .remove("last_balance")
            .commit()
        FilterHeaderStore.delete(context) // also nuke the file-backed CF-header chain (synchronous)
        OutgoingTxStore(context).clearAll()
        // Floor the compact-filter rescan at the wallet's birth so old tx blocks are
        // re-scanned and stamped (SyncService reads cf_birth_height on sync start).
        // If the birth height is unknown, REMOVE the pref rather than writing 0 —
        // 0 would floor the scan at genesis (~23M blocks). SyncService then falls back
        // to the wallet's birth checkpoint on its own (savedTip is now 0).
        val birth = runCatching { NativeBridge.getWalletBirthCheckpointHeight() }.getOrDefault(0L)
        context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE).edit().apply {
            if (birth > 0L) putLong("cf_birth_height", birth) else remove("cf_birth_height")
        }.commit()
    }

    /** The wallet's DigiDollar receive address (TD… testnet / DD… mainnet). Null if locked. */
    fun getDigiDollarReceiveAddress(): String? {
        return NativeBridge.getDigiDollarReceiveAddress()
    }

    /**
     * Start SPV sync.
     */
    fun startSync() {
        NativeBridge.startSync()
        _syncState.value = SyncState.Syncing(0f, 0)
    }

    /**
     * Stop SPV sync.
     */
    fun stopSync() {
        NativeBridge.stopSync()
        _syncState.value = SyncState.Idle
    }

    /**
     * Update sync state (called from NativeCallback).
     */
    fun updateSyncState(state: SyncState) {
        _syncState.value = state
    }

    /**
     * Complete wallet wipe — destroys the seed AND all privacy-sensitive derived
     * data (tx history, address set, recorded sends, filter-header chain, Room DB),
     * so a security wipe (manual Settings OR the PIN wipe-after-N backstop) doesn't
     * leave transaction history behind. Both paths call this single routine.
     *
     * Order is crash-safe: the seed ciphertext is cleared FIRST, so a process death
     * mid-wipe leaves hasSavedWallet()=false (no half-loadable wallet).
     */
    suspend fun wipeWallet() {
        // Stop sync and disconnect peers before destroying wallet.
        quiesceNative()
        // Seed ciphertext FIRST (crash-safety invariant, see above).
        dataEraser.eraseSeedCiphertext()
        // Regenerable + privacy-sensitive persisted data.
        dataEraser.eraseSyncData()
        dataEraser.eraseBloomPeerCache()
        dataEraser.eraseWatchedAddresses()
        dataEraser.eraseOutgoingTx()
        dataEraser.eraseFilterHeaders()
        dataEraser.eraseDatabase()
        utxoManager.clearAll()
        keyStoreManager.deleteKey()
        _walletState.value = WalletState.NoWallet
        _syncState.value = SyncState.Idle
    }

    // ── Seed persistence ────────────────────────────────────────

    private fun persistSeed(mnemonicBytes: ByteArray) {
        keyStoreManager.createKey()
        val encrypted = keyStoreManager.encrypt(mnemonicBytes)
        prefs.edit()
            .putString("encrypted_seed", bytesToHex(encrypted.ciphertext))
            .putString("encrypted_seed_iv", bytesToHex(encrypted.iv))
            .apply()
    }

    private fun loadSeed(): ByteArray? {
        val ciphertextHex = prefs.getString("encrypted_seed", null) ?: return null
        val ivHex = prefs.getString("encrypted_seed_iv", null) ?: return null
        return try {
            val encrypted = EncryptedData(
                ciphertext = hexToBytes(ciphertextHex),
                iv = hexToBytes(ivHex)
            )
            keyStoreManager.decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load the wallet's **64-byte BIP39 seed** for re-runnable recovery flows
     * (classify / sweep). The on-disk secret is the BIP39 *mnemonic* (see
     * [persistSeed]); this decrypts it via the existing [loadSeed] path and
     * converts it once via [NativeBridge.mnemonicToSeed], zeroing the decrypted
     * mnemonic bytes before returning.
     *
     * CRITICAL-3: the returned array is the caller's responsibility to
     * `fill(0)` when done.
     *
     * @return the 64-byte BIP39 seed, or null if no wallet exists, decrypt
     *   failed, or seed derivation failed.
     */
    fun loadBip39Seed(): ByteArray? {
        val mnemonicBytes = loadSeed() ?: return null
        return try {
            // No passphrase: this wallet does not use a BIP39 passphrase
            // (createWallet/recoverWallet never pass one).
            val seed = NativeBridge.mnemonicToSeed(mnemonicBytes, null)
            if (seed == null || seed.isEmpty()) null else seed
        } catch (e: Exception) {
            null
        } finally {
            mnemonicBytes.fill(0)
        }
    }

    // ── Sync data management ────────────────────────────────────

    private fun clearSyncData() {
        context.getSharedPreferences("dgb_sync_data" + networkSuffix(context), Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /**
     * Store a SHA-256 fingerprint of the mnemonic so we can detect seed changes
     * on subsequent restarts without decrypting the full seed for comparison.
     */
    private fun saveSeedFingerprint(mnemonicBytes: ByteArray) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(mnemonicBytes)
        prefs.edit().putString("seed_fingerprint", bytesToHex(hash)).apply()
    }

    private fun seedFingerprintMatches(mnemonicBytes: ByteArray): Boolean {
        val saved = prefs.getString("seed_fingerprint", null) ?: return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = bytesToHex(digest.digest(mnemonicBytes))
        return saved == hash
    }

    // ── Hex utilities ────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Validate + hex-decode the `saved_transactions` restore blob for
 * [WalletManager.restoreFromDisk]. Pure and side-effect-free so it's testable
 * without constructing a [WalletManager] (which would require mocking
 * `NativeBridge`'s JNI surface).
 *
 * Reuses [FilterHeaderStore.decodeHexOrNull]'s hex-validity guard (odd length
 * or a non-hex character -> `null`) instead of the throwing
 * `chunked(2).map { it.toInt(16) }` decoder it replaces in `restoreFromDisk` —
 * that decoder threw [NumberFormatException] on any corrupt byte, and nothing
 * in `restoreFromDisk` caught it (only `finally { seedBytes.fill(0) }` ran), so
 * a corrupt tx cache crashed the whole restore instead of just being dropped.
 *
 * @return the decoded bytes, or `null` if [hex] is `null` or malformed. The
 *   caller drops a `null` result and continues restoring the wallet from the
 *   seed — the tx cache is just a cache, re-derivable from chain.
 */
internal fun decodeSavedTransactionsOrNull(hex: String?): ByteArray? {
    if (hex == null) return null
    return FilterHeaderStore.decodeHexOrNull(hex)
}
