package app.aroundtheblock.wallet.core.wallets

import android.util.Log
import app.aroundtheblock.wallet.core.SeedFingerprint
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness

sealed class AddResult {
    data class Added(val entry: WalletEntry) : AddResult()
    data class Duplicate(val existing: WalletEntry) : AddResult()

    /** Never returned by [WalletProvisioner.add]; the caller maps [WalletProvisioner.prepareSeedKey] == null to it. */
    object CapReached : AddResult()
    object Failed : AddResult()
}

/**
 * Adds a wallet beside the ones the app holds. Writes only the new wallet's own seed record (and the
 * Keystore alias its seed key needs) through [WalletManager.storeRecordOnly][app.aroundtheblock.wallet.core.WalletManager.storeRecordOnly]
 * on a manager the [managerFactory] builds for the new scope. The running wallet's stores are never
 * touched, nothing native is created, and the new wallet is loaded only after the app restarts into it.
 *
 * Managers from [managerFactory] are used for record reads and [storeRecordOnly] only: their database
 * key release acts on the running wallet, so they are never unlocked, loaded or restored.
 */
class WalletProvisioner(
    private val registry: WalletRegistry,
    private val managerFactory: WalletManagerFactory,
    private val eraser: WalletStoreEraser,
    /** BIP39 mnemonic + passphrase to the 64-byte seed (NativeBridge.mnemonicToSeed); null when it cannot. */
    private val deriveSeed: (ByteArray, ByteArray?) -> ByteArray?,
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private sealed class Match {
        object None : Match()
        data class Of(val entry: WalletEntry) : Match()

        /** The phrase's identity could not be derived, so no answer is possible. */
        object Unknown : Match()
    }

    /**
     * The READY wallet on [testnet] this phrase + passphrase opens, or null (also when the seed
     * cannot be derived; [add] then refuses). Zeroes what it derives. Caller owns [passphrase].
     */
    fun duplicateOf(testnet: Boolean, mnemonic: String, passphrase: ByteArray?): WalletEntry? =
        (match(testnet, mnemonic, passphrase) as? Match.Of)?.entry

    /**
     * Reserves the new wallet's entry (PENDING, holding a slot toward the cap) and prepares its seed
     * key (KS-BIND) before [add], with the contract of `WalletManager.prepareSeedKeyForNewRecord`.
     * Null at the cap, with nothing written. The PENDING entry and the key it may have made are
     * erased by [add] when it does not complete, and by [WalletHousekeeping] at the next launch when
     * [add] is never called.
     */
    fun prepareSeedKey(testnet: Boolean, origin: WalletOrigin): Pair<WalletEntry, SeedKeyReadiness>? {
        val entry = registry.reserve(testnet, origin, nowSecs()) ?: return null
        val readiness = managerFactory.forScope(scopeOf(entry)).prepareSeedKeyForNewRecord()
        return entry to readiness
    }

    /**
     * Checks for a duplicate, writes the record for [entry] (reserved by [prepareSeedKey]) and
     * completes it as the active wallet of its network. On Duplicate or Failed it erases the scope
     * (the alias [prepareSeedKey] made included) and drops the entry.
     *
     * @param creationTimeSecs the wallet's birth: now for a create, the chosen recovery date for a restore.
     * @param passphrase NFKD UTF-8 bytes, or null. The CALLER owns and zeroes them.
     */
    fun add(entry: WalletEntry, mnemonic: String, passphrase: ByteArray?, creationTimeSecs: Long): AddResult {
        val testnet = entry.testnet ?: return AddResult.Failed
        val scope = scopeOf(entry)
        when (val m = match(testnet, mnemonic, passphrase)) {
            is Match.Of -> {
                abandon(entry, scope)
                return AddResult.Duplicate(m.entry)
            }
            Match.Unknown -> {
                Log.w(TAG, "wallet not added: its identity could not be derived")
                abandon(entry, scope)
                return AddResult.Failed
            }
            Match.None -> Unit
        }
        val stored = try {
            managerFactory.forScope(scope).storeRecordOnly(mnemonic, passphrase, creationTimeSecs, entry.origin)
        } catch (t: Throwable) {
            Log.w(TAG, "wallet record: ${t.javaClass.simpleName}")
            false
        }
        if (!stored || !registry.complete(entry.id, makeActive = true)) {
            Log.w(TAG, "wallet not added (record stored=$stored)")
            abandon(entry, scope)
            return AddResult.Failed
        }
        return AddResult.Added(registry.find(entry.id) ?: entry.copy(state = EntryState.READY))
    }

    /**
     * Erase what the add left in [scope] and drop its entry. The entry stays PENDING when something
     * of the scope still reads back present, so [WalletHousekeeping] erases it at the next launch
     * instead of the scope being orphaned with no entry naming it.
     */
    private fun abandon(entry: WalletEntry, scope: WalletScope) {
        val report = eraser.erase(scope)
        if (report.verified || !eraser.anythingLeft(scope)) {
            registry.drop(entry.id)
        } else {
            Log.w(TAG, "an abandoned add left state behind; erased at the next launch")
        }
    }

    private fun match(testnet: Boolean, mnemonic: String, passphrase: ByteArray?): Match {
        // BIP39 treats an empty passphrase as none, and so does the record (WalletManager.storeSeedRecord).
        val pass = passphrase?.takeIf { it.isNotEmpty() }
        val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)
        var seed: ByteArray? = null
        try {
            seed = deriveSeed(mnemonicBytes, pass) ?: return Match.Unknown
            val v2 = SeedFingerprint.v2(seed)
            // v1 is the mnemonic alone: it can only name a wallet opened without a passphrase.
            val v1 = if (pass == null) SeedFingerprint.v1(mnemonicBytes) else null
            for (entry in registry.list(testnet)) {
                val record = managerFactory.forScope(WalletScope(entry.id, testnet))
                val identity = record.recordIdentity()
                val same = if (identity != null) identity == v2 else v1 != null && record.recordFingerprintV1() == v1
                if (same) return Match.Of(entry)
            }
            return Match.None
        } finally {
            mnemonicBytes.fill(0)
            seed?.fill(0)
        }
    }

    private fun scopeOf(entry: WalletEntry) = WalletScope(entry.id, entry.testnet == true)

    private companion object {
        const val TAG = "WalletProvisioner"
    }
}
