package io.digibyte.core.send

/**
 * A send that did not go out, described so the user can act on it.
 *
 * ## Why this exists
 *
 * Failure used to be one line of small red text at the bottom of the send form, below the button,
 * with nothing to dismiss and nothing recorded — while success was a full-screen overlay that
 * blocked the app until acknowledged. The two outcomes had opposite volumes, and the quiet one
 * was the one that mattered.
 *
 * Reproduced on a Note 8 on 2026-08-28: `BRWalletCreateTransaction` returned NULL, the confirm
 * dialog closed, the form returned to exactly its previous state, and the entire report was the
 * words "Insufficient balance" — below the fold if the form happened to be scrolled. From the
 * outside that is indistinguishable from nothing having happened at all, which is how three real
 * sends came to be described as having "not registered anywhere".
 *
 * ## Why a model rather than a string
 *
 * "Insufficient balance" is true and useless when the balance is visibly sufficient: the amount
 * left nothing over for the fee. A failure someone cannot act on reads as a malfunction, and a
 * wallet that looks like it malfunctioned while holding their money is precisely the impression
 * to avoid. So each failure carries what happened, whether trying again could help, and what to
 * change.
 *
 * Pure — no Android, no JNI. The UI maps [guidanceKey] to localised text.
 */
data class SendFailure(
    val kind: Kind,
    /** Whether attempting the same send again could plausibly succeed. */
    val retryable: Boolean,
    /** Stable key the UI resolves to localised guidance. */
    val guidanceKey: String,
    /** The original reason, never discarded — for an unanticipated failure it is the only clue
     *  anyone will have, and it is what a bug report needs to contain. */
    val rawReason: String,
) {
    enum class Kind { INSUFFICIENT, BROADCAST, SIGNING, ADDRESS, AMOUNT, UNKNOWN }

    companion object {
        /**
         * Classify a reason from [io.digibyte.core.TxResult.Error].
         *
         * Matched on content rather than equality: these strings are produced in several places
         * and get reworded, and a classifier that silently degrades to UNKNOWN when someone
         * fixes a typo is worse than no classifier.
         */
        fun of(reason: String?): SendFailure {
            val raw = reason?.trim().orEmpty()
            val r = raw.lowercase()
            return when {
                raw.isEmpty() -> SendFailure(
                    Kind.UNKNOWN, retryable = true, guidanceKey = "send_fail_unknown",
                    rawReason = "The transaction could not be sent.",
                )
                r.contains("insufficient") -> SendFailure(
                    Kind.INSUFFICIENT, retryable = false,
                    // Names the fee, because that is the part that surprises people: the balance
                    // looks like enough until the network fee has to come out of it too.
                    guidanceKey = "send_fail_insufficient_fee", rawReason = raw,
                )
                r.contains("broadcast") -> SendFailure(
                    // Built and signed; only relay failed. Peers come and go, so the same send
                    // may work moments later — this is the one worth offering a retry on.
                    Kind.BROADCAST, retryable = true,
                    guidanceKey = "send_fail_broadcast", rawReason = raw,
                )
                r.contains("sign") -> SendFailure(
                    Kind.SIGNING, retryable = false,
                    guidanceKey = "send_fail_signing", rawReason = raw,
                )
                r.contains("address") -> SendFailure(
                    Kind.ADDRESS, retryable = false,
                    guidanceKey = "send_fail_address", rawReason = raw,
                )
                r.contains("amount") -> SendFailure(
                    Kind.AMOUNT, retryable = false,
                    guidanceKey = "send_fail_amount", rawReason = raw,
                )
                else -> SendFailure(
                    Kind.UNKNOWN, retryable = true,
                    guidanceKey = "send_fail_unknown", rawReason = raw,
                )
            }
        }
    }
}
