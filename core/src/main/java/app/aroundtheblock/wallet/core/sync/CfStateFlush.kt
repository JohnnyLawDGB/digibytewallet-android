package app.aroundtheblock.wallet.core.sync

/**
 * One flush of the compact-filter state the sync service holds in memory: the filter-header chain
 * ([FilterHeaderStore]) and the scan ledger ([CfScanLedgerStore]), the ledger only behind the
 * transaction-checkpoint gate (the frontier never moves past history that was not saved).
 *
 * STORE-DURABILITY: the store writes report whether they landed and keep the previous file when they
 * do not, so a caller must not treat a failed write as done. The service's coalesced writer and its
 * synchronous flush (teardown, pre-recreate, sync completion) both run this one copy, and re-mark
 * their state dirty when [Outcome.allLanded] is false, so the next tick retries.
 */
object CfStateFlush {
    data class Outcome(val headersLanded: Boolean, val ledgerLanded: Boolean) {
        val allLanded: Boolean get() = headersLanded && ledgerLanded

        companion object {
            /** Nothing was pending, or nothing may be written (a wipe stood the service down). */
            val NOTHING = Outcome(headersLanded = true, ledgerLanded = true)
        }
    }

    /**
     * [writeHeaders] / [writeLedger] are null when nothing of that kind is pending. [checkpointGate] is
     * asked only when a ledger is pending, and a shut gate counts as the ledger not landing.
     */
    fun flush(
        writeHeaders: (() -> Boolean)?,
        writeLedger: (() -> Boolean)?,
        checkpointGate: () -> Boolean,
    ): Outcome {
        val headers = writeHeaders?.let { runCatching(it).getOrDefault(false) } ?: true
        val ledger = writeLedger?.let { write ->
            runCatching { checkpointGate() && write() }.getOrDefault(false)
        } ?: true
        return Outcome(headers, ledger)
    }
}
