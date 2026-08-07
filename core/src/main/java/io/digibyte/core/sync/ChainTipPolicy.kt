package io.digibyte.core.sync

/**
 * Where a confirmation count comes from, and why the obvious fallback is wrong.
 *
 * A confirmation count is not stored — it is derived, every refresh, as
 * `chainTip - txHeight + 1`. That makes it only as good as the chain tip fed in.
 *
 * At app open the native peer manager has not yet loaded saved blocks, so
 * `NativeBridge.getLastBlockHeight()` reports 0 for the first stretch of a session. With a tip of
 * 0 every confirmation computes as 0 and the whole history renders "Unconfirmed" in error red —
 * so the UI floored the tip to the highest transaction height it could see.
 *
 * That floor is the bug. For the NEWEST transaction, `maxTxHeight` IS its own height, so the
 * arithmetic collapses to `maxTxHeight - maxTxHeight + 1` = 1. The newest transaction was
 * therefore guaranteed to read "1 confirmation" on every single launch until the real tip loaded
 * and the count corrected itself. Users read that as the wallet re-verifying their transaction on
 * every open. Nothing was re-verified: the transaction's height was right the whole time and only
 * the tip it was measured against was wrong.
 *
 * The fix is to remember the tip across sessions. It is the one number that makes the count
 * correct immediately, it only ever grows, and a value from last session is never an
 * over-estimate of this session's chain.
 *
 * DISPLAY ONLY. Nothing here may seed a scan floor, a birth height, a rescan anchor or any
 * retention decision. A persisted tip is attacker-influenced state in the sense that matters here
 * — it survives restarts and is not verified against the chain — so letting it bound sync would
 * turn a cosmetic value into a correctness one. It is safe precisely because the only thing it can
 * do is change a number next to a transaction.
 */
object ChainTipPolicy {

    /** No tip is known from this source. */
    const val UNKNOWN_TIP = 0L

    /**
     * The best chain tip available to render confirmations against, in preference order.
     *
     * [nativeTip] and [persistedTip] are both real observations of the chain, so the higher one
     * wins: during a catch-up the native tip climbs from a checkpoint and is legitimately BEHIND
     * last session's tip, and measuring against the stale-but-higher value is closer to the truth
     * than measuring against a tip the wallet has already surpassed once.
     *
     * [maxTxHeight] is deliberately last, and is NOT a peer of the other two — it is not an
     * observation of the chain at all, just the deepest block the wallet happens to hold a
     * transaction in. It survives only to cover the first launch after this ships, when no tip has
     * ever been persisted, and it reproduces the old "1 confirmation" artifact for exactly that one
     * launch. From the first persisted tip onward it is unreachable.
     */
    fun effectiveChainTip(
        nativeTip: Long,
        persistedTip: Long,
        maxTxHeight: Long,
    ): Long {
        val observed = maxOf(sane(nativeTip), sane(persistedTip))
        return if (observed > UNKNOWN_TIP) observed else sane(maxTxHeight)
    }

    /**
     * Confirmations for a transaction at [txHeight] against [chainTip].
     *
     * Returns 0 — rendered "Unconfirmed" — for a pending transaction ([txHeight] of 0, which is
     * how the UI normalises native's TX_UNCONFIRMED sentinel) and for a tip that is not yet known
     * or has not reached the transaction. A tip BELOW a transaction we already hold is not an
     * error worth surfacing: it happens routinely mid-catch-up, and reporting a negative count, or
     * clamping it to 1, would put back the exact wrong number this policy exists to remove.
     */
    fun confirmationsFor(txHeight: Long, chainTip: Long): Int {
        if (txHeight <= 0L || chainTip <= UNKNOWN_TIP) return 0
        if (chainTip < txHeight) return 0
        return (chainTip - txHeight + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Whether [candidate] should replace [stored] as the persisted tip.
     *
     * Guards the two ways a bad value gets in. Heights at or above [Int.MAX_VALUE] are native's
     * TX_UNCONFIRMED sentinel leaking out of a transaction record rather than a real height —
     * persisting one would park the tip at ~2.1 billion and every count would be astronomically
     * wrong until the prefs were cleared. And the tip only ever moves forward, so a lower
     * candidate is a regression (a mid-catch-up native tip, a reorg) and is ignored.
     */
    fun shouldPersistTip(candidate: Long, stored: Long): Boolean =
        sane(candidate) > UNKNOWN_TIP && candidate > stored

    /** 0 for anything that is not a plausible mainnet/testnet block height. */
    private fun sane(height: Long): Long =
        if (height > UNKNOWN_TIP && height < Int.MAX_VALUE.toLong()) height else UNKNOWN_TIP
}
