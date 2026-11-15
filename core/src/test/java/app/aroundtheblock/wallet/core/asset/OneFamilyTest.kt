package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.asset.send.AssetSendSelection
import app.aroundtheblock.wallet.core.asset.send.AssetStackSource
import app.aroundtheblock.wallet.core.asset.send.DA_MARKER_SATS
import app.aroundtheblock.wallet.core.asset.send.StackEntry
import app.aroundtheblock.wallet.core.asset.send.StackLookup
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import app.aroundtheblock.wallet.core.reconcile.DigiDollarHoldingResult
import app.aroundtheblock.wallet.core.reconcile.UtxoEntry
import app.aroundtheblock.wallet.core.recovery.DigiDollarAddress
import app.aroundtheblock.wallet.core.recovery.DigiDollarHolding
import app.aroundtheblock.wallet.core.recovery.DigiDollarScan
import app.aroundtheblock.wallet.core.recovery.ForeignUtxoAssetClassifier
import app.aroundtheblock.wallet.core.recovery.SweepPartition
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ONE-FAMILY: an output belongs to at most one token family. A DigiDollar output is never an asset
 * row, an asset input, a fee coin or a swept coin; a transaction marked both DigiDollar and DigiAsset
 * is credited to neither and offered to no send at all. The native half is host KAT one_family_kat.
 */
class OneFamilyTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val logged = mutableListOf<String>()

    private val ours = "0014" + "11".repeat(20)
    private val oursBytes = ours.hexBytes()
    private val ddToken = "5120" + "22".repeat(32)
    /** "DD" type 2 [5000 cents], with an OP_NOP after it: DigiDollar's reader passes over the OP_NOP,
     *  DigiByte Core's Solver reads the script as nonstandard (not nulldata). */
    private val ddOpReturnNonNull = "6a02444401020288" + "1361"
    private val ddOpReturn = "6a024444010202" + "8813"
    /** DigiAsset transfer: 10 units to output 0. */
    private val daCarrier = "6a0644410115000a"

    private fun id(tag: String) = tag.padEnd(64, '0')
    private val funding = HashMap<String, Array<String>>()

    /** The dual-family transaction: a DD token at 0, the DD OP_RETURN, the DA carrier naming 0, and
     *  1 DGB to us. The one carrier rule reads it as CARRIER:2, so DA-014-REF alone credits output 0. */
    private val dual = arrayOf("0|0|$ddToken", "1|0|$ddOpReturnNonNull", "2|0|$daCarrier", "3|100000000|$ours")
    private val ordinaryDd = arrayOf("0|0|$ddToken", "1|0|$ddOpReturn", "2|5000000|$ours")
    private val ordinaryAsset = arrayOf("0|700|$ours", "1|0|$daCarrier")

    private fun assetRow(txid: String, vout: Int, qty: Long, script: ByteArray = oursBytes) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = script, satoshis = 700L, blockHeight = 700_000L,
        isAsset = true, assetId = "La1", assetQuantity = qty, assetSource = AssetSource.NATIVE,
        assetCredit = AssetCredit.VERIFIED,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private suspend fun balances(vararg rows: UtxoEntity, state: Int = AssetSpentState.HELD): Map<String, AssetManager.HeldBalance> {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns rows.toList()
        return mgr.computeHeldAssetBalancesImpl(setOf(ours, ddToken), spentState = { _, _ -> state }, fundingOutputLines = { funding[it] })!!
    }

    // ── the count rule ────────────────────────────────────────────────────────────────────────

    @Test fun `setup - the dual-family transaction is a CARRIER under the one carrier rule`() {
        val scripts = DigiAssetCarrierRule.scriptsOfLines(dual)!!
        val v = DigiAssetCarrierRule.verdict(scripts, DigiAssetDecoder())
        assertTrue("the case must be live under DA-014-REF alone: $v", v is DigiAssetCarrierRule.Verdict.Carrier)
    }

    /** RED without the ONE-FAMILY count check: even with native answering HELD, the row is dropped. */
    @Test fun `a row on a dual-family transaction is not counted`() = runTest {
        val tx = id("d1")
        funding[tx] = dual
        assertNull("a dual-family row was counted", balances(assetRow(tx, 0, 10L, ddToken.hexBytes()))["La1"])
        assertTrue("the drop is not logged with its reason: $logged", logged.any { it.contains("carrier:onefamily-digidollar") })
    }

    /** Native's OTHER_FAMILY answer is never a holding. */
    @Test fun `native OTHER_FAMILY is not counted`() = runTest {
        val tx = id("d2")
        funding[tx] = ordinaryAsset
        assertNull(balances(assetRow(tx, 0, 10L), state = AssetSpentState.OTHER_FAMILY)["La1"])
        assertFalse(mgr.isHeldForDisplay(ours, setOf(ours), AssetSource.NATIVE, AssetSpentState.OTHER_FAMILY))
    }

    /** GUARD: an ordinary asset row still counts. */
    @Test fun `an ordinary asset row still counts`() = runTest {
        val tx = id("a1")
        funding[tx] = ordinaryAsset
        assertEquals(10L, balances(assetRow(tx, 0, 10L))["La1"]?.quantity)
    }

    // ── OneFamily.outputHeld, the per-outpoint answer the selection uses ──────────────────────

    @Test fun `every output of a dual-family transaction is held, a DD output is held, an asset output is not`() {
        val d = DigiAssetDecoder()
        for (v in 0..3) assertEquals("dual output $v", true, OneFamily.outputHeld(dual, v, d))
        assertEquals("ordinary DD token", true, OneFamily.outputHeld(ordinaryDd, 0, d))
        assertEquals("DGB change of an ordinary DD transfer", false, OneFamily.outputHeld(ordinaryDd, 2, d))
        assertEquals("ordinary asset output", false, OneFamily.outputHeld(ordinaryAsset, 0, d))
        assertEquals("the nVersion marker alone marks a token output", true,
            OneFamily.outputHeld(arrayOf("0|0|$ddToken", "1|0|6a04deadbeef"), 0, d, ddMarker = true))
        assertNull("unreadable", OneFamily.outputHeld(arrayOf("garbage"), 0, d))
    }

    // ── the asset send's selection ────────────────────────────────────────────────────────────

    private val asset = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch"
    private fun sendRow(txid: String, qty: Long) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = byteArrayOf(1, 1), satoshis = DA_MARKER_SATS, blockHeight = 1L,
        isAsset = true, assetId = asset, assetQuantity = qty,
    )
    private fun feeCoin(txid: String) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = byteArrayOf(1, 1), satoshis = 1_000_000L, blockHeight = 1L,
    )
    private val everythingHoldsTheAsset = object : AssetStackSource {
        override suspend fun stackOf(txid: String, vout: Int) = StackLookup.Found(listOf(StackEntry(asset, 5)))
    }
    private fun select(rows: List<UtxoEntity>, fees: List<UtxoEntity>, family: (String, Int) -> Boolean?) = runBlocking {
        AssetSendSelection(everythingHoldsTheAsset, { false }, family).choose(asset, rows, fees, 3, 100_000L)
    }

    /** RED without the selection filter: the indexer vouches for the DD output, and it is planned. */
    @Test fun `a DigiDollar output is never offered to an asset send`() {
        val out = select(listOf(sendRow("dd", 5)), listOf(feeCoin("f"))) { txid, _ -> txid == "dd" }
        assertTrue("a DD output was offered: $out", out is AssetSendSelection.Outcome.NotPlanned)
    }

    /** RED without the selection filter: only the good coin is spent. */
    @Test fun `a dual-family coin is passed over for an ordinary one`() {
        val family: (String, Int) -> Boolean? = { txid, v -> OneFamily.outputHeld(if (txid == "dual") dual else ordinaryAsset, v, DigiAssetDecoder()) }
        val out = select(listOf(sendRow("dual", 5), sendRow("good", 5)), listOf(feeCoin("f")), family)
        assertTrue(out is AssetSendSelection.Outcome.Planned)
        val inputs = (out as AssetSendSelection.Outcome.Planned).plan.assetInputs.map { it.txid }
        assertEquals(listOf("good"), inputs)
    }

    /** RED without the selection filter: a fee coin that is DD or dual-family is never a fee input,
     *  and a coin that cannot be judged is not offered either. */
    @Test fun `fee coins of another family, or unjudged, are not used`() {
        val out = select(listOf(sendRow("good", 5)), listOf(feeCoin("ddfee"), feeCoin("unknown"))) { txid, _ ->
            when (txid) { "ddfee" -> true; "unknown" -> null; else -> false }
        }
        assertTrue("a fee came from another family: $out", out is AssetSendSelection.Outcome.NotPlanned)
    }

    /** GUARD: with nothing of another family the send plans as before. */
    @Test fun `an ordinary asset send still plans`() {
        val out = select(listOf(sendRow("good", 5)), listOf(feeCoin("f"))) { _, _ -> false }
        assertTrue(out is AssetSendSelection.Outcome.Planned)
    }

    // ── sweep and the foreign asset move (the shared classifier) ──────────────────────────────

    private fun rawWithVersion(version: Int) = byteArrayOf(
        version.toByte(), (version shr 8).toByte(), (version shr 16).toByte(), (version shr 24).toByte(), 1, 2, 3,
    )
    private fun classify(raw: ByteArray, isAsset: Boolean): ForeignUtxoAssetClassifier.Verdict {
        val u = UtxoEntry("aa".repeat(32), 3, 100_000_000L, "D1", 1L)
        val c = ForeignUtxoAssetClassifier(fetchRawTx = { raw }, isAssetTx = { isAsset },
            resolveRuleState = { app.aroundtheblock.wallet.core.asset.rules.TransferRuleState.NONE },
            rawOutputScripts = { emptyList() })
        return runBlocking { c.classify(listOf(u)) }.getValue(u)
    }

    /** RED without the classifier check: read as ASSET, the move would spend it, and it is not swept. */
    @Test fun `a dual-family foreign transaction is neither swept nor moved`() {
        val v = classify(rawWithVersion(0x02000770), isAsset = true)
        assertEquals(ForeignUtxoAssetClassifier.Verdict.UNKNOWN, v)
        val u = UtxoEntry("aa".repeat(32), 3, 100_000_000L, "D1", 1L)
        val part = SweepPartition.split(listOf(u), carriesAsset = { v.carriesAsset }, classified = { v.classified })
        assertTrue(part.sweepable.isEmpty() && part.assetBearing.isEmpty() && part.unclassified == listOf(u))
    }

    /** GUARD: a DigiDollar transaction with no asset tag is plain; an asset transaction is an asset. */
    @Test fun `the classifier is unchanged for single-family transactions`() {
        assertEquals(ForeignUtxoAssetClassifier.Verdict.PLAIN, classify(rawWithVersion(0x02000770), isAsset = false))
        assertTrue(classify(rawWithVersion(2), isAsset = true).carriesAsset)
    }

    // ── the foreign DigiDollar move ───────────────────────────────────────────────────────────

    private val xq = "22".repeat(32)
    private fun ddScan(outs: List<DigiDollarHolding.Output>) = runBlocking {
        DigiDollarScan.assemble(
            listOf(DigiDollarAddress("DD1x", xq, 0, 0)),
            holdingFor = { DigiDollarHoldingResult(5000, 1, listOf("tx")) },
            outputsFor = { outs },
        )
    }

    /** RED without the scan check: the dual-family DD output was located and would be moved. */
    @Test fun `a dual-family DigiDollar output is reported, never moved`() {
        val r = ddScan(dual.map { l -> l.split("|").let { DigiDollarHolding.Output(it[0].toInt(), it[1].toLong(), it[2]) } })
        assertTrue(r.holdings.isEmpty())
        assertEquals(5000L, r.unlocatableCents)
    }

    /** GUARD: an ordinary DigiDollar output is still movable by the DD path. */
    @Test fun `an ordinary DigiDollar output is still movable`() {
        val r = ddScan(ordinaryDd.map { l -> l.split("|").let { DigiDollarHolding.Output(it[0].toInt(), it[1].toLong(), it[2]) } })
        assertEquals(1, r.holdings.size)
        assertEquals(0L, r.unlocatableCents)
    }

    // ── review round 2 ────────────────────────────────────────────────────────────────────────

    private fun outs(lines: Array<String>) =
        lines.map { l -> l.split("|").let { DigiDollarHolding.Output(it[0].toInt(), it[1].toLong(), it[2]) } }

    private fun ddScanOf(
        txs: Map<String, Array<String>>, cents: Long, unspent: Int,
        inputs: Map<String, List<DigiDollarHolding.PrevOut>> = emptyMap(),
    ) = runBlocking {
        DigiDollarScan.assemble(
            listOf(DigiDollarAddress("DD1x", xq, 0, 0)),
            holdingFor = { DigiDollarHoldingResult(cents, unspent, txs.keys.toList()) },
            outputsFor = { txs[it]?.let(::outs) },
            inputsFor = { inputs[it] ?: emptyList() },
        )
    }

    /** MEDIUM-1 (a). RED under the round-1 rule, which froze the whole address. */
    @Test fun `an ordinary DigiDollar tx beside a dual one stays movable`() {
        val r = ddScanOf(mapOf("ord" to ordinaryDd, "dual" to dual), cents = 10_000, unspent = 2)
        assertEquals(listOf("ord"), r.holdings.map { it.txid })
        assertEquals("only the dual tx's own cents are unlocatable", 5000L, r.unlocatableCents)
        assertEquals(5000L, r.movableCents)
    }

    /** MEDIUM-1 (b). RED under the round-1 rule. A dual tx whose output is already spent, and the
     *  spend-only tx that spent it, affect nothing. */
    @Test fun `a past spent dual tx no longer affects anything`() {
        val spend = arrayOf("0|0|5120" + "33".repeat(32), "1|0|$ddOpReturn")
        val r = ddScanOf(
            mapOf("dual" to dual, "spend" to spend, "ord" to ordinaryDd), cents = 5000, unspent = 1,
            inputs = mapOf("spend" to listOf(DigiDollarHolding.PrevOut("dual", 0))),
        )
        assertEquals(listOf("ord"), r.holdings.map { it.txid })
        assertEquals(0L, r.unlocatableCents)
    }

    /** MEDIUM-1 (c). RED under the round-1 rule: a DigiAsset-tagged tx with no DigiDollar marking
     *  freezes nothing. */
    @Test fun `a tagged non-DigiDollar tx freezes nothing`() {
        val tagged = arrayOf("0|700|5120$xq", "1|0|$daCarrier")
        val r = ddScanOf(mapOf("ord" to ordinaryDd, "tagged" to tagged), cents = 5000, unspent = 1)
        assertEquals(listOf("ord"), r.holdings.map { it.txid })
        assertEquals(0L, r.unlocatableCents)
    }

    /** MEDIUM-1: held cents that cannot be decoded fall back to the whole address. */
    @Test fun `undecodable held cents make the whole address unlocatable`() {
        val badDual = arrayOf("0|0|$ddToken", "1|0|6a024444" + "61", "2|0|$daCarrier")
        val r = ddScanOf(mapOf("ord" to ordinaryDd, "dual" to badDual), cents = 10_000, unspent = 2)
        assertTrue(r.holdings.isEmpty())
        assertEquals(10_000L, r.unlocatableCents)
    }

    /** Nit 3: a TRANSFER with three DD token outputs; this address holds the one at ordinal 1 (vout 2)
     *  of a dual tx, so exactly that slot's cents (2500) are held and the rest stays movable. */
    @Test fun `a held output at ordinal 1 of a multi-token transfer subtracts its own cents`() {
        val other = "5120" + "44".repeat(32)
        // "DD" type 2 [1000, 2500, 700] then an OP_NOP (not nulldata), DA carrier naming output 0.
        val ddMulti = "6a024444010202e803" + "02c409" + "02bc02" + "61"
        val multiDual = arrayOf("0|0|$other", "1|0|$ddMulti", "2|0|$ddToken", "3|0|$other", "4|0|$daCarrier")
        assertEquals(1000L, DigiDollarHolding.outputCents(outs(multiDual), 0))
        assertEquals(2500L, DigiDollarHolding.outputCents(outs(multiDual), 2))
        assertEquals(700L, DigiDollarHolding.outputCents(outs(multiDual), 3))
        val r = ddScanOf(mapOf("ord" to ordinaryDd, "dual" to multiDual), cents = 7500, unspent = 2)
        assertEquals(listOf("ord"), r.holdings.map { it.txid })
        assertEquals(2500L, r.unlocatableCents)
        assertEquals(5000L, r.movableCents)
    }

    /** Nit 1: a gap in the vouts (a line the parser dropped) makes the cents unreadable. */
    @Test fun `a gap in the vouts makes the cents unreadable`() {
        val gapped = outs(dual).filter { it.vout != 2 }
        assertNull(DigiDollarHolding.outputCents(gapped, 0))
        val gappedDual = arrayOf("0|0|$ddToken", "1|0|$ddOpReturnNonNull", "2|0|$daCarrier", "4|100000000|$ours")
        val r = ddScanOf(mapOf("ord" to ordinaryDd, "dual" to gappedDual), cents = 10_000, unspent = 2)
        assertTrue("a gapped dual tx must fall back to the whole address", r.holdings.isEmpty())
        assertEquals(10_000L, r.unlocatableCents)
    }

    @Test fun `the cents decoder reads the transaction's own DD OP_RETURN`() {
        assertEquals(5000L, DigiDollarHolding.outputCents(outs(dual), 0))
        assertEquals(5000L, DigiDollarHolding.outputCents(outs(ordinaryDd), 0))
        assertNull("not a token output", DigiDollarHolding.outputCents(outs(ordinaryDd), 2))
    }

    /** MEDIUM-2. RED without the OP_RETURN reading: plain nVersion, the marking only in a script. */
    @Test fun `the classifier holds a dual tx marked only by a DigiDollar OP_RETURN`() {
        fun classifyWith(scripts: List<ByteArray>?): ForeignUtxoAssetClassifier.Verdict {
            val u = UtxoEntry("bb".repeat(32), 3, 100_000_000L, "D1", 1L)
            val c = ForeignUtxoAssetClassifier(fetchRawTx = { rawWithVersion(2) }, isAssetTx = { true },
                resolveRuleState = { app.aroundtheblock.wallet.core.asset.rules.TransferRuleState.NONE },
                rawOutputScripts = { scripts })
            return runBlocking { c.classify(listOf(u)) }.getValue(u)
        }
        val dd = ddOpReturnNonNull.hexBytes()
        val opDigiDollar = "6abb028813".hexBytes()
        val carrier = daCarrier.hexBytes()
        assertEquals(ForeignUtxoAssetClassifier.Verdict.UNKNOWN, classifyWith(listOf(oursBytes, dd, carrier)))
        assertEquals(ForeignUtxoAssetClassifier.Verdict.UNKNOWN, classifyWith(listOf(oursBytes, opDigiDollar, carrier)))
        assertEquals("unread scripts are held", ForeignUtxoAssetClassifier.Verdict.UNKNOWN, classifyWith(null))
        assertTrue("guard: an ordinary asset tx is still an asset", classifyWith(listOf(oursBytes, carrier)).carriesAsset)
    }

    /** LOW-1. RED under round 1, which offered an outpoint native does not hold. */
    @Test fun `an outpoint native cannot judge is never offered`() {
        val d = DigiAssetDecoder()
        assertNull(OneFamily.judgeFromNative(AssetSpentState.UNDETECTED, 0, d) { ordinaryAsset })
        assertNull(OneFamily.judgeFromNative(AssetSpentState.PROBE_ERROR, 0, d) { ordinaryAsset })
        assertNull(OneFamily.judgeFromNative(AssetSpentState.HELD, 0, d) { null })
        assertNull(OneFamily.judgeFromNative(AssetSpentState.HELD, 0, d) { emptyArray() })
        assertEquals(true, OneFamily.judgeFromNative(AssetSpentState.OTHER_FAMILY, 0, d) { null })
        assertEquals(true, OneFamily.judgeFromNative(AssetSpentState.HELD, 3, d) { dual })
        assertEquals("guard", false, OneFamily.judgeFromNative(AssetSpentState.HELD, 0, d) { ordinaryAsset })
        val out = select(listOf(sendRow("x", 5)), listOf(feeCoin("f"))) { txid, _ -> if (txid == "x") null else false }
        assertTrue("an unjudged asset input was offered: $out", out is AssetSendSelection.Outcome.NotPlanned)
    }

    private fun String.hexBytes() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
