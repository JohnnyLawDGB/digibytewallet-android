package io.digibyte.core.sync

/**
 * What the app should do with the result of a transaction publish.
 *
 * Until v4.0.42 nothing could reach this decision: the JNI passed a NULL callback to
 * `BRPeerManagerPublishTx`, so a send the network refused was indistinguishable from one it
 * accepted — and the wallet had already marked its inputs spent either way. The callback is
 * now wired; this turns the errno it delivers into an action.
 *
 * The codes come from `BRPeerManagerPublishTx` and its cancellation paths:
 *
 * | code        | meaning                                                        |
 * |-------------|----------------------------------------------------------------|
 * | `0`         | a peer relayed the transaction back — genuine acceptance        |
 * | `EINVAL`    | the transaction is not signed / malformed                       |
 * | `ENOTCONN`  | not connected, or a disconnect cancelled the pending publish    |
 * | `ETIMEDOUT` | it went out and NO peer echoed it back before the timeout       |
 *
 * ## Why ETIMEDOUT is the interesting one
 *
 * Peers do not announce rejections — BIP61 reject messages are long gone — so **silence is
 * the only evidence a transaction was refused**. That is exactly the live failure this work
 * came from: an asset transfer that published, reported six relays, and existed in no mempool
 * and no block, because it spent an output no other node had ever seen.
 *
 * But silence is also what a merely-slow relay looks like, so it is deliberately NOT terminal.
 * The asymmetry is the whole point: **wrongly retrying costs a little radio; wrongly
 * destroying a send loses a transaction that was still propagating.** Only [Kind.REJECTED] —
 * where the core itself says the transaction is malformed — is allowed to be terminal, and an
 * unrecognised code always falls back to retryable.
 */
data class PublishOutcome(
    val kind: Kind,
    /** Worth publishing again. False only when the send is finished or provably hopeless. */
    val shouldRetry: Boolean,
    /** Proven never-acceptable, so the wallet may stop treating its inputs as spent. */
    val isTerminal: Boolean,
) {
    enum class Kind {
        /** A peer relayed it back to us. The network has it. */
        ACCEPTED,

        /** The core refused it outright — malformed or unsigned. It can never be accepted. */
        REJECTED,

        /** It never reached the wire (offline, or the publish was cancelled). */
        NOT_DELIVERED,

        /** It reached the wire and no peer echoed it back. Suspicious, not conclusive. */
        UNCONFIRMED_DELIVERY,
    }

    /** True only for [Kind.ACCEPTED] — the one state that may honestly be shown as "sent". */
    val userVisiblySent: Boolean get() = kind == Kind.ACCEPTED

    companion object {
        // errno values as reported by the C core; named here so the policy reads without
        // a platform header and stays testable on a plain JVM.
        const val EINVAL = 22
        const val ENOTCONN = 107
        const val ETIMEDOUT = 110

        fun of(error: Int): PublishOutcome = when (error) {
            0 -> PublishOutcome(Kind.ACCEPTED, shouldRetry = false, isTerminal = false)
            EINVAL -> PublishOutcome(Kind.REJECTED, shouldRetry = false, isTerminal = true)
            ETIMEDOUT ->
                PublishOutcome(Kind.UNCONFIRMED_DELIVERY, shouldRetry = true, isTerminal = false)
            // ENOTCONN and anything unrecognised: assume the transaction is fine and the
            // delivery was not. Fails safe — see the asymmetry note above.
            else -> PublishOutcome(Kind.NOT_DELIVERED, shouldRetry = true, isTerminal = false)
        }
    }
}
