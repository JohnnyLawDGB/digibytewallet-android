package app.aroundtheblock.wallet.core.sync

/**
 * One flush of the compact-filter state the sync service holds in memory: the scan ledger
 * ([CfScanLedgerStore], behind the transaction-checkpoint gate: the frontier never moves past history
 * that was not saved), then whatever the caller writes in between ([flush]'s `beforeHeaders`, the
 * block window on teardown and before a recreate), then the filter-header chain ([FilterHeaderStore]).
 * Most valuable and smallest first: if a bounded teardown flush runs out of time, it is the
 * multi-MB header chain (a restore optimization) that is left one interval stale.
 *
 * STORE-DURABILITY: a store write answers [StoreWrite]. Only [StoreWrite.FAILED] means the latest
 * state is not on disk; the service's coalesced writer and its synchronous flush both run this one
 * copy and re-mark their state dirty only then. A snapshot a delete made stale ([StoreWrite.OBSOLETE])
 * is not a failure: it is not retried, and the checkpoint gate (an fsync'd write of its own) is not
 * run for a ledger already known to be stale.
 */
object CfStateFlush {
    /** A pending ledger: whether a delete has made it stale since it was captured, and its write. */
    class PendingLedger(val obsolete: () -> Boolean, val write: () -> StoreWrite)

    data class Outcome(val ledger: StoreWrite, val headers: StoreWrite) {
        val ledgerLanded: Boolean get() = !ledger.failed
        val allLanded: Boolean get() = !ledger.failed && !headers.failed

        companion object {
            /** Nothing was pending, or nothing may be written (a wipe stood the service down). */
            val NOTHING = Outcome(StoreWrite.NONE, StoreWrite.NONE)
        }
    }

    /**
     * [ledger] / [headers] are null when nothing of that kind is pending. [checkpointGate] is asked
     * only for a pending ledger that is not already stale; a shut gate counts as the ledger failing.
     * [beforeHeaders] runs after the ledger and before the headers, whatever is pending.
     */
    fun flush(
        ledger: PendingLedger?,
        checkpointGate: () -> Boolean,
        headers: (() -> StoreWrite)?,
        beforeHeaders: () -> Unit = {},
    ): Outcome {
        val ledgerResult = when {
            ledger == null -> StoreWrite.NONE
            runCatching(ledger.obsolete).getOrDefault(false) -> StoreWrite.OBSOLETE
            else -> runCatching { if (checkpointGate()) ledger.write() else StoreWrite.FAILED }.getOrDefault(StoreWrite.FAILED)
        }
        runCatching(beforeHeaders)
        val headersResult = headers?.let { runCatching(it).getOrDefault(StoreWrite.FAILED) } ?: StoreWrite.NONE
        return Outcome(ledgerResult, headersResult)
    }
}
