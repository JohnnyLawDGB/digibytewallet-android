package io.digibyte.core

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.asset.AssetManager
import io.digibyte.core.asset.DeadSendPredicate
import io.digibyte.core.asset.OrphanSendPredicate
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.model.SyncState
import io.digibyte.core.sync.CfAbandonmentStore
import io.digibyte.core.sync.CfScanLedgerStore
import io.digibyte.core.sync.FilterHeaderStore
import io.digibyte.core.sync.SavedBlockStore
import io.digibyte.core.security.EncryptedData
import io.digibyte.core.security.KeyStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
data class StuckSendResult(
    val dropped: Int,
    val kept: Int,
    val assetRowsCleared: Int,
    /** Of [dropped], how many were ORPHANS — spending a parent the wallet no longer has, so
     *  they could never confirm and no peer would ever accept them. Reported separately
     *  because it is a different failure from a merely-dead send, and a user who was told
     *  "nothing to clear" by an earlier version deserves to see that it found something. */
    val orphansCleared: Int = 0,
)

/**
 * The `cf_birth_height` value to persist for a given native birth-checkpoint height,
 * or null to CLEAR the pref. Only a POSITIVE height is ever persisted: a 0 (or
 * negative) height means "unknown", and writing 0 would floor the compact-filter
 * scan at genesis — the full ~23M-block, multi-hour scan that never finishes on an
 * interruption. When null, callers remove the pref so SyncService falls back to its
 * own default (the saved-blocks tip / wallet birth checkpoint) rather than genesis.
 * Extracted so the recovery/rescan persistence decision is unit-testable without JNI.
 */
