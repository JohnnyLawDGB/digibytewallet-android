package app.aroundtheblock.wallet.core

import android.content.Context
import android.content.SharedPreferences
import app.aroundtheblock.wallet.core.asset.AssetManager
import app.aroundtheblock.wallet.core.asset.DeadSendPredicate
import app.aroundtheblock.wallet.core.asset.OrphanSendPredicate
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import app.aroundtheblock.wallet.core.model.SyncFrontierSession
import app.aroundtheblock.wallet.core.model.SyncState
import app.aroundtheblock.wallet.core.sync.FilterHeaderStore
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade
import app.aroundtheblock.wallet.core.sync.clearTransactionCacheForRebuild
import app.aroundtheblock.wallet.core.sync.SavedBlockStore
import app.aroundtheblock.wallet.core.security.EncryptedData
import app.aroundtheblock.wallet.core.security.KeyStoreManager
import app.aroundtheblock.wallet.core.security.KeystoreKeyInvalidatedException
import app.aroundtheblock.wallet.core.security.KeystoreUserAuthRequiredException
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

/** The creation time handed to native when the wallet is rebuilt from the stored seed before any
 *  wallet_creation_time existed. */
internal const val DEFAULT_RESTORE_CREATION_TIME = 1774252800L

// The seed record in `dgb_wallet_seed` (AND-005): written whole by WalletManager.writeSeedRecord.
private const val KEY_CREATION_TIME = "wallet_creation_time"
private const val KEY_RECORD_VERSION = "seed_record_version"
private const val KEY_HAS_PASSPHRASE = "seed_has_passphrase"
private const val KEY_FINGERPRINT_V1 = "seed_fingerprint"
private const val KEY_FINGERPRINT_V2 = "seed_fingerprint_v2"
/** How the wallet came to be on this device: [ORIGIN_CREATED] or [ORIGIN_RESTORED]; absent on a
 *  record written before 4.1.2, which may be either. */
private const val KEY_ORIGIN = "wallet_origin"
private const val ORIGIN_CREATED = "created"
private const val ORIGIN_RESTORED = "restored"

/** The version this release writes the seed record at: written whole, in one commit, saying whether it has a passphrase. */
private const val SEED_RECORD_VERSION = 1

/** Ciphertext and IV keys of the seed and passphrase envelopes, under the auth-bound key and under the legacy one. */
private val V2_ENVELOPE_FIELDS = listOf("encrypted_seed_v2", "encrypted_seed_iv_v2", "encrypted_pass_v2", "encrypted_pass_iv_v2")
private val LEGACY_ENVELOPE_FIELDS = listOf("encrypted_seed", "encrypted_seed_iv", "encrypted_pass", "encrypted_pass_iv")

/** Every field of the seed record. */
private val SEED_RECORD_FIELDS = V2_ENVELOPE_FIELDS + LEGACY_ENVELOPE_FIELDS +
    listOf(KEY_CREATION_TIME, KEY_RECORD_VERSION, KEY_HAS_PASSPHRASE, KEY_FINGERPRINT_V1, KEY_FINGERPRINT_V2, KEY_ORIGIN)

/**
 * The creation time handed to native on a restore. Native picks the header anchor (and
 * `getWalletBirthCheckpointHeight`) from it: the latest checkpoint at least a week older. After a
 * one-time history rebuild, [floorTime] is the lowest time the discarded cache gave — its records'
 * times, confirmed or not, and the time of the checkpoint at or below its oldest confirmed height
 * (see [HistoryRebuildOnUpgrade.KEY_FLOOR_TIME]); lowering the time to it keeps that history above
 * the anchor, so the scan can reach it. Never raised; the stored preference is not changed.
 */
internal fun creationTimeForRestore(stored: Long, floorTime: Long): Long {
    val base = if (stored > 0L) stored else DEFAULT_RESTORE_CREATION_TIME
    return if (floorTime > 0L) minOf(base, floorTime) else base
}

