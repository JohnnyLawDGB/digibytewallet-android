package io.digibyte.service

import io.digibyte.core.OutgoingTxStore.SweepAttempts
import io.digibyte.service.StrandedSendSelector.Action
import io.digibyte.service.StrandedSendSelector.DETAILS_WINDOW
import io.digibyte.service.StrandedSendSelector.HOLD_MS
import io.digibyte.service.StrandedSendSelector.MAX_ATTEMPTS
import io.digibyte.service.StrandedSendSelector.TX_UNCONFIRMED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stranded-send sweep's selection, from the wallet's own transaction list.
 *
 * The list is the [DETAILS_WINDOW] most recent rows, ordered by block height, and an unconfirmed
 * tx carries the greatest height of all — so a full window lists every unconfirmed tx, and a tx
 * that has aged out of it has confirmed (measured on the Note 8, 2026-09-26: a confirmed asset
 * send stood at row 2 of the window, moved to row 1 after the next send, left the window with the
 * one after, and was then re-published by the sweep every 90 s). A send the wallet holds at a
 * confirmed height is never re-published and is settled; an unconfirmed one is re-published a
 * bounded number of times.
 */
class StrandedSendSelectorTest {

    // Rows as the wallet writes them: txHash|amount|fee|blockHeight|timestamp|sent|received.
    private val confirmedAssetSend =
        "c66c80a654e336fcca99d480757ba29ab409fee48bcd4c0f284469cb0f5312b9|-64400|58400|24082399|1787480129|199989400|199925000"
    private val confirmedReceiveWithUnknownFee =
        "35ba64f4b1da5558a913c3084284641729bbe1c9eddeb38b3177bf28fe970ce9|6000|18446744073709551615|24082557|1787482539|0|6000"
    private val laterAssetSend =
        "d3b54030e85680ef24a6565a3e4133593cb733b2f811965c4280971418161784|-64400|58400|24082712|1787484921|199929000|199864600"
    private val unconfirmedSend =
        "f828f650bb3269e2f1552c5a38fe88df72c6cfd806f523f266d2b5c9f29246dd|-100000|1000|$TX_UNCONFIRMED|1790000000|200000000|199899000"

    private val confirmedTxid = confirmedAssetSend.substringBefore('|')
    private val unconfirmedTxid = unconfirmedSend.substringBefore('|')
    private val absentTxid = "0000000000000000000000000000000000000000000000000000000000000abc"

    private val now = 1_790_000_000_000L
    private fun noAttempts(): (String) -> SweepAttempts = { SweepAttempts.NONE }
    private fun attempts(count: Int, lastMs: Long): (String) -> SweepAttempts = { SweepAttempts(count, lastMs) }

    /** A window of [rows] rows, all confirmed, the given rows first. */
    private fun window(rows: Int, vararg first: String): String {
        val filler = (first.size until rows).map { i ->
            "%064x|1000|100|%d|1787000000|0|1000".format(i + 1, 24_000_000 + i)
        }
        return (first.toList() + filler).joinToString("\n", postfix = "\n")
    }

    private fun decision(decisions: List<StrandedSendSelector.Decision>, txid: String) =
        decisions.singleOrNull { it.txid == txid } ?: error("no decision for $txid in $decisions")

    @Test fun `the exact confirmed asset-send row parses as confirmed at its height`() {
        val details = StrandedSendSelector.parseDetails(
            listOf(confirmedAssetSend, confirmedReceiveWithUnknownFee, laterAssetSend, unconfirmedSend).joinToString("\n")
        )
        assertEquals(4, details.rows)
        assertEquals(24_082_399L, details.heights[confirmedTxid])
        assertEquals(24_082_557L, details.heights[confirmedReceiveWithUnknownFee.substringBefore('|')])
        assertEquals(TX_UNCONFIRMED, details.heights[unconfirmedTxid])
    }

    @Test fun `a send listed at a confirmed height is settled, not re-published`() {
        val decisions = StrandedSendSelector.select(
            setOf(confirmedTxid, unconfirmedTxid),
            window(10, confirmedAssetSend, unconfirmedSend),
            noAttempts(), now,
        )
        val confirmed = decision(decisions, confirmedTxid)
        assertEquals(Action.SETTLE_CONFIRMED, confirmed.action)
        assertEquals(24_082_399L, confirmed.height)
        assertTrue(confirmed.settles)
        assertEquals(listOf(unconfirmedTxid), decisions.filter { it.action == Action.REPUBLISH }.map { it.txid })
    }

    @Test fun `a send listed at the unconfirmed sentinel is re-published`() {
        val decisions = StrandedSendSelector.select(setOf(unconfirmedTxid), window(10, unconfirmedSend), noAttempts(), now)
        assertEquals(Action.REPUBLISH, decision(decisions, unconfirmedTxid).action)
    }

    @Test fun `a send absent from a full window has aged out of it and is settled`() {
        // The window lists every unconfirmed tx; a recorded send not in a full window is older
        // than a hundred confirmed rows, and so confirmed itself. Zero re-publishes.
        val decisions = StrandedSendSelector.select(
            setOf(confirmedTxid),
            window(DETAILS_WINDOW, confirmedReceiveWithUnknownFee, laterAssetSend),
            noAttempts(), now,
        )
        val aged = decision(decisions, confirmedTxid)
        assertEquals(Action.SETTLE_AGED_OUT, aged.action)
        assertTrue(aged.settles)
        assertNull(aged.height)
    }

