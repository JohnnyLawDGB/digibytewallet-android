package io.digibyte.core

import android.content.Context
import android.content.SharedPreferences
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.model.SyncState
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

class WalletManager(
    private val context: Context,
    private val keyStoreManager: KeyStoreManager,
    private val utxoManager: UtxoManager
) {
    private val _walletState = MutableStateFlow<WalletState>(WalletState.NoWallet)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dgb_wallet_seed", Context.MODE_PRIVATE)

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
            val migrationPrefs = context.getSharedPreferences("dgb_sync_data", android.content.Context.MODE_PRIVATE)
            if (!migrationPrefs.getBoolean("bip84_migrated", false)) {
                android.util.Log.i("WalletManager", "BIP84 upgrade detected — marking migration (no rescan)")
                migrationPrefs.edit()
                    .putBoolean("bip84_migrated", true)
                    .apply()
            }

            // Load saved transactions BEFORE creating wallet — recoverWallet uses them
            // so the wallet starts with full tx history and balance is immediately spendable.
            val syncPrefs = context.getSharedPreferences("dgb_sync_data", android.content.Context.MODE_PRIVATE)
            val savedTxHex = syncPrefs.getString("saved_transactions", null)
            if (savedTxHex != null) {
                val txBytes = hexToBytes(savedTxHex)
                val loaded = NativeBridge.loadSerializedTransactions(txBytes)
                android.util.Log.i("WalletManager", "Loaded $loaded saved transactions for restore")
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
        return NativeBridge.getReceiveAddress(index, format)
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
     * Wipe the wallet — delete everything.
     */
    suspend fun wipeWallet() {
        // Stop sync and disconnect peers before destroying wallet
        NativeBridge.stopSync()
        NativeBridge.lockSession()
        // Clear seed ciphertext FIRST — if process dies after this but before
        // key deletion, hasSavedWallet()=false so no orphaned state.
        prefs.edit().clear().commit()
        clearSyncData()
        // Clear saved transactions so they don't reappear on next wallet
        context.getSharedPreferences("dgb_sync_data", android.content.Context.MODE_PRIVATE)
            .edit().remove("saved_transactions").remove("has_synced").commit()
        // Clear bloom peer cache
        context.getSharedPreferences("dgb_bloom_peers", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
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

    // ── Sync data management ────────────────────────────────────

    private fun clearSyncData() {
        context.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE)
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
