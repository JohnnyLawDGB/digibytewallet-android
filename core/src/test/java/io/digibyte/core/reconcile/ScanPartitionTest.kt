package io.digibyte.core.reconcile

import io.digibyte.core.asset.DigiAssetDecoder
import io.digibyte.core.asset.DigiAssetEncoder
import io.digibyte.core.asset.HeldOutputFacts
import io.digibyte.core.asset.RemainderProofWalk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Scan for missing funds" partitions what the node returned by the wallet's own classes, and its
 * headline "Spendable" is the wallet's spendable balance by construction.
 *
 * The main fixture has the shape of a real wallet's scan: 12 spendable outputs, 13 asset carriers
 * held for their units, 9 last outputs of asset transfers held because their remainder is unknown,
 * and 12 rows the node listed twice (one address sent twice). Summed raw, that is 46 rows and
 * 430,797,283 sat against a spendable balance of 329,072,900.
 */
class ScanPartitionTest {

    private fun id(tag: String) = tag.padEnd(64, '0')

    private val spendableAmounts = listOf(
        1_000_000L, 2_000_000L, 4_938_200L, 4_984_600L, 5_000_000L, 8_984_600L, 10_000_000L,
        12_692_700L, 29_945_300L, 48_720_617L, 59_725_400L, 141_081_483L,
    )
    private val carrierAmounts = List(9) { 6_000L } + List(4) { 10_000L }
    private val remainderAmounts = listOf(
        3_945_300L, 14_945_300L, 4_945_300L, 4_945_300L, 4_945_300L, 4_945_300L, 6_661L, 6_661L, 6_661L,
    )

    private val spendable = spendableAmounts.mapIndexed { i, sat -> UtxoEntry(id("a" + "%02d".format(i)), 1, sat, "addrA$i", 100L) }
    private val carriers = carrierAmounts.mapIndexed { i, sat -> UtxoEntry(id("b" + "%02d".format(i)), 0, sat, "addrB$i", 100L) }
    private val remainders = remainderAmounts.mapIndexed { i, sat -> UtxoEntry(id("c" + "%02d".format(i)), 2, sat, "addrC$i", 100L) }

    /** The rows the node listed a second time: 5 spendable, 6 carriers, 1 remainder. */
    private val repeated: List<UtxoEntry> =
        listOf(0, 1, 4, 6, 8).map { spendable[it] } +
            listOf(0, 1, 2, 9, 10, 11).map { carriers[it] } +
            listOf(remainders[1].copy(txid = remainders[1].txid.uppercase()))   // same outpoint, other case

    private val nodeRows = spendable + carriers + remainders + repeated

    private val codes: Map<Pair<String, Int>, Int> =
        spendable.associate { outpointKey(it) to WalletOutpointCode.SPENDABLE } +
            carriers.associate { outpointKey(it) to WalletOutpointCode.HELD } +
            remainders.associate { outpointKey(it) to WalletOutpointCode.HELD }

    private val walletBalance = 329_072_900L

    private fun codesFor(rows: List<UtxoEntry>, override: Map<Pair<String, Int>, Int> = emptyMap()) =
        rows.map { override[outpointKey(it)] ?: codes[outpointKey(it)] ?: WalletOutpointCode.NOT_HELD }.toIntArray()

    private val heldKind: suspend (UtxoEntry) -> HeldKind = { u ->
        if (u.txid.lowercase().startsWith("c")) HeldKind.UNKNOWN_REMAINDER else HeldKind.ASSET
    }

    private suspend fun scan(
        rows: List<UtxoEntry> = nodeRows,
        override: Map<Pair<String, Int>, Int> = emptyMap(),
        walletOnly: ClassTotal = ClassTotal(),
    ): ScanPartition {
        val distinct = distinctOutpoints(rows)
        return partitionScan(distinct, codesFor(distinct, override), heldKind, walletOnly, walletBalance)!!
    }

    @Test fun `the fixture adds up to what the node returned`() {
        assertEquals(46, nodeRows.size)
        assertEquals(430_797_283L, nodeRows.sumOf { it.amountSatoshi })
        assertEquals(walletBalance, spendableAmounts.sum())
        assertEquals(62_938_600L, repeated.sumOf { it.amountSatoshi })
    }

    @Test fun `each address is sent to the node once`() {
        val dump = listOf("dgb1qa", "dgb1qb", "dgb1qa", " dgb1qc ", "", "dgb1qb")
        assertEquals(listOf("dgb1qa", "dgb1qb", "dgb1qc"), distinctAddresses(dump))
    }

    @Test fun `each outpoint is counted once, whatever case its id is written in`() {
        val distinct = distinctOutpoints(nodeRows)
        assertEquals(34, distinct.size)
        assertEquals(430_797_283L - 62_938_600L, distinct.sumOf { it.amountSatoshi })
        assertEquals(distinct.size, distinct.map(::outpointKey).toSet().size)
    }

    @Test fun `the headline is the wallet's spendable balance and every other output has a class`() = runTest {
        val p = scan()
        assertEquals(walletBalance, p.spendableSat)
        assertEquals(ClassTotal(12, 329_072_900L), p.total(ScanClass.SPENDABLE))
        assertEquals(ClassTotal(13, 94_000L), p.total(ScanClass.HELD_ASSET))
        assertEquals(ClassTotal(9, 38_691_783L), p.total(ScanClass.HELD_UNKNOWN))
        assertEquals(ClassTotal(0, 0L), p.total(ScanClass.NOT_IN_WALLET))
        assertEquals(0, p.walletOnly.count)
        assertTrue(p.matchesWallet)
        // Nothing counted twice: the classes add up to the distinct rows.
        assertEquals(34, ScanClass.values().sumOf { p.total(it).count })
        assertEquals(329_072_900L + 94_000L + 38_691_783L, ScanClass.values().sumOf { p.total(it).sat })
    }

