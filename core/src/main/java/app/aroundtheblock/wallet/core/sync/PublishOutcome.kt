package app.aroundtheblock.wallet.core.sync

import kotlinx.coroutines.delay

/**
 * What the app should do with the result of a transaction publish.
 *
 * Until v4.0.42 nothing could reach this decision: the JNI passed a NULL callback to
 * `BRPeerManagerPublishTx`, so a send the network refused was indistinguishable from one it
 * accepted — and the wallet had already marked its inputs spent either way. The callback is
 * now wired; this turns the errno it delivers into an action.
 *
 * The core answers each publish exactly once (the contract is written out in
 * `jni_transaction.c`, above `_publishResult`):
 *
 * | code        | meaning                                                                    |
 * |-------------|----------------------------------------------------------------------------|
 * | `0`         | a peer took it — it requested or relayed the transaction — or a block confirmed it |
 * | `EINVAL`    | unsigned; or invalid by the wallet's own rules when a peer asked for it; or a peer rejected it as invalid, non-standard or dust while no other peer had it |
 * | `ENOTCONN`  | not connected, a disconnect cancelled the pending publish, or the peer manager was torn down |
 * | `ETIMEDOUT` | it went out and no peer took it before the timeout                          |
 * | `ECANCELED` | the wallet itself removed the transaction while the publish was pending   |
 *
 * ## Why ETIMEDOUT is the interesting one
 *
 * DigiByte Core sends no reject message, so for most peers **silence is the only evidence a
 * transaction was refused**. That is exactly the live failure this work came from: an asset
 * transfer that published, reported six relays, and existed in no mempool and no block, because
 * it spent an output no other node had ever seen.
 *
 * But silence is also what a merely-slow relay looks like, so it is deliberately NOT terminal.
 * The asymmetry is the whole point: **wrongly retrying costs a little radio; wrongly
 * destroying a send loses a transaction that was still propagating.** Only [Kind.REJECTED] —
 * the transaction itself is invalid, by the wallet's rules or a peer's — is allowed to be
 * terminal, and an unrecognised code always falls back to retryable.
 */
data class PublishOutcome(
    val kind: Kind,
    /** Worth publishing again. False only when the send is finished or provably hopeless. */
    val shouldRetry: Boolean,
    /** Proven never-acceptable, so the wallet may stop treating its inputs as spent. */
    val isTerminal: Boolean,
) {
    enum class Kind {
        /** A peer took it (requested or relayed it), or a block confirmed it. */
        ACCEPTED,

        /** Invalid — by the wallet's own rules, or a peer's (invalid, non-standard, dust) — or unsigned. It can never be accepted. */
        REJECTED,

        /** It never reached the wire (offline, or the publish was cancelled). */
        NOT_DELIVERED,

        /** It reached the wire and no peer took it before the timeout. Suspicious, not conclusive. */
        UNCONFIRMED_DELIVERY,
    }

    /** True only for [Kind.ACCEPTED] — the one state that may honestly be shown as "sent". */
    val userVisiblySent: Boolean get() = kind == Kind.ACCEPTED

    /** The stranded-send sweep's action on a recorded send: see [actionFor]. */
    enum class Action {
        /** Carry on as before: the validity check, then a republish under the bounded cadence. */
        KEEP,

        /** Remove the transaction from the wallet, which unlocks its coins, and forget its record. */
        REMOVE_AND_UNLOCK,
    }

    /**
     * What the stranded-send sweep does with a recorded send whose last publish ended this way.
     *
     * No single peer's message removes a transaction or unlocks its coins. A terminal verdict can
     * be one peer's rejection — and a peer can reject a valid transaction honest peers already
     * relayed; removing it would give its coins back, the user would pay again with other coins,
     * and both payments could confirm. So a plain DGB send is removed, giving its coins back, and
     * its record forgotten only when the verdict is terminal AND [refusedByPeers]: the core saw
     * peers in at least two network groups reject it and no peer relay or announce it. Otherwise
     * it is kept, its coins stay locked, and the sweep carries on as before. An asset or
     * DigiDollar send ([plainDgb] false) keeps its own handling either way: the asset layer's
     * CONFLICTED accounting reads an abandoned attempt from the wallet.
     */
    fun actionFor(plainDgb: Boolean, refusedByPeers: Boolean): Action =
        if (isTerminal && plainDgb && refusedByPeers) Action.REMOVE_AND_UNLOCK else Action.KEEP

    companion object {
        // errno values as reported by the C core; named here so the policy reads without
        // a platform header and stays testable on a plain JVM.
        const val EINVAL = 22
        const val ENOTCONN = 107
        const val ETIMEDOUT = 110
        const val ECANCELED = 125

        /** The publish result ring's answer while no verdict has arrived. */
        const val PENDING = -1

        /**
         * The verdict on a send just published, waited for: [read] (the publish result ring) every
         * [pollMs] until it answers, for at most [timeoutMs]. Null when no verdict arrived — which is
         * never "sent". The core answers each publish once, so the first verdict is the verdict; and
         * it answers [ETIMEDOUT] once no peer has taken the transaction for 20 seconds (its protocol
         * timeout), so the default wait outlasts that.
         */
        suspend fun await(
            read: suspend () -> Int,
            pollMs: Long = 500L,
            timeoutMs: Long = 25_000L,
        ): PublishOutcome? {
            var waited = 0L
            while (true) {
                val verdict = read()
                if (verdict != PENDING) return of(verdict)
                if (waited >= timeoutMs) return null
                delay(pollMs)
                waited += pollMs
            }
        }

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
