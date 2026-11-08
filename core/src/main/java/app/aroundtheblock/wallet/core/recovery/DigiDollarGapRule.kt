package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.bridge.NativeBridge

/**
 * How far the DigiDollar recovery scan derives at m/86'/20'/0', and when its answer is finished
 * (4.1.2 row 19). The rule is the core's (the DigiDollar half of BRRecoveryGapWindow.h), which iOS
 * calls too: each chain starts at its gap (20 external, 10 internal) and, while an address within
 * that gap of the window's end has been used, grows to the gap past the last use, up to 1000
 * addresses; a scan the cap stopped is capped, never a finished answer. Until 2026-11 it was Kotlin
 * (DigiDollarGapWindow, the gaps in [RecoveryScanService] and the stop decision in its
 * scanDigiDollar).
 *
 * Kotlin's null crosses as -1 (a real index is never negative). The outcome values are
 * [RecoveryGapRule.ASK_AGAIN] and [RecoveryGapRule.FINISHED]; anything else is capped. The rule's
 * values are checked by the host KAT digidollar_gap_window_kat.
 */
object DigiDollarGapRule {
    /** The window [chain] (0 external, 1 internal) starts at. */
    fun start(chain: Int): Int = NativeBridge.recoveryDigiDollarGapStart(chain)

    /** The window [count] of [chain] grows to, given [highestUsed]. */
    fun next(chain: Int, count: Int, highestUsed: Int?): Int =
        NativeBridge.recoveryDigiDollarGapNext(chain, count, highestUsed ?: -1)

    /** [RecoveryGapRule.ASK_AGAIN], [RecoveryGapRule.FINISHED], or anything else: capped. */
    fun outcome(
        externalCount: Int, internalCount: Int, nextExternal: Int, nextInternal: Int,
        usedExternal: Int?, usedInternal: Int?,
    ): Int = NativeBridge.recoveryDigiDollarGapOutcome(
        externalCount, internalCount, nextExternal, nextInternal, usedExternal ?: -1, usedInternal ?: -1,
    )
}
