package io.digibyte.core.asset

/**
 * The tri-state — now four-state — answer [io.digibyte.core.bridge.NativeBridge.outpointSpentState]
 * gives for an asset outpoint, read from the sovereign native wallet.
 *
 * [CONFLICTED] is the one that is not about spending at all. A send that gets stuck and is
 * re-sent leaves the abandoned attempt in the native transaction set with its input spent
 * by the replacement. Nothing ever spends the abandoned attempt's own change output, so a
 * spentOutputs lookup answers "unspent" forever and the wallet counts that change a second
 * time. `BRWalletTransactionIsValid` is what separates the two.
 */
object AssetSpentState {
    /** The outpoint is in the wallet's spentOutputs set. */
    const val SPENT = 0

    /** The wallet knows the funding tx, the tx is valid, and the outpoint is unspent. */
    const val HELD = 1

    /** The wallet has no record of the funding tx — e.g. a below-scan-floor holding the SPV
     *  sync hasn't reached. Ambiguous; provenance decides. */
    const val UNDETECTED = -1

    /** The funding tx is known but INVALID: another transaction spent its inputs. Its
     *  outputs will never exist on-chain, so they are never held — whatever their
     *  provenance — but nothing spent them either, so the persisted spent flag is left
     *  alone in case the conflict resolves the other way. */
    const val CONFLICTED = -2

    /** The probe itself failed (JNI threw). Treated as [UNDETECTED]. */
    const val PROBE_ERROR = -99
}
