package io.digibyte.core

import java.text.Normalizer

/**
 * Prepares an optional BIP39 passphrase for the native derivation.
 *
 * ## Why this hands back bytes
 *
 * `CLAUDE.md:51` records a deliberate CRITICAL-3 remediation: the mnemonic is carried as a
 * `ByteArray` so it never becomes an immutable JVM `String`. A String cannot be zeroed, lives on
 * the heap until GC chooses otherwise, and may be duplicated by GC compaction. The passphrase is
 * the other half of the same secret — the two together ARE the wallet — and it originally shipped
 * as a String at every hop, quietly not extending that guarantee to it.
 *
 * The window cannot be closed completely. Compose text entry produces a String, and
 * [Normalizer] only operates on one, so a single transient copy is unavoidable and this file is
 * where it lives and dies. What the ByteArray return DOES remove are the copies that persist:
 * the value held in a ViewModel for the life of a screen, the one returned by
 * `WalletManager.loadPassphrase()`, and the one handed across JNI. Those are now bytes that every
 * caller zeroes.
 *
 * ## Normalisation is not optional
 *
 * `BRBIP39DeriveKey` performs none and its header states the caller must supply NFKD. Unicode
 * gives "café" at least two spellings; unnormalised they derive different wallets, and the user
 * discovers that at RESTORE, on someone else's software, with a valid empty wallet and no error.
 *
 * ## The cap is BYTES, and that is a fix
 *
 * The PBKDF2 salt is a stack buffer sized in bytes. This originally measured the cap in
 * CHARACTERS, so 128 CJK characters — 384 UTF-8 bytes — passed here and were rejected by native.
 * Wallet creation returned false and the user saw "Wallet creation failed" with nothing pointing
 * at the passphrase. Both sides now count the same unit.
 */
object Bip39Passphrase {

    /** Maximum UTF-8 bytes after normalisation. Mirrors PASSPHRASE_MAX in jni_wallet.c. */
    const val MAX_BYTES = 128

    /**
     * Normalise to NFKD and encode as UTF-8.
     *
     * @return the bytes, or null when there is no passphrase. **The caller owns the result and
     *   must `fill(0)` it when done.** Whitespace is deliberately NOT trimmed: " " is a real, if
     *   unwise, passphrase, and silently trimming it would derive a different wallet from the one
     *   the user set up elsewhere.
     */
    fun prepare(raw: String?): ByteArray? {
        if (raw.isNullOrEmpty()) return null
        return Normalizer.normalize(raw, Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
    }

    /**
     * Whether this passphrase can be used. Absent and empty are valid — the feature is optional.
     *
     * Zeroes its own working copy: validation runs on every keystroke in the entry field, and a
     * rejected passphrase is still a passphrase.
     */
    fun isValid(raw: String?): Boolean {
        val prepared = prepare(raw) ?: return true
        return try {
            prepared.size <= MAX_BYTES
        } finally {
            prepared.fill(0)
        }
    }
}
