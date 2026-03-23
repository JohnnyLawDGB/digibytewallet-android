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
            _walletState.value = WalletState.Unlocked
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
            NativeBridge.rescan()
        }
        return success
    }

    /**
     * Restore wallet from persisted encrypted seed on app restart.
     * Returns true if the wallet was successfully restored.
     */
    fun restoreFromDisk(): Boolean {
        val seed = loadSeed() ?: return false
        val success = NativeBridge.createWallet(seed)
        if (success) {
            _walletState.value = WalletState.Unlocked
        }
        // Zero the seed string from memory
        return success
    }

    /**
     * Unlock the wallet session (after biometric/PIN auth).
     * On app restart, this restores the C core wallet from disk.
     */
    fun unlock(authToken: ByteArray): Boolean {
        // If C core wallet isn't initialized, restore from disk
        if (NativeBridge.getBalance() == 0L && hasSavedWallet()) {
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
        keyStoreManager.deleteKey()
        utxoManager.clearAll()
        prefs.edit().clear().apply()
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

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