    @Test fun `a send absent from a window that is not full is tried a bounded number of times`() {
        val details = window(10, laterAssetSend)
        for (count in 0 until MAX_ATTEMPTS) {
            val d = decision(StrandedSendSelector.select(setOf(absentTxid), details, attempts(count, now - 90_000L), now), absentTxid)
            assertEquals("attempt $count", Action.REPUBLISH, d.action)
        }
        val d = decision(StrandedSendSelector.select(setOf(absentTxid), details, attempts(MAX_ATTEMPTS, now - 90_000L), now), absentTxid)
        assertEquals(Action.SETTLE_GAVE_UP, d.action)
        assertTrue(d.settles)
    }

    @Test fun `an unconfirmed send is held after the attempt cap and tried again after the hold`() {
        val details = window(10, unconfirmedSend)
        val capped = attempts(MAX_ATTEMPTS, now - 90_000L)
        assertEquals(Action.HOLD, decision(StrandedSendSelector.select(setOf(unconfirmedTxid), details, capped, now), unconfirmedTxid).action)
        val holdOver = attempts(MAX_ATTEMPTS, now - HOLD_MS)
        assertEquals(Action.REPUBLISH, decision(StrandedSendSelector.select(setOf(unconfirmedTxid), details, holdOver, now), unconfirmedTxid).action)
        // A hold is never a settlement: the send is still pending.
        assertTrue(StrandedSendSelector.select(setOf(unconfirmedTxid), details, capped, now).none { it.settles })
    }

    @Test fun `a malformed row neither throws nor makes the other rows unconfirmed`() {
        val malformedTxid = "1111111111111111111111111111111111111111111111111111111111111111"
        val text = listOf(
            confirmedAssetSend,
            "$malformedTxid|-1|",                    // truncated: no height field
            "2222|abc|def|not-a-height|0|0|0",       // non-numeric height
            "",                                      // blank line
            "|||24000001|0|0|0",                     // empty txid
            unconfirmedSend,
        ).joinToString("\n")
        val decisions = StrandedSendSelector.select(
            setOf(confirmedTxid, malformedTxid, unconfirmedTxid), text, noAttempts(), now,
        )
        assertEquals(Action.SETTLE_CONFIRMED, decision(decisions, confirmedTxid).action)
        assertEquals(Action.REPUBLISH, decision(decisions, unconfirmedTxid).action)
        // A row whose height cannot be read is not a confirmed height; the send is tried, bounded,
        // and then held like any unconfirmed send — the wallet does hold it, so it is never given up.
        assertEquals(Action.REPUBLISH, decision(decisions, malformedTxid).action)
        assertEquals(
            Action.HOLD,
            decision(StrandedSendSelector.select(setOf(malformedTxid), text, attempts(MAX_ATTEMPTS, now), now), malformedTxid).action,
        )
    }

    @Test fun `a blank details text decides nothing — nothing tried, settled or counted`() {
        for (blank in listOf("", "   ", "\n\n")) {
            assertEquals(blank.length.toString(), emptyList<StrandedSendSelector.Decision>(),
                StrandedSendSelector.select(setOf(absentTxid, confirmedTxid), blank, attempts(MAX_ATTEMPTS, now), now))
        }
    }

    /** A window of [rows] rows, all at the unconfirmed sentinel, the given rows first. */
    private fun unconfirmedWindow(rows: Int, vararg first: String): String {
        val filler = (first.size until rows).map { i ->
            "%064x|-1000|100|%d|1790000000|2000|1000".format(0x1000 + i, TX_UNCONFIRMED)
        }
        return (first.toList() + filler).joinToString("\n", postfix = "\n")
    }

    @Test fun `a send absent from a full window of unconfirmed rows is re-published, not aged out`() {
        // Every row unconfirmed says nothing about what fell out: an unconfirmed parent of those
        // rows is older than all of them. Such a window is not read as full.
        val details = unconfirmedWindow(DETAILS_WINDOW)
        assertEquals(DETAILS_WINDOW, StrandedSendSelector.parseDetails(details).rows)
        assertEquals(0, StrandedSendSelector.parseDetails(details).confirmedRows)
        val d = decision(StrandedSendSelector.select(setOf(absentTxid), details, noAttempts(), now), absentTxid)
        assertEquals(Action.REPUBLISH, d.action)
        // Still bounded like any send the window does not list.
        assertEquals(
            Action.SETTLE_GAVE_UP,
            decision(StrandedSendSelector.select(setOf(absentTxid), details, attempts(MAX_ATTEMPTS, now), now), absentTxid).action,
        )
    }

    @Test fun `one confirmed row anchors a full window, and an absent send is aged out`() {
        val details = unconfirmedWindow(DETAILS_WINDOW, laterAssetSend)
        assertEquals(1, StrandedSendSelector.parseDetails(details).confirmedRows)
        val d = decision(StrandedSendSelector.select(setOf(absentTxid), details, noAttempts(), now), absentTxid)
        assertEquals(Action.SETTLE_AGED_OUT, d.action)
    }

    @Test fun `decisions come back in txid order, one per recorded send`() {
        val decisions = StrandedSendSelector.select(
            setOf(unconfirmedTxid, confirmedTxid, absentTxid), window(10, confirmedAssetSend, unconfirmedSend), noAttempts(), now,
        )
        assertEquals(listOf(absentTxid, confirmedTxid, unconfirmedTxid), decisions.map { it.txid })
    }
}
