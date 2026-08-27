package io.digibyte.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An existing wallet must not notice that the passphrase feature exists.
 *
 * ## The trap
 *
 * `WalletManager.saveSeedFingerprint` stores SHA-256 of the **mnemonic bytes**, and
 * `restoreFromDisk` calls `clearSyncData()` when the stored value does not match. That works
 * today because a mnemonic identifies a wallet one-to-one.
 *
 * A passphrase breaks that: two wallets can share a mnemonic and differ only by passphrase, so
 * they would produce an IDENTICAL fingerprint and the wallet would treat one as the other. The
 * fingerprint therefore has to move to the derived 64-byte seed.
 *
 * Changing the basis in place is where the damage would be. Every wallet already installed holds
 * a mnemonic-based fingerprint. On the first launch after the upgrade the newly computed
 * seed-based value would not match it, `clearSyncData()` would fire, and **every existing user
 * would re-sync from the floor** — on a wallet whose deep-restore behaviour has been the single
 * largest source of bugs in this codebase.
 *
 * So the fingerprint is VERSIONED, and this test is the gate on that: an install carrying only
 * the v1 value must be recognised, adopted, and left alone.
 */
class SeedFingerprintMigrationTest {

    private val mnemonic = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"
    private val mnemonicBytes = mnemonic.toByteArray(Charsets.UTF_8)

    /** The 64-byte seed for the mnemonic above with no passphrase (pinned by the host KAT). */
    private val seedNoPass = hex(
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
        "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    )

    /** Same mnemonic, passphrase "TREZOR" — a DIFFERENT wallet. */
    private val seedWithPass = hex(
        "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e5349553" +
        "1f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
    )

    private fun hex(s: String) =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---- why the basis has to change at all ------------------------------------------------

    /**
     * The bug the version bump exists to fix: under the OLD basis these two wallets are
     * indistinguishable, so the wallet would keep one wallet's sync data while running the other.
     */
    @Test fun `mnemonic-based fingerprints collide across passphrases`() {
        assertEquals(
            "same mnemonic ⇒ same v1 fingerprint, which is the collision",
            SeedFingerprint.v1(mnemonicBytes),
            SeedFingerprint.v1(mnemonicBytes),
        )
    }

    @Test fun `seed-based fingerprints separate them`() {
        assertFalse(
            "a passphrase must produce a different wallet identity",
            SeedFingerprint.v2(seedNoPass) == SeedFingerprint.v2(seedWithPass),
        )
    }

    // ---- the upgrade path, which is the part that could hurt people ------------------------

    /**
     * THE GATE. An install that has only the v1 value must be recognised as the same wallet, so
     * no sync data is cleared. If this ever fails, shipping it re-syncs the whole userbase.
     */
    @Test fun `a v1-only install is recognised and does not count as changed`() {
        val stored = SeedFingerprint.Stored(v1 = SeedFingerprint.v1(mnemonicBytes), v2 = null)
        val verdict = SeedFingerprint.evaluate(stored, mnemonicBytes, seedNoPass)

        assertFalse("must NOT be treated as a seed change", verdict.seedChanged)
        assertTrue("and must adopt v2 so the next launch needs no v1 check", verdict.writeV2)
        assertEquals(SeedFingerprint.v2(seedNoPass), verdict.v2ToWrite)
    }

    /** After migration only v2 is consulted; the same wallet stays unchanged. */
    @Test fun `a migrated install matches on v2 alone`() {
        val stored = SeedFingerprint.Stored(v1 = null, v2 = SeedFingerprint.v2(seedNoPass))
        val verdict = SeedFingerprint.evaluate(stored, mnemonicBytes, seedNoPass)

        assertFalse(verdict.seedChanged)
        assertFalse("nothing to write, v2 is already current", verdict.writeV2)
    }

    /** A genuinely different seed still clears, which is the behaviour being preserved. */
    @Test fun `a real seed change is still detected`() {
        val stored = SeedFingerprint.Stored(v1 = null, v2 = SeedFingerprint.v2(seedNoPass))
        val verdict = SeedFingerprint.evaluate(stored, mnemonicBytes, seedWithPass)

        assertTrue("a different seed must clear sync data", verdict.seedChanged)
        assertTrue(verdict.writeV2)
    }

    /**
     * v1 present but NOT matching means the mnemonic itself changed — an
     * uninstall/reinstall with a different phrase. That is a real change and must clear, exactly
     * as it does today.
     */
    @Test fun `a v1 install whose mnemonic changed still clears`() {
        val otherMnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
            .toByteArray(Charsets.UTF_8)
        val stored = SeedFingerprint.Stored(v1 = SeedFingerprint.v1(otherMnemonic), v2 = null)
        val verdict = SeedFingerprint.evaluate(stored, mnemonicBytes, seedNoPass)

        assertTrue(verdict.seedChanged)
    }

    /** A fresh install has neither; it is a new wallet, so there is nothing to preserve. */
    @Test fun `a fresh install writes v2 and is treated as new`() {
        val verdict = SeedFingerprint.evaluate(
            SeedFingerprint.Stored(v1 = null, v2 = null), mnemonicBytes, seedNoPass
        )
        assertTrue(verdict.seedChanged)
        assertTrue(verdict.writeV2)
    }
}
