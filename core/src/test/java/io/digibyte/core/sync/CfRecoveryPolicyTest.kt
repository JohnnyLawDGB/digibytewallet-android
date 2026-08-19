package io.digibyte.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a compact-filter recovery is allowed to destroy.
 *
 * The BIP158 watchdog branches deleted the filter-header chain AND the CF scan ledger
 * together. Those record different things:
 *
 *  - the filter-header CHAIN is BIP158 header data, which really can diverge or corrupt,
 *    and re-fetching it is the recovery;
 *  - the scan LEDGER records which block ranges this wallet has already scanned for its own
 *    watch set. A wedged or diverged filter chain says nothing about whether those ranges
 *    were scanned.
 *
 * The ledger is what makes a restart resume near tip (`cf-ledger: restored … resume cursor
 * snap 24052400 -> 24052508`). Deleting it during a routine stall is what turned a recovery
 * into ~6 hours of re-scanning 1.4M blocks on a Note 8.
 */
class CfRecoveryPolicyTest {

    /** A wedged filter chain is a filter-chain problem. The scan record stays. */
    @Test fun a_wedged_filter_chain_drops_the_chain_but_keeps_the_scan_ledger() {
        val d = CfRecoveryPolicy.decide(CfRecoveryPolicy.Reason.FILTER_CHAIN_WEDGED)
        assertEquals(true, d.dropFilterChain)
        assertEquals(false, d.dropScanLedger)
    }

    /** Re-anchoring rebuilds the chain from a floor; the scanned ranges are still true. */
    @Test fun a_reanchor_drops_the_chain_but_keeps_the_scan_ledger() {
        val d = CfRecoveryPolicy.decide(CfRecoveryPolicy.Reason.REANCHORED)
        assertEquals(true, d.dropFilterChain)
        assertEquals(false, d.dropScanLedger)
    }

    /** A chain that stayed wedged THROUGH a re-anchor is the genuine-corruption signal the
     *  plan asks for — that one may take everything. */
    @Test fun a_chain_still_wedged_after_reanchor_may_drop_both() {
        val d = CfRecoveryPolicy.decide(CfRecoveryPolicy.Reason.FILTER_CHAIN_CORRUPT)
        assertEquals(true, d.dropFilterChain)
        assertEquals(true, d.dropScanLedger)
    }

    /** The ledger blob itself failing to decode is the only ledger-specific corruption. */
    @Test fun a_corrupt_ledger_blob_drops_the_ledger() {
        val d = CfRecoveryPolicy.decide(CfRecoveryPolicy.Reason.SCAN_LEDGER_CORRUPT)
        assertEquals(true, d.dropScanLedger)
    }

    /** An explicit reset means what it says. */
    @Test fun a_wallet_reset_drops_everything() {
        val d = CfRecoveryPolicy.decide(CfRecoveryPolicy.Reason.WALLET_RESET)
        assertEquals(true, d.dropFilterChain)
        assertEquals(true, d.dropScanLedger)
    }

    /** The property that matters, stated once: only a reason that implicates the LEDGER may
     *  destroy the resume point. */
    @Test fun only_ledger_implicating_reasons_destroy_the_resume_point() {
        val destroys = CfRecoveryPolicy.Reason.values()
            .filter { CfRecoveryPolicy.decide(it).dropScanLedger }
            .toSet()
        assertEquals(
            setOf(
                CfRecoveryPolicy.Reason.SCAN_LEDGER_CORRUPT,
                CfRecoveryPolicy.Reason.FILTER_CHAIN_CORRUPT,
                CfRecoveryPolicy.Reason.WALLET_RESET,
            ),
            destroys,
        )
    }
}