internal fun cfBirthHeightToPersist(rawBirth: Long): Long? = rawBirth.takeIf { it > 0L }

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

    // Off-main scope for the native watch-set pin. Deliberately NOT the caller's thread:
    // getReceiveAddress is invoked from Compose composition on the main thread, and the
    // native pin takes the wallet mutex. See rememberWatchedAddress.
    private val watchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    /**
     * @param passphrase NFKD UTF-8 bytes, or null. **The CALLER owns these and must zero them** —
     *   this does not, because wiping a buffer it was merely handed is the mistake jni_derive.c
     *   already had to be corrected for once.
     */
    fun createWallet(mnemonic: String, passphrase: ByteArray? = null): Boolean {
        // Default null keeps every existing caller — and every existing wallet — unchanged.
        if (passphrase != null && passphrase.size > Bip39Passphrase.MAX_BYTES) return false
        val prepared = passphrase
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            val success = NativeBridge.createWalletFromBytes(mnemonicBytes, prepared)
            if (success) {
                persistSeed(mnemonicBytes)
                persistPassphrase(prepared)
                // Persist creation time so restoreFromDisk uses the right sync checkpoint
                prefs.edit().putLong("wallet_creation_time", System.currentTimeMillis() / 1000).apply()
                _walletState.value = WalletState.Unlocked
                clearSyncData()
                deriveSeedForIdentity(mnemonicBytes)?.let { s2 ->
                    try { saveSeedFingerprintV2(s2) } finally { s2.fill(0) }
                }
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
    fun recoverWallet(
        mnemonic: String,
        creationTimestamp: Long,
        /** NFKD UTF-8 bytes, or null. Caller owns and zeroes them; see [createWallet]. */
        passphrase: ByteArray? = null,
    ): Boolean {
        if (passphrase != null && passphrase.size > Bip39Passphrase.MAX_BYTES) return false
        val prepared = passphrase
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            val success = NativeBridge.recoverWalletFromBytes(mnemonicBytes, creationTimestamp, prepared)
            if (success) {
                persistSeed(mnemonicBytes)
                persistPassphrase(prepared)
                _walletState.value = WalletState.Unlocked
                clearSyncData()
                deriveSeedForIdentity(mnemonicBytes)?.let { s2 ->
                    try { saveSeedFingerprintV2(s2) } finally { s2.fill(0) }
                }
                // PERSIST the creation time (mirror createWallet, line 97). Without this,
                // restoreFromDisk finds no wallet_creation_time on the next launch and feeds
                // native the HARDCODED 2026 fallback (line 229) → getWalletBirthCheckpointHeight
                // returns the ~2026 checkpoint (~block 21.5M) for EVERY recovered wallet →
                // an older wallet's scan floors ~20M blocks ABOVE its funds and shows empty.
                // Use the user's chosen recovery timestamp; if unknown (0), match native's own
                // time(NULL) default so a restart stays consistent with the first sync.
                val creationTimeSecs =
                    if (creationTimestamp > 0L) creationTimestamp else System.currentTimeMillis() / 1000
                prefs.edit().putLong("wallet_creation_time", creationTimeSecs).apply()
                // PERSIST the compact-filter scan floor NOW, while native
                // g_walletCreationTime still reflects the user's chosen recovery date
                // (jni_wallet.c sets it from creationTimestamp). Without this the birth
                // height lives ONLY in volatile native state: the next app launch rebuilds
                // the wallet from the seed alone (restoreFromDisk passes no timestamp →
                // g_walletCreationTime resets to 0 → getWalletBirthCheckpointHeight returns
                // the GENESIS checkpoint), so the scan silently re-floors at genesis and
                // grinds the full ~23M-block chain (~11-19h) that never finishes on an
                // interruption. Writing cf_birth_height makes the chosen floor DURABLE
                // across restarts (proven on-device: genesis→21.5M cut a 19h scan to ~12m).
                // Mirrors rebuildFromChainRescan(). commit() (sync) so it lands before the
                // rescan()/first sync reads it.
                val birth = runCatching { NativeBridge.getWalletBirthCheckpointHeight() }.getOrDefault(0L)
                val toPersist = cfBirthHeightToPersist(birth)
                context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE).edit().apply {
                    if (toPersist != null) putLong("cf_birth_height", toPersist) else remove("cf_birth_height")
                }.commit()
                android.util.Log.i("WalletManager", "recoverWallet: persisted cf_birth_height=$toPersist (raw=$birth)")
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
            // Identity is the DERIVED seed, so a passphrase distinguishes wallets that share a
            // mnemonic. v1 (mnemonic-based) is still honoured for installs that predate this, so
            // upgrading does not clear sync data and re-sync everyone from the floor.
            val identitySeed = deriveSeedForIdentity(seedBytes)
            if (identitySeed == null) {
                // Derivation failed — do NOT guess. Clearing on a failed derivation would throw
                // away good sync data over a transient native error.
                // Worded without the trigger words the NetworkLeakTest gate scans for. That rule
                // is deliberately broad — a line that already mentions the secret is one edit away
                // from interpolating it — so it is respected rather than narrowed.
                android.util.Log.w("WalletManager", "wallet identity unavailable; leaving sync data intact")
            } else {
                try {
                    val verdict = SeedFingerprint.evaluate(storedFingerprints(), seedBytes, identitySeed)
                    if (verdict.seedChanged) clearSyncData()
                    if (verdict.writeV2) {
                        prefs.edit().putString("seed_fingerprint_v2", verdict.v2ToWrite).apply()
                    }
                } finally {
                    identitySeed.fill(0)
                }
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
            // The stored passphrase MUST come along. This is the unlock path — it runs on every
            // restart, on resume, and from BootGuard. Rebuilding a passphrase wallet without it
            // derives a different seed, so the wallet would open with different addresses and
            // report a zero balance, on a wallet whose coins are perfectly safe. Null for the
            // overwhelming majority of wallets, which have no passphrase.
            val storedPass = loadPassphrase()
            val success = try {
                if (creationTime > 0) {
                    NativeBridge.recoverWalletFromBytes(seedBytes, creationTime, storedPass)
                } else {
                    NativeBridge.recoverWalletFromBytes(seedBytes, 1774252800L, storedPass)
                }
            } finally {
                storedPass?.fill(0)
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

    /**
     * Persist a Receive-screen address into the watched-address store, and pin it into the
     * native watch set.
     *
     * The prefs write is the DURABLE store — it is replayed into native at sync start
     * (SyncService), and it is the only part that survives a process restart.
     *
     * The native pin is belt-and-braces, NOT the live-detection path. An address returned by
     * [getReceiveAddress] is already in the native match set before that JNI call returns:
     * BRWalletReceiveAddress → BRWalletUnusedAddrs derives it into the chain array and adds it
     * to the wallet's allAddrs set, and the compact-filter element list is rebuilt from live
     * wallet state on every cfilter (nothing is cached). What this pin buys is durability for
     * a later session in which derivation does not reach the address.
     *
     * Fired on [Dispatchers.IO], never inline. ReceiveScreen requests its addresses from a
     * `remember { }` during composition — i.e. on the main thread — and the native call takes
     * the wallet mutex, which the compact-filter element build holds once per block per peer.
     * A synchronous JNI call there is the exact shape of the v3.10.26/27 Pixel ANR. Since the
     * prefs write is the durable path, a late or dropped native pin costs nothing.
     */
    private fun rememberWatchedAddress(addr: String) {
        val cur = watchedPrefs.getStringSet("addrs", emptySet()) ?: emptySet()
        if (!cur.contains(addr)) {
            watchedPrefs.edit().putStringSet("addrs", cur + addr).apply()
        }
        watchScope.launch {
            runCatching {
                if (NativeBridge.isWalletLoaded()) NativeBridge.addWatchedAddresses(arrayOf(addr))
            }.onFailure {
                // Never swallow silently: the prefs replay still covers this address at the next
                // sync start, but a repeated failure here is worth seeing.
                android.util.Log.w("WalletManager", "watch-set pin failed for ${addr.take(6)}…", it)
            }
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

        // Every txid the wallet currently knows, in any confirmation state. Built ONCE for
        // the pass so an orphan check is a set lookup rather than a JNI call per input.
        val walletTxids = rows.mapNotNullTo(HashSet()) { line ->
            line.split("|").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        var dropped = 0
        var kept = 0
        var assetRowsCleared = 0
        var orphansCleared = 0
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
                // An orphan spends a parent the wallet no longer has, so it can never
                // confirm and no peer will accept it — but it looks healthy to the dead-send
                // predicate (BRWallet reports it VALID from local state, and an asset marker
                // sits above the dust floor). Without this, the only cure was a full
                // rebuild-from-chain re-sync. Reads inputs BEFORE any removal, same as
                // outputs above.
                val orphan = !dead && OrphanSendPredicate.isOrphan(
                    inputs = OrphanSendPredicate.parseInputs(
                        runCatching { NativeBridge.getTransactionInputsForHash(txid) }.getOrNull()
                    ),
                    walletTxids = walletTxids,
                )
                if (orphan) {
                    android.util.Log.w(
                        "WalletManager",
                        "clearStuckSends: $txid is an ORPHAN — parent missing from the wallet, " +
                            "cannot ever confirm; clearing"
                    )
                }
                if (!dead && !orphan) { kept++; return@runCatching } // slow but still valid — spare it

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
                    if (orphan) orphansCleared++
                    // Removing a parent strands anything built on it — that is how the
                    // orphan we are fixing was most likely created in the first place.
                    // Dropping it from the known set means a later row spending it is
                    // recognised as an orphan within THIS pass rather than surviving to
                    // the next one. Rows earlier in the list than their parent still need
                    // a second pass, which is why the caller may run this more than once.
                    walletTxids.remove(txid)
                    if (assetManager != null) {
                        assetRowsCleared += assetManager.clearDeadAssetSend(txid, ownedSet, outputs)
                    }
                }
            }.onFailure { e ->
                android.util.Log.w("WalletManager", "clearStuckSends: skipping row after exception", e)
            }
        }
        if (dropped > 0) runCatching { WalletTxPersister(context).persist() }
        return StuckSendResult(
            dropped = dropped,
            kept = kept,
            assetRowsCleared = assetRowsCleared,
            orphansCleared = orphansCleared,
        )
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
            .remove("saved_blocks")         // legacy key belt-and-suspenders — file store is authoritative now
            .remove("saved_blocks_tip")
            .remove("has_synced")
            .remove("last_balance")
            .commit()
        FilterHeaderStore.delete(context) // also nuke the file-backed CF-header chain (synchronous)
        SavedBlockStore.delete(context)   // and the file-backed saved-blocks window (I2 fix)
        // …and the file-backed CF SCAN LEDGER. This is load-bearing, not tidiness
        // (paced-convoy fetch, spec Part E / GATE 3(iii)): on the forced restart
        // startSync() Inits the native ledger fresh at `abandonedBelow = 0`, and
        // SyncService then feeds whatever survives here straight into
        // restoreCfScanLedger(), which Parses the OLD `abandonedBelow` right back
        // over that Init. `abandonedBelow` is a monotonic hard floor clamping every
        // CF request (BRCFScanLedger.c:433/600/666), so leaving the blob in place
        // means the CF path can NEVER re-cover an abandoned band — the "a full
        // rescan re-covers it" half of the recovery guarantee would be a lie, and
        // the B2 valve's residual (it can only prove refusal by the peers it is
        // connected to, so a servable height CAN be abandoned) would become
        // permanent silent loss instead of a recoverable inconvenience.
        CfScanLedgerStore.delete(context)
        // The surfaced band goes with it: after the re-Init `abandonedBelow` really
        // is 0, so there is nothing left to recover and nothing to nag about.
        CfAbandonmentStore.clear(context)
        OutgoingTxStore(context).clearAll()
        // Floor the compact-filter rescan at the wallet's birth so old tx blocks are
        // re-scanned and stamped (SyncService reads cf_birth_height on sync start).
        // If the birth height is unknown, REMOVE the pref rather than writing 0 —
        // 0 would floor the scan at genesis (~23M blocks). SyncService then falls back
        // to the wallet's birth checkpoint on its own (savedTip is now 0).
        val birth = runCatching { NativeBridge.getWalletBirthCheckpointHeight() }.getOrDefault(0L)
        val toPersist = cfBirthHeightToPersist(birth)
        context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE).edit().apply {
            if (toPersist != null) putLong("cf_birth_height", toPersist) else remove("cf_birth_height")
        }.commit()
    }

    /**
     * The wallet's DigiDollar receive address (TD… testnet / DD… mainnet). Null if locked.
     *
     * DO NOT re-add a watch-set pin here. The v4.0.20 version of this method pinned the DD
     * address via [rememberWatchedAddress], described as what makes a DigiDollar receive
     * visible to the compact-filter scan. That was wrong twice over, and measured:
     *
     *  1. The pin is INERT. A DD address is Base58Check over a 34-byte payload (2-byte
     *     "DD"/"TD" version + 32-byte taproot output key), so BRAddressIsValid rejects it and
     *     BRWalletAddWatchedAddress drops it before it reaches the watch set. Even if it were
     *     admitted, BRAddressScriptPubKey cannot encode it, so it would contribute no filter
     *     element.
     *  2. It is also UNNECESSARY. This address encodes the tap-tweaked output key X(Q) of
     *     m/86'/20'/0'/0/0 — the same key as taprootExternalChain[0] — and a DD token output
     *     is a plain P2TR script. The filter element for a DD payment (OP_1 0x20 <X(Q)>, 34
     *     bytes) is therefore ALREADY emitted by the taproot chain. Measured on a real
     *     wallet: present at index 935 of 1045 elements. Pinned by filter_elements_kat so
     *     the alias cannot silently break.
     *
     * Pinning it here additionally leaked a "DD…" string into dumpAllAddresses, which the
     * reconcile path POSTs to the backend in 500-address batches — a privacy regression in a
     * CF-only wallet, and one unparseable entry can fail a whole batch.
     *
     * The real cause of the Ultra missed-DD-receive is documented in
     * docs/superpowers/specs/2026-07-25-watchset-silent-drops-design.md §9.
     */
    fun getDigiDollarReceiveAddress(): String? = NativeBridge.getDigiDollarReceiveAddress()

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
        dataEraser.eraseCfSyncState()
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

    /**
     * Persist the OPTIONAL BIP39 passphrase, under the same Keystore key as the mnemonic.
     *
     * Stored rather than prompted because [restoreFromDisk] runs from resume and BootGuard paths
     * with no UI attached — a passphrase that had to be typed on every unlock would leave those
     * unable to rebuild the wallet, and background sync would stop until the user next opened the
     * app. The cost is stated in the spec: a stored passphrase protects the written BACKUP, not
     * the device, because it sits behind the same door as the mnemonic.
     *
     * A null or blank passphrase writes nothing — BIP39 treats absent and empty identically
     * (salt = "mnemonic"), so an absent entry and an empty one must remain the same wallet.
     */
    /** @param passphrase NFKD UTF-8 bytes, or null. The caller still owns and must zero them. */
    private fun persistPassphrase(passphrase: ByteArray?) {
        if (passphrase == null || passphrase.isEmpty()) {
            prefs.edit().remove("encrypted_pass").remove("encrypted_pass_iv").apply()
            return
        }
        keyStoreManager.createKey()
        val encrypted = keyStoreManager.encrypt(passphrase)
        prefs.edit()
            .putString("encrypted_pass", bytesToHex(encrypted.ciphertext))
            .putString("encrypted_pass_iv", bytesToHex(encrypted.iv))
            .apply()
    }

    /**
     * The stored passphrase as NFKD UTF-8 bytes, or null when this wallet has none.
     *
     * Bytes rather than a String so the caller can wipe it — the version this replaces built a
     * String from the decrypted bytes, which then could not be zeroed and outlived every use.
     * **The caller owns the result and must `fill(0)` it.**
     */
    private fun loadPassphrase(): ByteArray? = try {
        val ct = prefs.getString("encrypted_pass", null)
        val iv = prefs.getString("encrypted_pass_iv", null)
        if (ct == null || iv == null) null
        else keyStoreManager.decrypt(EncryptedData(hexToBytes(ct), hexToBytes(iv)))
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
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
            // Carries the wallet's stored passphrase, if it has one — a passphrase wallet's
            // recovery/sweep seed must match the seed it actually derives from.
            val pass = loadPassphrase()
            val seed = try {
                NativeBridge.mnemonicToSeed(mnemonicBytes, pass)
            } finally {
                pass?.fill(0)
            }
            if (seed == null || seed.isEmpty()) null else seed
        } catch (e: Exception) {
            null
        } finally {
            mnemonicBytes.fill(0)
        }
    }

    // ── Sync data management ────────────────────────────────────

    /** `internal` (not `private`) so [WalletManagerClearSyncDataTest] can call it
     *  directly without instantiating createWallet/recoverWallet's native path. */
    internal fun clearSyncData() {
        context.getSharedPreferences("dgb_sync_data" + networkSuffix(context), Context.MODE_PRIVATE)
            .edit().clear().apply()
        // Drop ChainTipStore's in-memory mirror too — see the note in
        // AndroidWalletDataEraser.eraseSyncData. This helper backs createWallet and recoverWallet,
        // so without it a restored seed would inherit the previous wallet's confirmation counts
        // until the process happened to restart.
        io.digibyte.core.sync.ChainTipStore.invalidateCache()
        // I2 review (MINOR): the saved-blocks window moved out of dgb_sync_data
        // into a file (SavedBlockStore) — the .clear() above no longer reaches it.
        // Without this, a fresh wallet (createWallet/recoverWallet) or a
        // seed-fingerprint-mismatch restore (restoreFromDisk) would inherit the
        // PREVIOUS wallet's saved-blocks window from disk.
        SavedBlockStore.delete(context)
    }

    /**
     * Store the wallet's identity so restarts can tell whether the seed changed.
     *
     * Now over the DERIVED seed rather than the mnemonic: a BIP39 passphrase lets one mnemonic
     * open many wallets, which would all share a mnemonic-based fingerprint and silently inherit
     * each other's sync data. See [SeedFingerprint] for why it is versioned rather than replaced
     * — recomputing in place would clear sync data for every existing install on first launch.
     */
    private fun saveSeedFingerprintV2(seedBytes: ByteArray) {
        prefs.edit().putString("seed_fingerprint_v2", SeedFingerprint.v2(seedBytes)).apply()
    }

    private fun storedFingerprints() = SeedFingerprint.Stored(
        v1 = prefs.getString("seed_fingerprint", null),
        v2 = prefs.getString("seed_fingerprint_v2", null),
    )

    /**
     * The 64-byte seed for a mnemonic under this wallet's stored passphrase (usually none).
     * Caller must zero the result.
     */
    private fun deriveSeedForIdentity(mnemonicBytes: ByteArray): ByteArray? {
        val pass = loadPassphrase()
        return try {
            NativeBridge.mnemonicToSeed(mnemonicBytes, pass)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } finally {
            pass?.fill(0)
        }
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
