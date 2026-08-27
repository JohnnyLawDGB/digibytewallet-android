package io.digibyte.core

import java.security.MessageDigest

/**
 * Which wallet the stored sync data belongs to.
 *
 * `restoreFromDisk` clears saved blocks, peers and transactions when the seed has changed. That
 * decision needs an identity for the wallet, and until now the identity was SHA-256 of the
 * **mnemonic**.
 *
 * ## Why the basis had to change
 *
 * A BIP39 passphrase makes one mnemonic capable of opening many different wallets. Under the old
 * basis they all share a fingerprint, so the wallet would carry one wallet's sync data into
 * another — wrong balances, wrong confirmation counts, and no signal that anything was amiss.
 * Identity has to be the derived 64-byte seed, which is what actually distinguishes them.
 *
 * ## Why it is versioned rather than replaced
 *
 * Every install that already exists holds a mnemonic-based value. Recomputing on the new basis
 * would mismatch on the first launch after upgrade, and `clearSyncData()` would fire for
 * **every existing user** — a full re-sync from the floor, on the code path that has been this
 * project's largest source of bugs. So v1 is read, recognised, and retired quietly:
 *
 *  - v1 present and matching, v2 absent  → same wallet. Adopt v2, change nothing else.
 *  - v2 present                          → v2 is the only thing consulted.
 *  - neither                             → a new wallet.
 *
 * [SeedFingerprintMigrationTest] is the gate; the v1-only case is the one that would hurt people.
 */
object SeedFingerprint {

    /** Legacy identity: SHA-256 of the mnemonic bytes. Read for migration, never written again. */
    fun v1(mnemonicBytes: ByteArray): String = sha256(mnemonicBytes)

    /** Current identity: SHA-256 of the derived 64-byte BIP39 seed, so a passphrase counts. */
    fun v2(seedBytes: ByteArray): String = sha256(seedBytes)

    /** What is on disk right now. Either may be absent. */
    data class Stored(val v1: String?, val v2: String?)

    /**
     * @param seedChanged whether sync data must be cleared.
     * @param writeV2     whether [v2ToWrite] should be persisted.
     */
    data class Verdict(val seedChanged: Boolean, val writeV2: Boolean, val v2ToWrite: String)

    fun evaluate(stored: Stored, mnemonicBytes: ByteArray, seedBytes: ByteArray): Verdict {
        val currentV2 = v2(seedBytes)

        stored.v2?.let { existing ->
            val changed = existing != currentV2
            return Verdict(seedChanged = changed, writeV2 = changed, v2ToWrite = currentV2)
        }

        // No v2 yet. A matching v1 means this is an install from before the passphrase work —
        // the same wallet it has always been. Adopt v2 silently; clearing here would re-sync
        // every existing user for no reason.
        val v1Matches = stored.v1 != null && stored.v1 == v1(mnemonicBytes)
        return Verdict(seedChanged = !v1Matches, writeV2 = true, v2ToWrite = currentV2)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
