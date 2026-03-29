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
        val success = NativeBridge.createWallet(mnemonic)
        if (success) {
            persistSeed(mnemonic)
            // Persist creation time so restoreFromDisk uses the right sync checkpoint
            prefs.edit().putLong("wallet_creation_time", System.currentTimeMillis() / 1000).apply()
            _walletState.value = WalletState.Unlocked
            clearSyncData()
            saveSeedFingerprint(mnemonic)
            NativeBridge.rescan()
        }
        return success
    }

    /**
     * Recover wallet from mnemonic and creation timestamp.
     * Encrypts and persists the phrase to disk.
     */
    fun recoverWallet(mnemonic: String, creationTimestamp: Long): Boolean {
        val success = NativeBridge.recoverWallet(mnemonic, creationTimestamp)
        if (success) {
            persistSeed(mnemonic)
            _walletState.value = WalletState.Unlocked
            clearSyncData()
            saveSeedFingerprint(mnemonic)
            NativeBridge.rescan()
        }
        return success
    }

    /**
     * Restore wallet from persisted encrypted seed on app restart.
     * Stops any running sync first to avoid use-after-free crashes.
     * Returns true if the wallet was successfully restored.
     */
    fun restoreFromDisk(): Boolean {
        val seed = loadSeed() ?: return false

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
        if (!seedFingerprintMatches(seed)) {
            clearSyncData()
            saveSeedFingerprint(seed)
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

        // Use recoverWallet with the original creation timestamp so the
        // peer manager starts syncing from the right checkpoint — not NOW.
        val creationTime = prefs.getLong("wallet_creation_time", 0L)
        val success = if (creationTime > 0) {
            NativeBridge.recoverWallet(seed, creationTime)
        } else {
            NativeBridge.recoverWallet(seed, 1774252800L)
        }
        if (success) {
            _walletState.value = WalletState.Unlocked
        }
        return success
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
        NativeBridge.lockSession()
        // Clear seed ciphertext FIRST — if process dies after this but before
        // key deletion, hasSavedWallet()=false so no orphaned state.
        // (MEDIUM-4: ordering prevents permanent funds loss on partial failure)
        prefs.edit().clear().commit()  // commit (sync) not apply (async) — must complete
        clearSyncData()
        utxoManager.clearAll()
        keyStoreManager.deleteKey()
        _walletState.value = WalletState.NoWallet
        _syncState.value = SyncState.Idle
    }

    // ── Seed persistence ────────────────────────────────────────

    private fun persistSeed(mnemonic: String) {
        keyStoreManager.createKey()
        val encrypted = keyStoreManager.encrypt(mnemonic.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("encrypted_seed", bytesToHex(encrypted.ciphertext))
            .putString("encrypted_seed_iv", bytesToHex(encrypted.iv))
            .apply()
    }

    private fun loadSeed(): String? {
        val ciphertextHex = prefs.getString("encrypted_seed", null) ?: return null
        val ivHex = prefs.getString("encrypted_seed_iv", null) ?: return null
        return try {
            val encrypted = EncryptedData(
                ciphertext = hexToBytes(ciphertextHex),
                iv = hexToBytes(ivHex)
            )
            val decrypted = keyStoreManager.decrypt(encrypted)
            String(decrypted, Charsets.UTF_8)
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
    private fun saveSeedFingerprint(mnemonic: String) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(mnemonic.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("seed_fingerprint", bytesToHex(hash)).apply()
    }

    private fun seedFingerprintMatches(mnemonic: String): Boolean {
        val saved = prefs.getString("seed_fingerprint", null) ?: return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = bytesToHex(digest.digest(mnemonic.toByteArray(Charsets.UTF_8)))
        return saved == hash
    }

    // ── Hex utilities ────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
