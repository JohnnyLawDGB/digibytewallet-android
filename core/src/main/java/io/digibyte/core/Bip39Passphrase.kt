package io.digibyte.core

import java.text.Normalizer

/**
 * Prepares an optional BIP39 passphrase for the native derivation.
 *
 * ## Why normalisation is not optional
 *
 * `BRBIP39DeriveKey` performs none — it concatenates `"mnemonic" + passphrase` and runs PBKDF2 —
 * and its header states plainly that the caller must supply NFKD-normalised input. Unicode gives
 * "café" at least two spellings: composed (U+00E9) and decomposed (e + U+0301). A user typing the
 * same visible passphrase on Android and in Electrum can produce either, and unnormalised they
 * derive different wallets.
 *
 * The failure mode is the worst kind available here: it appears at RESTORE, on someone else's
 * software, with a valid empty wallet and no error to explain it.
 *
 * ## Why the length is bounded
 *
 * The PBKDF2 salt is a stack VLA sized by the passphrase, so an unbounded value is a stack
 * overflow. Native enforces the same bound; this layer exists so the user can be told before
 * they commit to a passphrase that will be rejected.
 *
 * The bound is measured AFTER normalisation, because decomposition lengthens strings — a value
 * that fits before normalising can overflow after it.
 *
 * ## Absent and empty are the same thing
 *
 * BIP39 salts with `"mnemonic" + passphrase`, so no passphrase and an empty one derive the same
 * seed. Every wallet created before this feature used the former; they must never diverge.
 */
object Bip39Passphrase {

    /** Characters allowed after normalisation. Mirrors PASSPHRASE_MAX in jni_wallet.c. */
    const val MAX_LENGTH = 128

    /**
     * Normalise for derivation.
     *
     * @return the NFKD form, or null when there is no passphrase. Whitespace is deliberately NOT
     *   trimmed: " " is a real, if unwise, passphrase, and silently trimming it would derive a
     *   different wallet from the one the user set up elsewhere.
     */
    fun prepare(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return Normalizer.normalize(raw, Normalizer.Form.NFKD)
    }

    /** Whether this passphrase can be used. Absent and empty are valid — the feature is optional. */
    fun isValid(raw: String?): Boolean {
        val prepared = prepare(raw) ?: return true
        return prepared.length <= MAX_LENGTH
    }
}