    @Test fun `only an output the wallet does not credit is missing funds`() = runTest {
        val stranger = UtxoEntry(id("f0"), 0, 25_000_000L, "addrF", 100L)
        val watchedOnly = UtxoEntry(id("f1"), 3, 1_000L, "addrG", 100L)
        val p = scan(
            rows = nodeRows + stranger + watchedOnly,
            override = mapOf(outpointKey(watchedOnly) to WalletOutpointCode.NOT_CREDITED),
        )
        assertEquals(ClassTotal(2, 25_001_000L), p.total(ScanClass.NOT_IN_WALLET))
        assertEquals(walletBalance, p.spendableSat)
        assertFalse(p.matchesWallet)
    }

    @Test fun `DigiDollar, immature and pending outputs are listed, not counted as spendable or missing`() = runTest {
        val dd = UtxoEntry(id("d0"), 0, 0L, "addrD", 100L)
        val mined = UtxoEntry(id("d1"), 0, 72_000_000_000L, "addrE", 100L)
        val pending = UtxoEntry(id("d2"), 1, 5_000L, "addrH", 100L)
        val p = scan(
            rows = nodeRows + dd + mined + pending,
            override = mapOf(
                outpointKey(dd) to WalletOutpointCode.DIGIDOLLAR,
                outpointKey(mined) to WalletOutpointCode.IMMATURE,
                outpointKey(pending) to WalletOutpointCode.SPENT,
            ),
        )
        assertEquals(1, p.total(ScanClass.DIGIDOLLAR).count)
        assertEquals(ClassTotal(1, 72_000_000_000L), p.total(ScanClass.IMMATURE))
        assertEquals(ClassTotal(1, 5_000L), p.total(ScanClass.PENDING))
        assertEquals(0, p.total(ScanClass.NOT_IN_WALLET).count)
        assertTrue(p.matchesWallet)
    }

    @Test fun `a spendable output the node did not report breaks the match`() = runTest {
        val p = scan(walletOnly = walletOnlyOf(longArrayOf(1, 7_000L))!!)
        assertEquals(ClassTotal(1, 7_000L), p.walletOnly)
        assertFalse(p.matchesWallet)
    }

    @Test fun `no wallet to ask, no partition`() = runTest {
        val distinct = distinctOutpoints(nodeRows)
        assertNull(partitionScan(distinct, null, heldKind, ClassTotal(), walletBalance))
        assertNull(partitionScan(distinct, IntArray(distinct.size - 1), heldKind, ClassTotal(), walletBalance))
        assertNull(partitionScan(distinct, codesFor(distinct), heldKind, walletOnlyOf(null), walletBalance))
    }

    @Test fun `a proven remainder moves to plain DGB, the rest stay held`() = runTest {
        val recorded = mutableListOf<String>()
        val p = proveHeldRemainders(
            partition = scan(),
            prove = { u ->
                if (u.amountSatoshi == 6_661L) RemainderProofWalk.Verdict.Held(RemainderProofWalk.Reason.UNAVAILABLE)
                else RemainderProofWalk.Verdict.Proven
            },
            record = { recorded += it },
        )
        assertEquals(ClassTotal(6, 38_691_783L - 3 * 6_661L), p.total(ScanClass.PROVEN_PLAIN))
        assertEquals(ClassTotal(3, 3 * 6_661L), p.total(ScanClass.HELD_UNKNOWN))
        assertEquals(6, recorded.size)
        // Moving a class never changes the headline: it is still what the wallet spends today.
        assertEquals(walletBalance, p.spendableSat)
    }

    // ---- why a held output is held -----------------------------------------------------------

    private val oneToOutputZero = DigiAssetDecoder().decode(
        DigiAssetEncoder.encodeTransferScript(
            3, listOf(DigiAssetEncoder.TransferInstruction(false, false, false, 0, 1L)),
        ),
    )!!

    private fun facts(heldNow: Boolean?, row: Long?) = HeldOutputFacts(
        header = oneToOutputZero, firstNonOpReturnVout = 0, outputCount = 3, heldByRuleNow = heldNow, rowQuantity = row,
    )

    @Test fun `the last output of a transfer is held for an unknown remainder until the remainder is known`() {
        assertEquals(HeldKind.UNKNOWN_REMAINDER, heldKindOf(facts(true, 0L), 2))
        assertEquals(HeldKind.UNKNOWN_REMAINDER, heldKindOf(facts(true, null), 2))
        assertEquals(HeldKind.UNKNOWN_REMAINDER, heldKindOf(facts(null, 0L), 2))  // no answer: still unknown
        assertEquals(HeldKind.PROVEN_ZERO, heldKindOf(facts(false, 0L), 2))
        assertEquals(HeldKind.ASSET, heldKindOf(facts(true, 4L), 2))      // a stored quantity
        assertEquals(HeldKind.ASSET, heldKindOf(facts(false, 0L), 0))     // named by an instruction
        assertEquals(HeldKind.ASSET, heldKindOf(null, 2))                 // unreadable: held for its units
    }

    @Test fun `the wallet's answer about outputs the node did not list is read strictly`() {
        assertEquals(ClassTotal(2, 9_000L), walletOnlyOf(longArrayOf(2, 9_000L)))
        assertNull(walletOnlyOf(null))
        assertNull(walletOnlyOf(longArrayOf(1)))
        assertNull(walletOnlyOf(longArrayOf(-1, 0)))
    }
}
