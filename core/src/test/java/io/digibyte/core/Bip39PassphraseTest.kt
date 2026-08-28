package io.digibyte.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything that must happen to a passphrase before it reaches native.
 *
 * ## Why this returns ByteArray
 *
 * `CLAUDE.md:51` records a deliberate CRITICAL-3 remediation: the mnemonic is a `ByteArray` so it
 * never becomes an immutable JVM `String`, which cannot be zeroed and lives on the heap until GC
 * decides otherwise. The passphrase is the other half of the same secret — together they ARE the
 * wallet — and it originally shipped as a `String` at every hop, which quietly did not extend that
 * guarantee to it.
 *
 * The window cannot be closed completely: Compose text entry and `java.text.Normalizer` both
 * require a `String`, so one transient copy is unavoidable. What CAN be removed are the copies
 * that live for the lifetime of a screen or a scan. Everything downstream of [prepare] is bytes
 * the caller can zero.
 *
 * ## Normalisation (spec R2)
 *
 * `BRBIP39DeriveKey` does no Unicode normalisation and its header says the caller must. Without
 * it, an accented passphrase derives one seed here and another in Electrum — a failure that only
 * appears at restore, on someone else's software.
 *
 * ## Length is measured in BYTES (spec R3)
 *
 * The PBKDF2 salt is a stack buffer sized in bytes. Measuring the cap in CHARACTERS — as this did
 * before — meant 128 CJK characters passed the Kotlin check and were rejected by native, and the
 * user saw "Wallet creation failed" with nothing explaining why.
 */
class Bip39PassphraseTest {

    private fun str(b: ByteArray?) = b?.toString(Charsets.UTF_8)

    // ---- normalisation ----------------------------------------------------------------------

    @Test fun `composed and decomposed spellings normalise to the same bytes`() {
        val composed = "café"        // é as U+00E9
        val decomposed = "café"     // e + combining acute

        assertArrayEquals(
            "these look identical to a user and must derive one wallet",
            Bip39Passphrase.prepare(composed),
            Bip39Passphrase.prepare(decomposed),
        )
    }

    @Test fun `normalisation is NFKD, not NFC`() {
        val prepared = str(Bip39Passphrase.prepare("café"))!!
        assertTrue("expected decomposed output, got $prepared", prepared.contains('́'))
    }

    /**
     * Compatibility decomposition too: NFKD expands "½" to three characters, NFD would leave it
     * alone. Pinning this proves the FORM is NFKD rather than NFD.
     */
    @Test fun `compatibility forms decompose under NFKD`() {
        val prepared = str(Bip39Passphrase.prepare("½"))!!
        assertEquals("NFKD expands the vulgar fraction; NFD would not", 3, prepared.length)
        assertTrue("expected the digits to survive: $prepared", prepared.startsWith("1"))
    }

    // ---- the type itself, which is the point of this change ---------------------------------

    @Test fun `prepare yields bytes the caller can zero`() {
        val out = Bip39Passphrase.prepare("hunter2")!!
        assertArrayEquals("hunter2".toByteArray(Charsets.UTF_8), out)
        out.fill(0)
        assertTrue("a ByteArray can be wiped; a String cannot", out.all { it == 0.toByte() })
    }

    // ---- absent vs empty --------------------------------------------------------------------

    @Test fun `null and empty both mean no passphrase`() {
        assertNull(Bip39Passphrase.prepare(null))
        assertNull(Bip39Passphrase.prepare(""))
    }

    /** Whitespace is a real, if unwise, passphrase — trimming it would derive a different wallet. */
    @Test fun `whitespace is preserved, not trimmed`() {
        assertEquals(" ", str(Bip39Passphrase.prepare(" ")))
        assertEquals("a b", str(Bip39Passphrase.prepare("a b")))
    }

    // ---- length, in bytes -------------------------------------------------------------------

    @Test fun `a passphrase at the byte cap is accepted`() {
        val at = "x".repeat(Bip39Passphrase.MAX_BYTES)
        assertTrue(Bip39Passphrase.isValid(at))
        assertEquals(Bip39Passphrase.MAX_BYTES, Bip39Passphrase.prepare(at)!!.size)
    }

    @Test fun `a passphrase over the byte cap is rejected`() {
        assertTrue(!Bip39Passphrase.isValid("x".repeat(Bip39Passphrase.MAX_BYTES + 1)))
    }

    /**
     * THE bug this change fixes. 128 CJK characters are 384 UTF-8 bytes. Measured in characters
     * this passed Kotlin and was then rejected by native's byte-sized buffer, so the user set a
     * passphrase, wallet creation returned false, and they saw "Wallet creation failed" with
     * nothing pointing at the passphrase.
     */
    @Test fun `a multibyte passphrase is measured the way native measures it`() {
        // Each CJK character is 3 UTF-8 bytes, so 42 of them are 126 bytes — under the cap.
        val underByBytes = "你".repeat(42)
        assertEquals(126, underByBytes.toByteArray(Charsets.UTF_8).size)
        assertTrue("126 bytes is under the cap and must pass", Bip39Passphrase.isValid(underByBytes))

        // 43 characters is 129 bytes: over the BYTE cap while being far under any character cap.
        // Measured in characters — as this was before — it sailed through here and was then
        // rejected by native's byte-sized buffer, surfacing as "Wallet creation failed" with
        // nothing pointing at the passphrase.
        val overByBytes = "你".repeat(43)
        assertEquals(129, overByBytes.toByteArray(Charsets.UTF_8).size)
        assertTrue("43 chars is well under any character cap", overByBytes.length < Bip39Passphrase.MAX_BYTES)
        assertTrue("but 129 bytes exceeds it — must be rejected HERE, not by native",
            !Bip39Passphrase.isValid(overByBytes))
    }

    /** Normalisation can lengthen; the cap applies to what native actually receives. */
    @Test fun `the cap is measured after normalisation`() {
        val composedAtCap = "é".repeat(Bip39Passphrase.MAX_BYTES)
        assertTrue(!Bip39Passphrase.isValid(composedAtCap))
    }

    @Test fun `null and empty are valid — the feature is optional`() {
        assertTrue(Bip39Passphrase.isValid(null))
        assertTrue(Bip39Passphrase.isValid(""))
    }
}
