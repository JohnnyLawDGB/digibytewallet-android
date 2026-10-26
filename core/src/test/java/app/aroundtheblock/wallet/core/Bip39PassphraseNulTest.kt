package app.aroundtheblock.wallet.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.1.2 row 25: a BIP39 passphrase with an embedded NUL is refused, never truncated. The native
 * derivation read it as a C string, so "abc\u0000evil" derived the wallet of "abc".
 */
class Bip39PassphraseNulTest {

    @Test fun `a passphrase with an embedded NUL is not valid`() {
        assertFalse(Bip39Passphrase.isValid("abc\u0000evil"))
        assertFalse(Bip39Passphrase.isValid("\u0000"))
    }

    @Test fun `ordinary passphrases, control characters other than NUL included, stay valid`() {
        assertTrue(Bip39Passphrase.isValid("correct horse"))
        assertTrue(Bip39Passphrase.isValid("tab\there"))   // another wallet may have made one like this
        assertTrue(Bip39Passphrase.isValid(null))
    }
}