// The seams below (each defaulting to the production call) are what let the wipe, the record write
// and the key migration run on a plain JVM; grouping them would hide which call each test replaces.
class WalletManager @Suppress("LongParameterList") constructor(
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
    // The app-side half of a wipe (Hub login held in memory, DigiStamp web session) — core
    // cannot see those, so the app module supplies it (AppModule). Defaulted so pure-JVM
    // constructions keep compiling; the default has nothing to end and says so truthfully.
    private val identitySessions: IdentitySessionWipe = IdentitySessionWipe { true },
    /**
     * Marks the erasure owed, durably, BEFORE a wipe erases anything (4.1.2 row 24): a process death in
     * the seconds the wipe runs used to leave the Hub token, the DB key and the database with no retry
     * once the seed was gone. The app's launch backstop finishes an owed erasure and clears the mark
     * when a wipe verifies (FreshStartAfterWipe).
     */
    private val markErasureOwed: () -> Unit = {},
    // Native wallet creation and the native calls around storing a wallet's record, injectable so
    // a pure-JVM test can drive createWallet/recoverWallet and the seed record they write
    // (NativeBridge's init loads the native library, which a JVM cannot). Each default touches
    // NativeBridge only when invoked.
    private val createNative: (mnemonic: ByteArray, passphrase: ByteArray?) -> Boolean =
        { m, p -> NativeBridge.createWalletFromBytes(m, p) },
    private val recoverNative: (mnemonic: ByteArray, creationTime: Long, passphrase: ByteArray?) -> Boolean =
        { m, t, p -> NativeBridge.recoverWalletFromBytes(m, t, p) },
    private val seedNative: (mnemonic: ByteArray, passphrase: ByteArray?) -> ByteArray? =
        { m, p -> NativeBridge.mnemonicToSeed(m, p) },
    private val rescanNative: () -> Unit = { NativeBridge.rescan() },
    private val birthHeightNative: () -> Long = { NativeBridge.getWalletBirthCheckpointHeight() },
    // The database password's record must not depend on the seed's legacy key when that key is
    // deleted. Injectable for the same reason as the above; the default reaches the Keystore only
    // when invoked.
    private val releaseDatabaseKey: () -> Boolean =
        { app.aroundtheblock.wallet.core.db.DatabaseKeyProvisioner.forContext(context).releaseLegacyWrap() },
) {
    // Sessions running for the wallet in this process (the sync service), stopped by every wipe
    // before it erases anything. See [WalletSessionStop].
    private val runningSessions = java.util.concurrent.CopyOnWriteArraySet<WalletSessionStop>()

    private val _walletState = MutableStateFlow<WalletState>(WalletState.NoWallet)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** The process-wide "reached Synced this session" latch every sync-status screen derives
     *  through, so the main card and Network Info cannot disagree. Reset by [wipeWallet]. */
    val syncFrontierSession = SyncFrontierSession()

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
    /**
     * False only for a wallet made on this device by "create": its seed has never signed a Digi-ID
     * login anywhere, so it has no shared-key (m/0'/0/0) identity to recover and is never offered
     * one (4.1.2 row 16). A restored wallet, or one whose record predates the origin field, may.
     */
    fun mayHaveDigiIdHistoryElsewhere(): Boolean = prefs.getString(KEY_ORIGIN, null) != ORIGIN_CREATED

    fun hasSavedWallet(): Boolean = prefs.contains("encrypted_seed") || prefs.contains("encrypted_seed_v2")

    /**
     * Whether PIN setup creates the wallet from the words onboarding holds.
     *
     * Decided by the wallet STORED on this device, never by what is loaded in memory: a wallet a
     * wipe removed may still be the one this process last loaded, and the words the user has just
     * written down must then become the wallet. A stored wallet is never created a second time —
     * the restore path stores it before PIN setup, and a recomposition finds the one PIN setup
     * has just made.
     */
    fun pinSetupCreatesWallet(): Boolean = !hasSavedWallet()

    /** Register a session running for the wallet in this process; every wipe stops it first. */
    fun addSessionStop(stop: WalletSessionStop) {
        runningSessions += stop
    }

    /** The session has ended: later wipes leave it alone. */
    fun removeSessionStop(stop: WalletSessionStop) {
        runningSessions -= stop
    }

    /**
     * Create a new wallet from a mnemonic phrase, and store its record.
     *
     * The wallet exists once its record has landed, and not before: the record — seed, whether there
     * is a passphrase and its envelope, creation time, identity, version — is ONE checked write
     * ([writeSeedRecord]). A write that did not land answers false and leaves "wallet exists" false.
     *
     * @param passphrase NFKD UTF-8 bytes, or null. **The CALLER owns these and must zero them** —
     *   this does not, because wiping a buffer it was merely handed is the mistake jni_derive.c
     *   already had to be corrected for once.
     */
    fun createWallet(mnemonic: String, passphrase: ByteArray? = null): Boolean {
        // Default null keeps every existing caller — and every existing wallet — unchanged.
        if (passphrase != null && (passphrase.size > Bip39Passphrase.MAX_BYTES || Bip39Passphrase.hasNul(passphrase))) return false
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            // Persist creation time so restoreFromDisk uses the right sync checkpoint — in the record.
            val created = createNative(mnemonicBytes, passphrase) &&
                writeSeedRecord(mnemonicBytes, passphrase, System.currentTimeMillis() / 1000, ORIGIN_CREATED)
            if (created) {
                _walletState.value = WalletState.Unlocked
                clearSyncData()
                rescanNative()
            }
            return created
        } finally {
            mnemonicBytes.fill(0)
        }
    }

    /**
     * Recover wallet from mnemonic and creation timestamp, and store its record — one checked write,
     * as [createWallet]; a write that did not land answers false and leaves "wallet exists" false.
     */
    fun recoverWallet(
        mnemonic: String,
        creationTimestamp: Long,
        /** NFKD UTF-8 bytes, or null. Caller owns and zeroes them; see [createWallet]. */
        passphrase: ByteArray? = null,
    ): Boolean {
        if (passphrase != null && (passphrase.size > Bip39Passphrase.MAX_BYTES || Bip39Passphrase.hasNul(passphrase))) return false
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        try {
            // The creation time is part of the record. Without it restoreFromDisk feeds native the
            // hardcoded 2026 fallback (DEFAULT_RESTORE_CREATION_TIME) → getWalletBirthCheckpointHeight
            // returns the ~2026 checkpoint (~block 21.5M) for EVERY recovered wallet → an older
            // wallet's scan floors ~20M blocks ABOVE its funds and shows empty. Use the user's chosen
            // recovery timestamp; if unknown (0), match native's own time(NULL) default so a restart
            // stays consistent with the first sync.
            val creationTimeSecs =
                if (creationTimestamp > 0L) creationTimestamp else System.currentTimeMillis() / 1000
            val recovered = recoverNative(mnemonicBytes, creationTimestamp, passphrase) &&
                writeSeedRecord(mnemonicBytes, passphrase, creationTimeSecs, ORIGIN_RESTORED)
            if (recovered) {
                _walletState.value = WalletState.Unlocked
                clearSyncData()
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
                val birth = runCatching { birthHeightNative() }.getOrDefault(0L)
                val toPersist = cfBirthHeightToPersist(birth)
                context.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE).edit().apply {
                    if (toPersist != null) putLong("cf_birth_height", toPersist) else remove("cf_birth_height")
                }.commit()
                android.util.Log.i("WalletManager", "recoverWallet: persisted cf_birth_height=$toPersist (raw=$birth)")
                rescanNative()
            }
            return recovered
        } finally {
            mnemonicBytes.fill(0)
        }
    }

    /**
     * Restore wallet from persisted encrypted seed on app restart.
     * Stops any running sync first to avoid use-after-free crashes.
     * Returns true if the wallet was successfully restored.
     *
     * @param beforeNative run at the start of the restore's risky native window. The app arms its
     *   restore-crash bracket here (BootGuard.beginRestore), so the bracket covers exactly the work
     *   it is there to watch.
     */
    fun restoreFromDisk(beforeNative: () -> Unit = {}): Boolean {
        val seedBytes = loadSeed() ?: return false
        // The stored passphrase MUST come along. This is the unlock path — it runs on every
        // restart, on resume, and from BootGuard. Rebuilding a passphrase wallet without it
        // derives a different seed, so the wallet would open with different addresses and
        // report a zero balance, on a wallet whose coins are perfectly safe. Null for the
        // overwhelming majority of wallets, which have no passphrase. Read before anything native:
        // a record that cannot say whether it has one is not opened at all (AND-005).
        val stored = storedPassphraseZeroingOnThrow(seedBytes)
        if (stored is StoredPassphrase.Unknown) {
            android.util.Log.w("WalletManager", "wallet record incomplete (${stored.why}); not opened")
            seedBytes.fill(0)
            return false
        }
        val storedPass = (stored as? StoredPassphrase.Present)?.bytes
        // Identity is the DERIVED seed, so a passphrase distinguishes wallets that share a
        // mnemonic. Derived here, before any native work that touches the wallet (the derivation is
        // a pure function): a record read as "no passphrase" whose own identity is a different
        // wallet's — an envelope an earlier release's key migration dropped — is not opened, and
        // nothing of it is overwritten (AND-005).
        val identitySeed = deriveSeedForIdentity(seedBytes, storedPass)
        if (storedPass == null && identitySeed != null && identityContradicts(identitySeed)) {
            android.util.Log.w("WalletManager", "wallet record contradicts its own identity; not opened")
            identitySeed.fill(0)
            seedBytes.fill(0)
            return false
        }
        try {
            // The restore's risky native window starts here, so the app's restore-crash bracket is
            // armed here — never for a restore that refused above: a refusal is not a crash.
            beforeNative()
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
            // v1 (mnemonic-based) is still honoured for installs that predate the derived
            // identity, so upgrading does not clear sync data and re-sync everyone from the floor.
            if (identitySeed == null) {
                // Derivation failed — do NOT guess. Clearing on a failed derivation would throw
                // away good sync data over a transient native error.
                // Worded without the trigger words the NetworkLeakTest gate scans for. That rule
                // is deliberately broad — a line that already mentions the secret is one edit away
                // from interpolating it — so it is respected rather than narrowed.
                android.util.Log.w("WalletManager", "wallet identity unavailable; leaving sync data intact")
            } else {
                val verdict = SeedFingerprint.evaluate(storedFingerprints(), seedBytes, identitySeed)
                if (verdict.seedChanged) clearSyncData()
                if (verdict.writeV2) {
                    prefs.edit().putString("seed_fingerprint_v2", verdict.v2ToWrite).apply()
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
            // Rebuild transaction history once after an update: a cache written by an earlier
            // build is discarded here, before it is read. Normally already decided at process
            // start (DigiByteApp.onCreate); this covers a network this process has not evaluated.
            // A throw leaves it undone, to be retried on the next launch.
            runCatching { HistoryRebuildOnUpgrade.beforeWalletLoad(context) }
                .onFailure { android.util.Log.w(HistoryRebuildOnUpgrade.TAG, "history rebuild check failed; retried next launch", it) }
            // A rebuild that started clearing and did not finish leaves a partial cache: do not load it.
            val savedTxHex = if (HistoryRebuildOnUpgrade.isInProgress(context)) {
                android.util.Log.w(HistoryRebuildOnUpgrade.TAG, "history rebuild in progress; not loading the transaction cache")
                null
            } else {
                syncPrefs.getString("saved_transactions", null)
            }
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

            // restoreWalletFromBytes builds the wallet from the records loaded just above (the only native
            // call that uses loaded records), with the original creation timestamp so the peer manager
            // starts syncing from the right checkpoint — not NOW.
            val creationTime = prefs.getLong(KEY_CREATION_TIME, 0L)
            // After a one-time history rebuild the anchor goes no later than the oldest record
            // the discarded cache held (creationTimeForRestore); otherwise unchanged.
            val restoreTime = creationTimeForRestore(creationTime, HistoryRebuildOnUpgrade.floorTime(context))
            val success = NativeBridge.restoreWalletFromBytes(seedBytes, restoreTime, storedPass)
            if (success) {
                _walletState.value = WalletState.Unlocked
            }
            return success
        } finally {
            identitySeed?.fill(0)
            storedPass?.fill(0)
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
                        "clearStuckSends: ${txid.take(12)} is an ORPHAN — parent missing from the wallet, " +
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
        // Arm the one-time rebuild runner BEFORE clearing (done unset, in progress, floors kept).
        // A coalesced writer can still put a cache back between the clear below and the
        // process exit; the runner then clears it again at the next start before anything
        // loads it, and only then stamps done.
        if (!HistoryRebuildOnUpgrade.armRerun(context)) {
            runCatching { android.util.Log.w(HistoryRebuildOnUpgrade.TAG, "manual rebuild: arming the rerun did not land") }
        }
        // NOTE: synchronous (commit), NOT apply(). The caller kills the process
        // (Runtime.exit) immediately after this returns to force a clean reload; an
        // async apply() would be dropped before it flushes, leaving the corrupt cache
        // in place (the tx graph would reload unchanged and the scan would stay at the
        // tip instead of the birth floor). The clear is shared with the one-time
        // history rebuild after an update, so the two cannot drift; see
        // clearTransactionCacheForRebuild for what it removes and why.
        if (!clearTransactionCacheForRebuild(context)) {
            runCatching { android.util.Log.w(HistoryRebuildOnUpgrade.TAG, "manual rebuild: the clear did not fully land; the next start repeats it") }
        }
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
     * leave transaction history behind. Both paths call this single routine, and one
     * run covers BOTH networks' stores and the identity sessions ([IdentitySessionWipe]).
     *
     * Order holds across a process death: the seed ciphertext is cleared FIRST, and its key once
     * that removal is CONFIRMED — so a wipe cut short leaves hasSavedWallet()=false (no
     * half-loadable wallet) and nothing that could decrypt a record that did survive, while a
     * record that is still on the device keeps the key that opens it and stays the wallet its
     * PIN opened before this run. A wipe that could not finish is always one that can be run
     * again, from a device its owner can still open.
     *
     * Nothing of the wiped wallet keeps running once the erase begins: every session running for
     * it in this process ([WalletSessionStop] — the sync service) is stopped first, then the
     * native session is quiesced (sync stopped, seed zeroed), so nothing writes the wiped stores
     * back after they are erased. The native wallet itself stays loaded — bridge readers rely on it
     * not being taken away under them — and once the seed is gone the app ends this process and
     * starts a fresh one instead, so the next wallet created or restored is made in a process that
     * never loaded the wiped one.
     *
     * Every step is attempted whatever happened to the ones before it — a native session
     * that cannot be stopped, or a store that refuses its write, never keeps the seed on the
     * device. What the run established comes back as a [WipeReport]; entry points do not call
     * this directly but go through [wipeThenReleasePin], which decides about the PIN from it.
     */
    suspend fun wipeWallet(): WipeReport {
        fun attempt(what: String, block: () -> Boolean): Boolean = try {
            block()
        } catch (t: Throwable) {
            android.util.Log.e("WalletManager", "wipe: $what did not complete (${t.javaClass.simpleName})")
            false
        }

        // Stop what runs the wallet's session in this process, so nothing it holds is written
        // after the erase below. Each one is asked, whatever the one before it did.
        val sessionsStopped = runningSessions.toList()
            .map { session -> attempt("running session") { session.stopForWipe(); true } }
            .all { it }
        // Stop sync and disconnect peers before destroying wallet.
        val quiesced = attempt("native quiesce") { quiesceNative(); true }
        // Seed ciphertext FIRST (the ordering invariant above). The record is what holds the
        // seed; the key only unwraps it. So the record's removal is CONFIRMED first — the write
        // landed (the in-memory view of a preferences file shows an edit whether or not it
        // reached the disk, so the write result is part of the evidence) and nothing is readable
        // any more — and only then does the key go.
        val seedErased = attempt("wallet record") { dataEraser.eraseSeedCiphertext() }
        val recordGone = seedErased && attempt("wallet record read-back") { !hasSavedWallet() }
        if (recordGone) {
            attempt("wallet key") { keyStoreManager.deleteKey(); true } // judged by the read-back below
        }
        // Empty the UTXO table through the still-open handle BEFORE the database files are erased:
        // a write after the erase can create a file by name (a rollback journal) that the eraser's
        // read-back has already passed. Tidiness for this process only, not part of the report.
        try {
            utxoManager.clearAll()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("WalletManager", "wipe: utxo cache not emptied (${t.javaClass.simpleName})")
        }
        // Regenerable + privacy-sensitive persisted data, both networks.
        val storesCleared = listOf(
            attempt("sync data") { dataEraser.eraseSyncData() },
            attempt("peer cache") { dataEraser.eraseBloomPeerCache() },
            attempt("watched addresses") { dataEraser.eraseWatchedAddresses() },
            attempt("recorded sends") { dataEraser.eraseOutgoingTx() },
            attempt("compact-filter state") { dataEraser.eraseCfSyncState() },
            attempt("database") { dataEraser.eraseDatabase() },
            attempt("leftover state") { dataEraser.eraseLeftoverState() },
            attempt("hub session") { dataEraser.eraseHubSession() },
            attempt("identity sessions") { identitySessions.eraseIdentitySessions() },
        ).all { it }
        // READ-BACK, not "no step threw": the record's removal is confirmed (above) and neither
        // seed key alias exists when the Keystore is asked again.
        val seedGone = recordGone && attempt("wallet key read-back") { dataEraser.seedKeyAbsent() }

        // The state says what is on the device: no wallet only when the seed is gone. Otherwise
        // the wallet still exists behind its PIN and its session has been stopped — Locked.
        _walletState.value = if (seedGone) WalletState.NoWallet else WalletState.Locked
        _syncState.value = SyncState.Idle
        syncFrontierSession.reset()
        val report = WipeReport(
            seedGone = seedGone,
            everythingCleared = sessionsStopped && quiesced && storesCleared,
        )
        return report
    }

    /**
     * The ONE sequence every wipe entry point runs (Settings, the unlock screen, the spend
     * dialog, the launch backstop): wipe, look at what the wipe established, and only then
     * touch the PIN store. `WipeCallSiteGateTest` keeps the entry points on it.
     *
     *  - The PIN store — hash, attempt counters, wipe-after-N setting — is cleared only after
     *    a [WipeReport.verified] wipe. While a wallet may still be on the device, the PIN that
     *    guards it stays, so the app never offers to set a new PIN over a wallet that was not
     *    wiped.
     *  - The owed-wipe flag is released as soon as the seed is verifiably gone, and not
     *    before: until then the launch backstop and the unlock screen keep retrying the wipe;
     *    from then on there is nothing left for a retry to protect, and a wallet created
     *    afterwards must never inherit a wipe that was owed to its predecessor.
     *
     * Runs to its end once started (NonCancellable): a wipe flips the wallet state, which pops
     * the screen — and the scope — that started it. The caller picks the thread.
     *
     * @return what the wipe established. Only a [WipeReport.verified] report means the wallet was
     *   wiped. [WipeReport.seedGone] is the read-back of the seed alone — its record and its key —
     *   and the app acts on it too: once the seed is gone it ends the process that held the wallet,
     *   whether or not every other store confirmed. A wipe that did not run to its end reports
     *   nothing gone.
     */
    suspend fun wipeThenReleasePin(pinManager: app.aroundtheblock.wallet.core.security.PinManager): WipeReport =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { markErasureOwed() }.onFailure {
                android.util.Log.e("WalletManager", "erasure not marked owed before the wipe (${it.javaClass.simpleName})")
            }
            val report = try {
                wipeWallet()
            } catch (t: Throwable) {
                android.util.Log.e("WalletManager", "wipe did not run to its end (${t.javaClass.simpleName})")
                WipeReport(seedGone = false, everythingCleared = false)
            }
            if (report.seedGone) {
                runCatching { pinManager.clearWipePending() }.onFailure {
                    android.util.Log.e("WalletManager", "owed-wipe flag not released (${it.javaClass.simpleName})")
                }
            }
            if (report.verified) {
                runCatching { pinManager.clearPin() }.onFailure {
                    android.util.Log.e("WalletManager", "PIN store not cleared (${it.javaClass.simpleName})")
                }
            }
            report
        }

    /**
     * The way out of an owed wipe that will not complete.
     *
     * While a wipe is owed the unlock screen takes no credential and retries the wipe instead of
     * opening the wallet. That hold is bounded, because a store that will not take its write must
     * never add up to a device that can no longer be opened at all. After the bound the screen
     * asks this and then acts on the answer:
     *
     *  - **true** — no wallet record is on this device, so there is nothing left for the owed wipe
     *    to hold the screen for. The flag is released (and only the flag: the PIN store is
     *    untouched, as after any wipe that could not be verified) so onboarding can be offered and
     *    a wallet made from here on does not inherit a wipe owed to its predecessor.
     *  - **false** — a wallet record is still on this device, so the hold has nothing left to
     *    protect and the screen stands down and takes the PIN, which opens that wallet: a refused
     *    write leaves this process reading what the store's file still holds (see
     *    [AndroidWalletDataEraser]), so a load from the record finds it. The screen still acts on
     *    whether the wallet actually loaded, rather than showing a wallet with nothing in it.
     *    Either way the wipe stays owed: the launch backstop and each later entry to the unlock
     *    screen retry it.
     *
     * The evidence is the store's own answer to the wipe's first step, asked once more, and not
     * the record read-back on its own. So only a store that TAKES the removal counts as a device
     * with no wallet left, and a store that still refuses its write keeps its owner on the unlock
     * screen — never at onboarding over a wallet that is still there.
     *
     * Runs to its end once started (NonCancellable). The caller picks the thread. A PIN store that
     * refuses the release is reported and the way out is still taken: being able to reach the app
     * at all comes first.
     */
    suspend fun releaseOwedWipeIfNoWalletIsLeft(
        pinManager: app.aroundtheblock.wallet.core.security.PinManager,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val recordGone = try {
            dataEraser.eraseSeedCiphertext() && !hasSavedWallet()
        } catch (t: Throwable) {
            android.util.Log.e("WalletManager", "wallet record not removable (${t.javaClass.simpleName})")
            false // unknown is "still here": the screen stands down and the PIN decides
        }
        if (!recordGone) return@withContext false
        // The flag goes, but the secrets the unverified wipes left (Hub token, DB key) are still owed
        // their erasure: the next launch finishes it (4.1.2 row 33).
        runCatching { markErasureOwed() }.onFailure {
            android.util.Log.e("WalletManager", "erasure not marked owed (${it.javaClass.simpleName})")
        }
        runCatching { pinManager.clearWipePending() }.onFailure {
            android.util.Log.e("WalletManager", "owed-wipe flag not released (${it.javaClass.simpleName})")
        }
        true
    }

    // ── Seed persistence ────────────────────────────────────────

    /**
     * A wallet record's secrets, sealed before anything is written, under one door: the auth-bound key
     * where the device allows it (docs/specs/keystore-auth-binding.md), else the legacy unbound key.
     * Wallet creation never blocks on binding (the API 28 lesson from 256522c2): ANY failure on the
     * bound door — keygen refused, a lapsed auth window, a vendor quirk — falls back to the legacy one.
     */
    private class SealedRecord(val authBound: Boolean, val seed: EncryptedData, val passphrase: EncryptedData?)

    private fun sealRecord(mnemonicBytes: ByteArray, passphrase: ByteArray?): SealedRecord {
        if (keyStoreManager.createAuthBoundKey()) {
            try {
                return SealedRecord(
                    authBound = true,
                    seed = keyStoreManager.encryptAuthBound(mnemonicBytes),
                    passphrase = passphrase?.let { keyStoreManager.encryptAuthBound(it) },
                )
            } catch (e: Exception) {
                android.util.Log.w("WalletManager", "auth-bound blob write failed (${e::class.java.simpleName}) — using legacy key")
            }
        }
        keyStoreManager.createKey()
        return SealedRecord(
            authBound = false,
            seed = keyStoreManager.encrypt(mnemonicBytes),
            passphrase = passphrase?.let { keyStoreManager.encrypt(it) },
        )
    }

    /**
     * Writes a wallet's record — the seed, whether there is a passphrase and its envelope, the creation
     * time, the wallet's identity and the record's version — with ONE checked commit, every field
     * prepared first (AND-005). True only when the write landed and reads back as written. Otherwise
     * this process is put back to what the store held before, so "wallet exists" stays false for a
     * record that never reached the disk, in this process and the next.
     *
     * The passphrase is stored rather than prompted because [restoreFromDisk] runs from resume and
     * BootGuard paths with no UI attached; the cost is stated in the spec: a stored passphrase protects
     * the written BACKUP, not the device, because it sits behind the same door as the mnemonic. A null
     * or empty passphrase is "no passphrase" — BIP39 treats absent and empty identically (salt =
     * "mnemonic"), so the two must remain the same wallet.
     */
    private fun writeSeedRecord(mnemonicBytes: ByteArray, passphrase: ByteArray?, creationTimeSecs: Long, origin: String): Boolean {
        val pass = passphrase?.takeIf { it.isNotEmpty() }
        val sealed = try {
            sealRecord(mnemonicBytes, pass)
        } catch (e: Exception) {
            android.util.Log.e("WalletManager", "wallet record not sealed (${e::class.java.simpleName}); no wallet was made")
            return false
        }
        val identity = identityOf(mnemonicBytes, pass)
        val fields = if (sealed.authBound) V2_ENVELOPE_FIELDS else LEGACY_ENVELOPE_FIELDS
        val (seedCt, seedIv) = fields
        val (passCt, passIv) = fields.drop(2)
        val seedHex = bytesToHex(sealed.seed.ciphertext)
        val held = prefs.all
        val edit = prefs.edit()
        // A record is written whole: nothing of an earlier one survives it.
        SEED_RECORD_FIELDS.forEach { edit.remove(it) }
        edit.putString(seedCt, seedHex).putString(seedIv, bytesToHex(sealed.seed.iv))
        sealed.passphrase?.let { edit.putString(passCt, bytesToHex(it.ciphertext)).putString(passIv, bytesToHex(it.iv)) }
        identity?.let { edit.putString(KEY_FINGERPRINT_V2, it) }
        edit.putLong(KEY_CREATION_TIME, creationTimeSecs)
            .putBoolean(KEY_HAS_PASSPHRASE, sealed.passphrase != null)
            .putInt(KEY_RECORD_VERSION, SEED_RECORD_VERSION)
            .putString(KEY_ORIGIN, origin)
        val landed = edit.commit()
        val readsBack = landed &&
            prefs.getInt(KEY_RECORD_VERSION, 0) == SEED_RECORD_VERSION &&
            prefs.getString(seedCt, null) == seedHex &&
            prefs.contains(KEY_HAS_PASSPHRASE) && prefs.getBoolean(KEY_HAS_PASSPHRASE, false) == (sealed.passphrase != null) &&
            prefs.getLong(KEY_CREATION_TIME, -1L) == creationTimeSecs
        if (readsBack) return true
        android.util.Log.e("WalletManager", "wallet record not stored (landed=$landed); no wallet was made")
        putBack(held)
        return false
    }

    /**
     * After a write to the record store that did not land or did not read back: put [held] — what the
     * store showed before — back into this process's view, and onto disk if that write lands. The file
     * never took the refused write, so this process then reads what the file holds.
     */
    private fun putBack(held: Map<String, *>) {
        val editor = prefs.edit().clear()
        for ((key, value) in held) {
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (!editor.commit()) android.util.Log.w("WalletManager", "wallet store: a refused write was put back in this process only")
    }

    /** True when the seed blob lives under the auth-bound key. */
    private fun seedIsAuthBound(): Boolean = prefs.contains("encrypted_seed_v2")

    /**
     * Re-wrap an existing wallet's blobs onto the auth-bound key. Runs on the
     * foreground legacy-decrypt path (loadSeed), where the mnemonic is in hand
     * anyway. Verified before destructive: the v2 blob must round-trip decrypt
     * equal BEFORE the legacy blob and key are removed. Every failure aborts
     * quietly and is retried on a later unlock.
     *
     * The legacy key also wrapped the database password's record on installs from before that record
     * had a key of its own (PER-001): the record is moved onto its own key, read back and verified
     * ([releaseDatabaseKey]) before the legacy key is deleted, and while that has not happened the key
     * stays. A passphrase envelope that is there but cannot be read stops the migration, keeping the
     * envelope and the key: dropping it would turn the wallet into a different one.
     */
    private fun migrateSeedKeyIfNeeded(mnemonicBytes: ByteArray) {
        if (seedIsAuthBound()) return
        try {
            if (!keyStoreManager.createAuthBoundKey()) return
            val enc = keyStoreManager.encryptAuthBound(mnemonicBytes)
            val check = keyStoreManager.decryptAuthBound(enc)
            val ok = check.contentEquals(mnemonicBytes)
            check.fill(0)
            if (!ok) {
                android.util.Log.e("WalletManager", "key-wrap migration verify failed — keeping legacy key")
                return
            }
            // Passphrase blob moves through the same door as the mnemonic.
            var passCt: String? = null; var passIv: String? = null
            val legacyEnvelope = envelopeAt("encrypted_pass", "encrypted_pass_iv", authBound = false)
            if (legacyEnvelope != null) {
                val pass = try {
                    keyStoreManager.decrypt(legacyEnvelope.data)
                } catch (e: Exception) {
                    android.util.Log.w("WalletManager", "key-wrap migration deferred: an envelope did not read (${e::class.java.simpleName})")
                    return
                }
                try {
                    if (pass.isNotEmpty()) {
                        val pEnc = keyStoreManager.encryptAuthBound(pass)
                        val pCheck = keyStoreManager.decryptAuthBound(pEnc)
                        val pOk = pCheck.contentEquals(pass)
                        pCheck.fill(0)
                        if (!pOk) return
                        passCt = bytesToHex(pEnc.ciphertext); passIv = bytesToHex(pEnc.iv)
                    }
                } finally {
                    pass.fill(0)
                }
            }
            val edit = prefs.edit()
                .putString("encrypted_seed_v2", bytesToHex(enc.ciphertext))
                .putString("encrypted_seed_iv_v2", bytesToHex(enc.iv))
            if (passCt != null) edit.putString("encrypted_pass_v2", passCt).putString("encrypted_pass_iv_v2", passIv)
            // commit(): the v2 blob must be ON DISK before anything legacy is removed.
            if (!edit.commit()) return
            prefs.edit()
                .remove("encrypted_seed").remove("encrypted_seed_iv")
                .remove("encrypted_pass").remove("encrypted_pass_iv")
                .commit()
            // The database password's record, re-wrapped under its own key and verified, BEFORE the key
            // that wrapped it is deleted. If that has not happened the key stays, and it is not asked
            // for again: the seed has moved, so this migration does not run again. The record still
            // moves at the next start (WalletDatabaseOpener); the legacy key then wraps nothing and
            // stays until a wipe.
            if (!releaseDatabaseKey()) {
                android.util.Log.w("WalletManager", "legacy key kept: the database key record still depends on it")
                return
            }
            keyStoreManager.deleteLegacyKey()
            android.util.Log.i("WalletManager", "wallet blob re-wrapped onto auth-bound key")
        } catch (e: Exception) {
            // Includes KeystoreUserAuthRequiredException (window lapsed mid-migration):
            // quiet abort, wallet stays on the legacy key until a later unlock.
            android.util.Log.w("WalletManager", "key-wrap migration deferred (${e::class.java.simpleName})")
        }
    }

    /** What the stored record says about the wallet's optional BIP39 passphrase. */
    private sealed class StoredPassphrase {
        data object None : StoredPassphrase()

        /** NFKD UTF-8 bytes. **The caller owns them and must `fill(0)` them.** */
        class Present(val bytes: ByteArray) : StoredPassphrase()

        /** The record cannot say: an error, never "no passphrase" — that would be a different wallet. */
        class Unknown(val why: String) : StoredPassphrase()
    }

    /** A passphrase envelope as stored, under the auth-bound key ([authBound]) or the legacy one. */
    private class Envelope(val data: EncryptedData, val authBound: Boolean)

    /** The envelope stored under [ctKey]/[ivKey], or null when there is none. Throws when it is malformed. */
    private fun envelopeAt(ctKey: String, ivKey: String, authBound: Boolean): Envelope? {
        val ct = prefs.getString(ctKey, null)
        val iv = prefs.getString(ivKey, null)
        if (ct == null && iv == null) return null
        require(ct != null && iv != null) { "half an envelope" }
        return Envelope(EncryptedData(hexToBytes(ct), hexToBytes(iv)), authBound)
    }

    /**
     * What the record says about the passphrase (AND-005).
     *
     * A record written by this release carries a version marker and says whether the wallet has a
     * passphrase; its envelope must then be there and read, and none must be there when it says none.
     * A record with no version marker comes from an earlier release. Its envelope is read if it holds
     * one. Without one it is "no passphrase" only where the record itself shows its passphrase write
     * landed: every earlier release wrote the seed, then the passphrase (or its removal), then the
     * creation time, each write a full snapshot of the store — so a creation time on disk means the
     * passphrase write before it landed too; and a mnemonic-based fingerprint (v1) was only ever written
     * by releases that had no passphrases. Anything else is [StoredPassphrase.Unknown].
     *
     * @throws KeystoreUserAuthRequiredException / KeystoreKeyInvalidatedException as [loadSeed].
     */
    private fun storedPassphrase(): StoredPassphrase {
        val envelope = try {
            envelopeAt("encrypted_pass_v2", "encrypted_pass_iv_v2", authBound = true)
                ?: envelopeAt("encrypted_pass", "encrypted_pass_iv", authBound = false)
        } catch (e: Exception) {
            return StoredPassphrase.Unknown("envelope malformed")
        }
        if (prefs.getInt(KEY_RECORD_VERSION, 0) >= 1) {
            if (!prefs.contains(KEY_HAS_PASSPHRASE)) return StoredPassphrase.Unknown("versioned record without its flag")
            val says = prefs.getBoolean(KEY_HAS_PASSPHRASE, false)
            return when {
                says && envelope == null -> StoredPassphrase.Unknown("record says it has one and holds none")
                !says && envelope != null -> StoredPassphrase.Unknown("record says it has none and holds one")
                envelope == null -> StoredPassphrase.None
                else -> openEnvelope(envelope).let {
                    if (it === StoredPassphrase.None) StoredPassphrase.Unknown("record says it has one and holds an empty one") else it
                }
            }
        }
        if (envelope != null) return openEnvelope(envelope)
        if (prefs.contains(KEY_CREATION_TIME) || prefs.contains(KEY_FINGERPRINT_V1)) return StoredPassphrase.None
        return StoredPassphrase.Unknown("record from an earlier release does not show its write completed")
    }

    /** [storedPassphrase] for [restoreFromDisk], which holds [seedBytes]: zeroed when the read throws. */
    private fun storedPassphraseZeroingOnThrow(seedBytes: ByteArray): StoredPassphrase = try {
        storedPassphrase()
    } catch (t: Throwable) {
        seedBytes.fill(0)
        throw t
    }

    private fun openEnvelope(envelope: Envelope): StoredPassphrase {
        val bytes = try {
            if (envelope.authBound) keyStoreManager.decryptAuthBound(envelope.data) else keyStoreManager.decrypt(envelope.data)
        } catch (e: KeystoreUserAuthRequiredException) {
            throw e
        } catch (e: KeystoreKeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            return StoredPassphrase.Unknown("envelope did not read (${e::class.java.simpleName})")
        }
        if (bytes.isEmpty()) return StoredPassphrase.None
        return StoredPassphrase.Present(bytes)
    }

    /**
     * @throws KeystoreUserAuthRequiredException when the auth-bound key needs a fresh
     *   device unlock — foreground callers prompt (DEVICE_CREDENTIAL|BIOMETRIC_STRONG)
     *   and retry; swallowing it to null made the case indistinguishable from corruption.
     * @throws KeystoreKeyInvalidatedException when the device lock screen was removed —
     *   only the written recovery phrase recovers the wallet, and the UI says exactly that.
     */
    private fun loadSeed(): ByteArray? {
        val ctV2 = prefs.getString("encrypted_seed_v2", null)
        val ivV2 = prefs.getString("encrypted_seed_iv_v2", null)
        if (ctV2 != null && ivV2 != null) {
            return try {
                keyStoreManager.decryptAuthBound(EncryptedData(hexToBytes(ctV2), hexToBytes(ivV2)))
            } catch (e: KeystoreUserAuthRequiredException) {
                throw e
            } catch (e: KeystoreKeyInvalidatedException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val ciphertextHex = prefs.getString("encrypted_seed", null) ?: return null
        val ivHex = prefs.getString("encrypted_seed_iv", null) ?: return null
        return try {
            val encrypted = EncryptedData(
                ciphertext = hexToBytes(ciphertextHex),
                iv = hexToBytes(ivHex)
            )
            keyStoreManager.decrypt(encrypted)?.also { migrateSeedKeyIfNeeded(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** SeedViewScreen's decrypt path — v2-aware, replacing its raw prefs reads.
     *  Caller owns the bytes and must fill(0). Throws like [loadSeed]. */
    fun decryptStoredMnemonic(): ByteArray? = loadSeed()

    /**
     * The stored passphrase as NFKD UTF-8 bytes, or null when this wallet has none. **The caller owns
     * the result and must `fill(0)` it.** Throws like [loadSeed], and [IllegalStateException] when the
     * record cannot say whether the wallet has one — that is an error, never "none".
     */
    fun decryptStoredPassphrase(): ByteArray? = when (val stored = storedPassphrase()) {
        is StoredPassphrase.Present -> stored.bytes
        StoredPassphrase.None -> if (noPassphraseContradicted()) error("wallet record incomplete: its identity is another wallet's") else null
        is StoredPassphrase.Unknown -> error("wallet record incomplete: ${stored.why}")
    }

    /** The record's own identity (fingerprint v2) is not the wallet [identitySeed] opens. */
    private fun identityContradicts(identitySeed: ByteArray): Boolean =
        prefs.getString(KEY_FINGERPRINT_V2, null)?.let { it != SeedFingerprint.v2(identitySeed) } ?: false

    /** The record reads "no passphrase", and its own identity is not the wallet that opens with none. */
    private fun noPassphraseContradicted(): Boolean {
        if (!prefs.contains(KEY_FINGERPRINT_V2)) return false
        val mnemonicBytes = loadSeed() ?: return false
        return try {
            deriveSeedForIdentity(mnemonicBytes, null)?.let { derived ->
                try {
                    identityContradicts(derived)
                } finally {
                    derived.fill(0)
                }
            } ?: false
        } finally {
            mnemonicBytes.fill(0)
        }
    }

    /**
     * Load the wallet's **64-byte BIP39 seed** for re-runnable recovery flows
     * (classify / sweep). The on-disk secret is the BIP39 *mnemonic* (see
     * [writeSeedRecord]); this decrypts it via the existing [loadSeed] path and
     * converts it once via [NativeBridge.mnemonicToSeed], zeroing the decrypted
     * mnemonic bytes before returning.
     *
     * CRITICAL-3: the returned array is the caller's responsibility to
     * `fill(0)` when done.
     *
     * @return the 64-byte BIP39 seed, or null if no wallet exists, decrypt
     *   failed, the record cannot say whether it has a passphrase, or seed
     *   derivation failed.
     */
    fun loadBip39Seed(): ByteArray? {
        val mnemonicBytes = loadSeed() ?: return null
        return try {
            // Carries the wallet's stored passphrase, if it has one — a passphrase wallet's
            // recovery/sweep seed must match the seed it actually derives from.
            val stored = storedPassphrase()
            val pass = when (stored) {
                is StoredPassphrase.Unknown -> {
                    android.util.Log.w("WalletManager", "wallet record incomplete (${stored.why}); recovery key not derived")
                    return null
                }
                is StoredPassphrase.Present -> stored.bytes
                StoredPassphrase.None -> null
            }
            val seed = try {
                seedNative(mnemonicBytes, pass)
            } finally {
                pass?.fill(0)
            }
            when {
                seed == null || seed.isEmpty() -> null
                // The record reads "no passphrase" but its own identity is another wallet's.
                stored === StoredPassphrase.None && identityContradicts(seed) -> {
                    seed.fill(0)
                    android.util.Log.w("WalletManager", "wallet record contradicts its own identity; recovery key not derived")
                    null
                }
                else -> seed
            }
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
        // commit(), not apply(): the one-time history rebuild is stamped not needed below, and
        // that stamp must not reach disk before the clear it relies on — nor at all if the
        // clear did not land.
        val cleared = context.getSharedPreferences("dgb_sync_data" + networkSuffix(context), Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Drop ChainTipStore's in-memory mirror too — see the note in
        // AndroidWalletDataEraser.eraseSyncData. This helper backs createWallet and recoverWallet,
        // so without it a restored seed would inherit the previous wallet's confirmation counts
        // until the process happened to restart.
        app.aroundtheblock.wallet.core.sync.ChainTipStore.invalidateCache()
        // I2 review (MINOR): the saved-blocks window moved out of dgb_sync_data
        // into a file (SavedBlockStore) — the .clear() above no longer reaches it.
        // Without this, a fresh wallet (createWallet/recoverWallet) or a
        // seed-fingerprint-mismatch restore (restoreFromDisk) would inherit the
        // PREVIOUS wallet's saved-blocks window from disk.
        SavedBlockStore.delete(context)
        // A new wallet (create, recover, seed change): every record cached from here on is
        // written by this build, so the one-time rebuild after an update has nothing to do. The
        // floor a previous wallet's rebuild recorded does not belong to this one.
        if (cleared) {
            HistoryRebuildOnUpgrade.markNotNeeded(context, forgetFloor = true)
        } else {
            runCatching { android.util.Log.w(HistoryRebuildOnUpgrade.TAG, "sync data clear did not land; the one-time rebuild stays armed") }
        }
    }

    /**
     * The wallet's identity, so restarts can tell whether the seed changed: [SeedFingerprint.v2] of the
     * DERIVED seed, not the mnemonic — a BIP39 passphrase lets one mnemonic open many wallets, which
     * would all share a mnemonic-based fingerprint and silently inherit each other's sync data. See
     * [SeedFingerprint] for why it is versioned rather than replaced. Part of the record
     * ([writeSeedRecord]); null when the seed cannot be derived.
     */
    private fun identityOf(mnemonicBytes: ByteArray, passphrase: ByteArray?): String? {
        val derived = deriveSeedForIdentity(mnemonicBytes, passphrase) ?: return null
        return try {
            SeedFingerprint.v2(derived)
        } finally {
            derived.fill(0)
        }
    }

    private fun storedFingerprints() = SeedFingerprint.Stored(
        v1 = prefs.getString(KEY_FINGERPRINT_V1, null),
        v2 = prefs.getString(KEY_FINGERPRINT_V2, null),
    )

    /**
     * The 64-byte seed for a mnemonic under [passphrase] (usually none). Caller must zero the result.
     */
    private fun deriveSeedForIdentity(mnemonicBytes: ByteArray, passphrase: ByteArray?): ByteArray? = try {
        seedNative(mnemonicBytes, passphrase)?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
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
