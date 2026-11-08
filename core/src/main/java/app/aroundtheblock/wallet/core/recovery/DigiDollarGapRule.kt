package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.bridge.NativeBridge

/**
 * How far the DigiDollar recovery scan derives at m/86'/20'/0', and when its answer is finished
 * (4.1.2 row 19). The rule is the core's (the DigiDollar half of BRRecoveryGapWindow.h), which iOS
 * calls too. It is the plain-DGB rule ([RecoveryGapRule]) with DigiDollar's own gaps and cap: each
 * chain starts at its gap (20 external, 10 internal) and, while an address within that gap of the
 * window's end has been used, grows to the gap past the last use, by at least a whole gap, up to
 * 1000 addresses, for at most [roundBound] rounds; a scan the cap or the round bound stopped is
 * capped, never a finished answer. Until 2026-11 it was Kotlin (DigiDollarGapWindow, the gaps in
 * [RecoveryScanService] and the stop decision in its scanDigiDollar), which grew a window only to a
 * gap past the last use and had no round bound.
 *
 * Kotlin's null crosses as -1 (a real index is never negative). The outcome values are
 * [RecoveryGapRule.ASK_AGAIN] and [RecoveryGapRule.FINISHED]; anything else is capped. The rule's
 * values are checked by the host KAT digidollar_whole_gap_kat.
 */
object DigiDollarGapRule {
    /** The window [chain] (0 external, 1 internal) starts at. */
    fun start(chain: Int): Int = NativeBridge.recoveryDigiDollarGapStart(chain)

    /** The window [count] of [chain] grows to, given [highestUsed]. */
    fun next(chain: Int, count: Int, highestUsed: Int?): Int =
        NativeBridge.recoveryDigiDollarWholeGapNext(chain, count, highestUsed ?: -1)

    /** [RecoveryGapRule.ASK_AGAIN], [RecoveryGapRule.FINISHED], or anything else: capped, on
     *  [round] (from 1) of at most [bound] ([roundBound]). */
    fun outcome(
        externalCount: Int, internalCount: Int, nextExternal: Int, nextInternal: Int,
        usedExternal: Int?, usedInternal: Int?, round: Int, bound: Int,
    ): Int = NativeBridge.recoveryDigiDollarWholeGapOutcome(
        externalCount, internalCount, nextExternal, nextInternal, usedExternal ?: -1, usedInternal ?: -1,
        round, bound,
    )

    /** The scan's round bound: more rounds than its windows can take to reach the cap. */
    fun roundBound(): Int = NativeBridge.recoveryDigiDollarGapRoundBound()
}
