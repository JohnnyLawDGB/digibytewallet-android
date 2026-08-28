package io.digibyte.ui.onboarding

import io.digibyte.core.Bip39Passphrase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the seed screen is allowed to continue.
 *
 * Two failures to prevent, pulling in opposite directions:
 *
 *  - A user who never opens the Advanced section must NEVER be blocked. The passphrase is
 *    optional; anything that gates the common path on it has broken the feature's premise.
 *  - A user who typed a passphrase but not its confirmation must never get a wallet. A BIP39
 *    passphrase has no checksum, so an unconfirmed typo is undetectable until restore, when it
 *    presents as an empty wallet and reads as stolen coins.
 */
class PassphraseEntryTest {

    @Test fun `an untouched section never blocks`() {
        assertTrue(passphraseEntryReady("", ""))
        // Even with stray text in confirm — if there is no passphrase there is nothing to confirm.
        assertTrue(passphraseEntryReady("", "leftover"))
    }

    @Test fun `a matching pair is ready`() {
        assertTrue(passphraseEntryReady("correct horse", "correct horse"))
    }

    @Test fun `an unconfirmed passphrase blocks`() {
        assertFalse("empty confirmation must not pass", passphraseEntryReady("hunter2", ""))
        assertFalse("mismatch must not pass", passphraseEntryReady("hunter2", "hunter3"))
    }

    /** Case and whitespace are significant — they change the derived seed. */
    @Test fun `near-misses still block`() {
        assertFalse(passphraseEntryReady("Hunter2", "hunter2"))
        assertFalse(passphraseEntryReady("hunter2 ", "hunter2"))
    }

    @Test fun `over the byte cap blocks even when confirmed`() {
        val over = "x".repeat(Bip39Passphrase.MAX_BYTES + 1)
        assertFalse(passphraseEntryReady(over, over))
    }

    @Test fun `exactly at the cap is allowed`() {
        val at = "x".repeat(Bip39Passphrase.MAX_BYTES)
        assertTrue(passphraseEntryReady(at, at))
    }
}
