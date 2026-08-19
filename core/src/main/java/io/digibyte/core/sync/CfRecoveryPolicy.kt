package io.digibyte.core.sync

/**
 * What a compact-filter recovery is allowed to destroy.
 *
 * The BIP158 watchdog branches used to delete the filter-header chain and the CF scan
 * ledger together, on every recovery. Those record different things:
 *
 *  - the filter-header CHAIN is BIP158 header data. It really can diverge from the block
 *    chain or corrupt, and re-fetching it IS the recovery;
 *  - the scan LEDGER records which block ranges this wallet has already scanned against its
 *    own watch set. A wedged or diverged filter chain says nothing about whether those
 *    ranges were scanned.
 *
 * The ledger is what lets a restart resume near tip rather than at the birth floor. Deleting
 * it during a routine stall is what turned a recovery into ~6 hours of re-scanning 1.4M
 * blocks on a Note 8 — the wallet re-derived work it had already done and had a correct
 * record of.
 *
 * So: recovery may drop the chain freely, and may drop the ledger only for a reason that
 * actually implicates the ledger.
 */
object CfRecoveryPolicy {

    enum class Reason {
        /** cfTip stuck at the network max while the block tip climbs. A stall, not proof of
         *  anything about the scan record. */
        FILTER_CHAIN_WEDGED,

        /** The chain was re-anchored at a floor; the persisted copy must go so a kill before
         *  the first re-anchored append cannot restore the stuck tip. */
        REANCHORED,

        /** Still wedged AFTER a re-anchor — the persisted chain is not merely stale, and the
         *  scan record derived alongside it is no longer trustworthy either. */
        FILTER_CHAIN_CORRUPT,

        /** The ledger blob itself failed to decode. */
        SCAN_LEDGER_CORRUPT,

        /** Explicit reset: wipe, restore, or the startup crash-loop breaker. */
        WALLET_RESET,
    }

    data class Decision(val dropFilterChain: Boolean, val dropScanLedger: Boolean)

    fun decide(reason: Reason): Decision = when (reason) {
        Reason.FILTER_CHAIN_WEDGED -> Decision(dropFilterChain = true, dropScanLedger = false)
        Reason.REANCHORED -> Decision(dropFilterChain = true, dropScanLedger = false)
        Reason.FILTER_CHAIN_CORRUPT -> Decision(dropFilterChain = true, dropScanLedger = true)
        Reason.SCAN_LEDGER_CORRUPT -> Decision(dropFilterChain = false, dropScanLedger = true)
        Reason.WALLET_RESET -> Decision(dropFilterChain = true, dropScanLedger = true)
    }
}
