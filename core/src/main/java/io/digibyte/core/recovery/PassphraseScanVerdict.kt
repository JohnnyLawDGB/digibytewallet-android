package io.digibyte.core.recovery

/**
 * What to tell someone whose passphrase found nothing.
 *
 * A BIP39 passphrase has no checksum, so a typo does not fail — it derives a different, valid,
 * empty wallet. The scan then honestly reports no funds, and the person reading it concludes
 * their coins are gone. "No recoverable funds found" is the most damaging sentence this flow can
 * show, because it is a confident answer to a question asked only one way.
 *
 * Scanning the same mnemonic without the passphrase costs one extra pass and answers the question
 * that matters: does this phrase have funds at all? If it does, the passphrase is almost certainly
 * mistyped or unnecessary, and saying so is the difference between a five-second fix and someone
 * believing they have been robbed.
 */
object PassphraseScanVerdict {

    enum class Outcome {
        /** Funds found under the passphrase. Nothing to explain. */
        FOUND,

        /** Nothing under the passphrase, but the bare phrase HAS funds — almost certainly a typo. */
        LIKELY_TYPO,

        /** Nothing either way. An honest empty result. */
        NONE_ANYWHERE,

        /** The scan could not finish. Claim nothing — see RecoveryScanService.anyBackendUnreachable. */
        INCOMPLETE,
    }

    /**
     * @param withPassphraseSat    total found using the supplied passphrase.
     * @param withoutPassphraseSat total found without it, or null when no comparison scan ran
     *                             (which is the case whenever no passphrase was supplied).
     * @param incomplete           true when any derivation path could not be checked.
     */
    fun of(withPassphraseSat: Long, withoutPassphraseSat: Long?, incomplete: Boolean): Outcome = when {
        // POSITIVE findings first, and deliberately ahead of `incomplete`.
        //
        // The first version of this put `incomplete` on top, which sounded conservative and was
        // wrong. BIP49 fails against the reconcile backend on every scan, so `incomplete` is
        // effectively always true — the typo hint, the most useful sentence in this flow, could
        // never fire for anyone. It passed its unit test and was unreachable in production.
        //
        // Money observed on an address is observed. An unchecked BIP49 path cannot make it
        // untrue. Only the NEGATIVE conclusion — "nothing anywhere" — is a claim about paths the
        // wallet never looked at, and that is the one that has to wait for a complete scan.
        withPassphraseSat > 0L -> Outcome.FOUND
        (withoutPassphraseSat ?: 0L) > 0L -> Outcome.LIKELY_TYPO
        incomplete -> Outcome.INCOMPLETE
        else -> Outcome.NONE_ANYWHERE
    }
}
