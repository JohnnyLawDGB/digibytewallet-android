package io.digibyte.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The passphrase actually changes the wallet — on a device, through the real JNI boundary.
 *
 * ## Why this is an instrumented test and not a host KAT
 *
 * The host KAT proves `BRBIP39DeriveKey` matches the BIP39 vectors. It cannot prove the JNI
 * signature change is wired correctly, because JNI symbol names do not encode arity: a stale
 * library still LINKS and then misbehaves at runtime. Only a real `System.loadLibrary` on a real
 * device settles whether the passphrase argument reaches the C.
 *
 * ## Why it is not a UI test
 *
 * The seed screen sets FLAG_SECURE, so it cannot be screenshotted, and driving two password
 * fields through the soft keyboard proved to race with recomposition. Those are harness problems.
 * The property worth pinning — that a passphrase produces a different wallet, deterministically —
 * is better tested here, where it can be asserted rather than inferred from a pixel.
 */
@RunWith(AndroidJUnit4::class)
class PassphraseDerivationTest {

    private val mnemonic = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"
    private val bytes get() = mnemonic.toByteArray(Charsets.UTF_8)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun noPassphraseMatchesTheBip39Vector() {
        val seed = NativeBridge.mnemonicToSeed(bytes, null)
        assertNotNull("derivation returned null across JNI", seed)
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
            "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            hex(seed!!),
        )
        seed.fill(0)
    }

    /** The interop anchor, across the real JNI boundary this time. */
    @Test fun aPassphraseReachesTheNativeDerivation() {
        val seed = NativeBridge.mnemonicToSeed(bytes, "TREZOR".toByteArray(Charsets.UTF_8))
        assertNotNull(seed)
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e5349553" +
            "1f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            hex(seed!!),
        )
        seed.fill(0)
    }

    /** The whole point: same words, different passphrase, different wallet. */
    @Test fun aPassphraseProducesADifferentWallet() {
        val plain = NativeBridge.mnemonicToSeed(bytes, null)!!
        val withPass = NativeBridge.mnemonicToSeed(bytes, "test123".toByteArray(Charsets.UTF_8))!!
        assertNotEquals(hex(plain), hex(withPass))
        plain.fill(0); withPass.fill(0)
    }

    /**
     * Absent and empty must stay the same wallet. Every wallet created before this feature used
     * the former; if these ever diverged, an upgrade would move everyone's addresses.
     */
    @Test fun nullAndEmptyAgreeAcrossJni() {
        val a = NativeBridge.mnemonicToSeed(bytes, null)!!
        val b = NativeBridge.mnemonicToSeed(bytes, ByteArray(0))!!
        assertEquals(hex(a), hex(b))
        a.fill(0); b.fill(0)
    }

    /**
     * Normalisation happens in Kotlin, so the two spellings must agree by the time they reach
     * native. This is the end-to-end version of the host KAT's deliberate non-property.
     */
    @Test fun normalisedSpellingsAgree() {
        val composed = Bip39Passphrase.prepare("café")
        val decomposed = Bip39Passphrase.prepare("café")
        val a = NativeBridge.mnemonicToSeed(bytes, composed)!!
        val b = NativeBridge.mnemonicToSeed(bytes, decomposed)!!
        assertEquals("Bip39Passphrase.prepare must make these one wallet", hex(a), hex(b))
        a.fill(0); b.fill(0)
    }

    /**
     * A passphrase over the cap must never reach native, where the PBKDF2 salt is a stack VLA
     * sized by its length.
     */
    @Test fun anOverlongPassphraseIsRejectedBeforeJni() {
        val over = "x".repeat(Bip39Passphrase.MAX_BYTES + 1)
        assertEquals(false, Bip39Passphrase.isValid(over))
    }
}
