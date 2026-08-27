package io.digibyte.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything that must happen to a passphrase before it reaches native.
 *
 * ## Normalisation (spec R2)
 *
 * `BRBIP39DeriveKey` does no Unicode normalisation — it concatenates and runs PBKDF2 — and its
 * header states that phrase and passphrase "must be unicode NFKD normalized". The host KAT
 * demonstrates the consequence: a composed "café" and a decomposed "café" derive DIFFERENT seeds.
 *
 * A user typing the same visible passphrase on Android and in Electrum can produce either
 * spelling. Without normalisation here, their wallet restores nowhere else — and they find out at
 * restore time, on someone else's software, with no way to tell why.
 *
 * ## Length (spec R3)
 *
 * The PBKDF2 salt is a stack VLA sized by the passphrase, so an unbounded value is a stack
 * overflow. Native enforces the bound too; this is the layer that can tell the user.
 *
 * ## Absent and empty are the same wallet (spec R1)
 *
 * BIP39 salts with `"mnemonic" + passphrase`, so no passphrase and an empty one are identical.
 * Every wallet created before this feature used the former. They must never diverge.
 */
class Bip39PassphraseTest {

    // ---- normalisation ----------------------------------------------------------------------

    @Test fun `composed and decomposed spellings normalise to the same value`() {
        val composed = "café"        // é as U+00E9
        val decomposed = "café"     // e + combining acute

        assertEquals(
            "these look identical to a user and must derive one wallet",
            Bip39Passphrase.prepare(composed),
            Bip39Passphrase.prepare(decomposed),
        )
    }

    @Test fun `normalisation is NFKD, not NFC`() {
        // NFKD decomposes; if this ever returns the composed form the wrong form was chosen and
        // seeds will not match other BIP39 wallets.
        val prepared = Bip39Passphrase.prepare("café")!!
        assertTrue("expected decomposed output, got $prepared", prepared.contains('́'))
    }

    /**
     * Compatibility decomposition too: NFKD expands "½" to three characters, NFD would leave it
     * alone. Pinning this proves the FORM is NFKD rather than NFD — they differ only on inputs
     * like this one, and the spec requires NFKD.
     */
    @Test fun `compatibility forms decompose under NFKD`() {
        val prepared = Bip39Passphrase.prepare("½")!!
        assertEquals("NFKD expands the vulgar fraction; NFD would not", 3, prepared.length)
        assertTrue("expected the digits to survive: $prepared", prepared.startsWith("1"))
    }

    // ---- absent vs empty --------------------------------------------------------------------

    @Test fun `null and empty and blank all mean no passphrase`() {
        assertNull(Bip39Passphrase.prepare(null))
        assertNull(Bip39Passphrase.prepare(""))
    }

    /**
     * Whitespace is NOT trimmed. A passphrase of " " is a real, if unwise, passphrase, and
     * silently trimming it would derive a different wallet from the one the user set up
     * elsewhere. Only a genuinely empty string means "none".
     */
    @Test fun `whitespace is preserved, not trimmed`() {
        assertEquals(" ", Bip39Passphrase.prepare(" "))
        assertEquals("a b", Bip39Passphrase.prepare("a b"))
    }

    // ---- length -----------------------------------------------------------------------------

    @Test fun `a passphrase at the cap is accepted`() {
        val at = "x".repeat(Bip39Passphrase.MAX_LENGTH)
        assertTrue(Bip39Passphrase.isValid(at))
        assertEquals(at, Bip39Passphrase.prepare(at))
    }

    @Test fun `a passphrase over the cap is rejected`() {
        val over = "x".repeat(Bip39Passphrase.MAX_LENGTH + 1)
        assertTrue("must be rejected before it reaches the JNI boundary", !Bip39Passphrase.isValid(over))
    }

    /**
     * The cap applies to what native will actually receive. Normalisation can LENGTHEN a string —
     * "é" is one char composed and two decomposed — so a value that fits before normalising can
     * overflow after it. Measuring the wrong one is how a bound gets bypassed.
     */
    @Test fun `the cap is measured after normalisation, not before`() {
        val composedAtCap = "é".repeat(Bip39Passphrase.MAX_LENGTH)
        assertTrue(
            "decomposition doubles this; it must not pass a check made before normalising",
            !Bip39Passphrase.isValid(composedAtCap),
        )
    }

    @Test fun `null and empty are valid — the feature is optional`() {
        assertTrue(Bip39Passphrase.isValid(null))
        assertTrue(Bip39Passphrase.isValid(""))
    }
}
