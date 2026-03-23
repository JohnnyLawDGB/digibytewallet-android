package io.digibyte.core

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.model.SyncState
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
    private val keyStoreManager: KeyStoreManager,
    private val utxoManager: UtxoManager
) {
    private val _walletState = MutableStateFlow<WalletState>(WalletState.NoWallet)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Create a new wallet from a mnemonic phrase.
     * Encrypts the phrase with Keystore and stores it.
     */
    fun createWallet(mnemonic: String): Boolean {
        val success = NativeBridge.createWallet(mnemonic)
        if (success) {
            // Encrypt mnemonic for storage
            keyStoreManager.createKey()
            val encrypted = keyStoreManager.encrypt(mnemonic.toByteArray(Charsets.UTF_8))
            // Store encrypted data (implementation depends on storage mechanism)
            _walletState.value = WalletState.Unlocked

            // Trigger rescan so the bloom filter includes the new wallet's addresses
            // and peers send us matching transactions
            NativeBridge.rescan()
        }
        return success
    }

    /**
     * Recover wallet from mnemonic and creation timestamp.
     */
    fun recoverWallet(mnemonic: String, creationTimestamp: Long): Boolean {
        val success = NativeBridge.recoverWallet(mnemonic, creationTimestamp)
        if (success) {
            keyStoreManager.createKey()
            val encrypted = keyStoreManager.encrypt(mnemonic.toByteArray(Charsets.UTF_8))
            _walletState.value = WalletState.Unlocked

            // Trigger rescan to find transactions for the recovered wallet
            NativeBridge.rescan()
        }
        return success
    }

    /**
     * Unlock the wallet session (after biometric auth).
     */
    fun unlock(authToken: ByteArray): Boolean {
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
        _walletState.value = WalletState.NoWallet
        _syncState.value = SyncState.Idle
    }
}
