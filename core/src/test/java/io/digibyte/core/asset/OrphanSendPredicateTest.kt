package io.digibyte.core.asset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An unconfirmed send whose parent the wallet no longer has is an **orphan**: it spends an
 * output that, as far as anything outside this device is concerned, does not exist. It can
 * never confirm, and no peer will ever accept it.
 *
 * Observed live on 2026-08-20 (S25 Ultra, v4.0.39): an asset transfer sat "Pending" for
 * eleven minutes, was re-published on restart, reported `relays=6`, and never reached the
 * mempool of a node with `txindex` synced. Its parent did not exist on chain and its raw
 * bytes were not in the wallet — `M3 walk[1]: no raw for 79b27063… — stop`.
 *
 * Every existing recovery path missed it:
 *  - [DeadSendPredicate] said keep — BRWallet reports it VALID (local state only) and its
 *    6000-sat marker output is above [DeadSendPredicate.DUST_FLOOR].
 *  - `rebroadcastStrandedSends` re-published it forever, because publishing "succeeds".
 *  - Only `rebuildFromChainRescan` could clear it, which costs a full deep re-sync.
 *
 * The likely origin is the wallet's own cleanup: removing a stuck send does not remove the
 * transactions built on top of it, so clearing a phantom parent strands its children.
 */
class OrphanSendPredicateTest {

    @Test fun a_send_whose_parent_is_missing_from_the_wallet_is_an_orphan() {
        assertTrue(
            OrphanSendPredicate.isOrphan(
                inputs = listOf(OrphanSendPredicate.Input(prevTxid = "79b27063", prevVout = 2)),
                walletTxids = setOf("623ba8b5"), // the child itself, but not its parent
            )
        )
    }

    /** The ordinary case: spending a UTXO from a transaction the wallet holds. */
    @Test fun a_send_whose_parents_are_all_known_is_not_an_orphan() {
        assertFalse(
            OrphanSendPredicate.isOrphan(
                inputs = listOf(
                    OrphanSendPredicate.Input("aaaa", 0),
                    OrphanSendPredicate.Input("bbbb", 1),
                ),
                walletTxids = setOf("aaaa", "bbbb", "cccc"),
            )
        )
    }

    /** One bad parent is enough — the transaction cannot confirm without all of its inputs. */
    @Test fun a_single_missing_parent_among_several_is_still_an_orphan() {
        assertTrue(
            OrphanSendPredicate.isOrphan(
                inputs = listOf(
                    OrphanSendPredicate.Input("aaaa", 0),
                    OrphanSendPredicate.Input("missing", 1),
                ),
                walletTxids = setOf("aaaa"),
            )
        )
    }

    /**
     * Fail SAFE, in the direction that costs nothing. If the inputs could not be read the
     * wallet knows nothing about this transaction's parents, and destroying a send on an
     * absence of evidence is the wrong default — a genuinely-broadcast transaction would be
     * removed from the wallet's view while it is still live on the network.
     */
    @Test fun an_unreadable_input_list_is_not_treated_as_an_orphan() {
        assertFalse(OrphanSendPredicate.isOrphan(inputs = emptyList(), walletTxids = setOf("aaaa")))
    }

    /** Comparison is case-insensitive: txid hex reaches this from two different sources. */
    @Test fun parent_matching_ignores_hex_case() {
        assertFalse(
            OrphanSendPredicate.isOrphan(
                inputs = listOf(OrphanSendPredicate.Input("AABBCC", 0)),
                walletTxids = setOf("aabbcc"),
            )
        )
    }

    /** The JNI hands inputs back as "prevTxidHex|prevVout"; a malformed row must not parse
     *  into a bogus parent that then looks missing and condemns a healthy send. */
    @Test fun malformed_input_rows_are_dropped_rather_than_parsed_into_fake_parents() {
        assertTrue(OrphanSendPredicate.parseInputs(arrayOf("aabbcc|0")).size == 1)
        assertTrue(OrphanSendPredicate.parseInputs(arrayOf("garbage")).isEmpty())
        assertTrue(OrphanSendPredicate.parseInputs(arrayOf("aabbcc|notanumber")).isEmpty())
        assertTrue(OrphanSendPredicate.parseInputs(arrayOf("")).isEmpty())
    }
}
