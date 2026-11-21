package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.asset.send.DA_MARKER_SATS
import app.aroundtheblock.wallet.core.reconcile.UtxoEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FOREIGN-ASSET-DISTINCT, the callers' side.
 *
 * The native signers refuse a transaction whose inputs name one outpoint twice
 * (signer_distinct_inputs_kat). A transaction like that is never a valid one, so the refusal takes
 * nothing away as long as no caller builds its input list with a repeat out of distinct coins. This
 * holds each caller to that: the lists it hands a signer name every outpoint once, sibling outputs of
 * one transaction stay two inputs, and a lookup that mentions a coin twice is reduced to one mention
 * where the rows enter, before any list is built.
 */
class SignerInputsDistinctCallersTest {

    private val legacyProfile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }
    private val script = "76a914aabbccddeeff00112233445566778899aabbccdd88ac"
    private val dest = "dgb1qgapugthjpsqnh80jn7un0f34u2qusl8y7gg76f"
    private val feePerKb = 100_000L

    private fun row(txid: String, vout: Int, sats: Long, address: String, height: Long = 100L) =
        UtxoEntry(txid, vout, sats, address, height, script)

    private fun scanned(rows: List<UtxoEntry>) = RecoveryScanService.ProfileResult(
        profile = legacyProfile,
        addresses = listOf("addr0", "addr1"),
        derivedAddresses = listOf(DerivedAddress("addr0", 0, 0), DerivedAddress("addr1", 0, 1)),
        utxos = rows,
        rawTxs = emptyMap(),
    )

    private fun SweepInputs.outpoints() = txids.indices.map { "${txids[it]}:${vouts[it]}" }

    private fun spend(txid: String, vout: Int, sats: Long) = ForeignAssetTransferPlan.Spend(
        txid = txid, vout = vout, amountSat = sats, scriptPubKeyHex = script, chain = 0, index = 0,
    )

    private fun List<ForeignAssetTransferPlan.Spend>.outpoints() = map { "${it.txid}:${it.vout}" }

    // ---- the sweep ---------------------------------------------------------------------------

    /** Nothing valid is refused: distinct coins reach the signer as they are, each once. */
    @Test fun `distinct coins reach the sweep signer unchanged, sibling outputs included`() {
        val a = "aa".repeat(32)
        val b = "bb".repeat(32)
        val rows = listOf(row(a, 0, 500_000L, "addr0"), row(a, 1, 700_000L, "addr1"), row(b, 0, 900_000L, "addr0"))

        val inputs = assembleSweepInputs(scanned(UtxoDedup.byOutpoint(rows)))

        assertEquals(listOf("$a:0", "$a:1", "$b:0"), inputs.outpoints())
        assertEquals(inputs.outpoints().size, inputs.outpoints().toSet().size)
        assertEquals(2_100_000L, inputs.totalIn)
    }

    /** The lookup's repeat is gone before the list is built, so the signer is never handed one. */
    @Test fun `a lookup that mentions one coin twice hands the sweep signer one input for it`() {
        val a = "aa".repeat(32)
        val b = "bb".repeat(32)
        val rows = listOf(
            row(a, 0, 500_000L, "addr0", height = 24_106_618L),
            row(a, 0, 500_000L, "addr0", height = 0L),
            row(b, 0, 900_000L, "addr1"),
        )

        val inputs = assembleSweepInputs(scanned(UtxoDedup.byOutpoint(rows)))

        assertEquals(listOf("$a:0", "$b:0"), inputs.outpoints())
        assertEquals(1_400_000L, inputs.totalIn)
    }

    @Test fun `the scan reduces repeated rows where they enter`() {
        val scan = code("src/main/java/app/aroundtheblock/wallet/core/recovery/RecoveryScanService.kt")
        assertEquals(
            "every profile's rows pass UtxoDedup.byOutpoint on their way into the result",
            1, Regex("""utxos\s*=\s*UtxoDedup\.byOutpoint\(fetched\?\.utxos \?: emptyList\(\)\)""").findAll(scan).count(),
        )
        assertEquals("one place builds a profile's rows", 1, Regex("""\butxos\s*=\s*""").findAll(scan).count())
    }

    // ---- the asset recovery transfer ---------------------------------------------------------

    @Test fun `every asset transfer plan lists each outpoint once, and no two plans share one`() {
        val t = "cc".repeat(32)
        val assets = listOf(
            ForeignAssetTransferBatch.AssetItem(spend("a1".repeat(32), 0, DA_MARKER_SATS), 10L),
            ForeignAssetTransferBatch.AssetItem(spend("a1".repeat(32), 1, DA_MARKER_SATS), 5L),
            ForeignAssetTransferBatch.AssetItem(spend("a2".repeat(32), 0, DA_MARKER_SATS), 7L),
        )
        // Fee coins of assorted sizes, several of them outputs of one transaction; the small ones
        // make a plan take more than one.
        val pool = listOf(
            spend(t, 0, 30_000L), spend(t, 1, 30_000L), spend(t, 2, 30_000L), spend(t, 3, 30_000L),
            spend("dd".repeat(32), 0, 100_000L), spend("ee".repeat(32), 0, 100_000L),
        )

        val planned = ForeignAssetTransferBatch.plan(assets, pool, dest, feePerKb)
        val plans = planned.map { (it.result as? ForeignAssetTransferPlan.Result.Ok)?.plan }

        assertTrue("every asset is planned, got $planned", plans.all { it != null })
        for (plan in plans.filterNotNull()) {
            val ops = plan.inputs.outpoints()
            assertEquals("a plan lists an outpoint twice: $ops", ops.size, ops.toSet().size)
        }
        assertTrue("a plan takes more than one fee coin", plans.any { it!!.inputs.size > 2 })
        val all = plans.filterNotNull().flatMap { it.inputs.outpoints() }
        assertEquals("two plans share an outpoint: $all", all.size, all.toSet().size)
    }

    @Test fun `the split before the transfers lists each plain coin once`() {
        val plain = listOf(spend("f1".repeat(32), 0, 2_000_000L), spend("f1".repeat(32), 1, 3_000_000L))
        val r = ForeignAssetFanOut.plan(
            assetCount = 5, plainInputs = plain, sourceAddress = "DBWmsXxuXsyzhKsBYnqRkMRKjErYGA6xZv", feePerKb = feePerKb,
        )
        val plan = (r as ForeignAssetFanOut.Result.Ok).plan
        assertEquals(plain.outpoints(), plan.inputs.outpoints())
        assertEquals(2, plan.inputs.outpoints().toSet().size)
    }

    @Test fun `the asset signer's arrays are the plan's inputs, and its coins come from the scan's rows`() {
        val move = code("src/main/java/app/aroundtheblock/wallet/core/recovery/ForeignAssetTransferService.kt")
        for (array in listOf("txidsHex", "vouts", "amounts", "chainIndices", "addressIndices", "scriptPubKeysHex")) {
            assertTrue("$array is not written from plan.inputs", Regex("""$array\s*=\s*plan\.inputs\.map\s*\{""").containsMatchIn(move))
        }
        assertEquals("one native call signs a transfer", 1, Regex("""NativeBridge\.buildAndSignForeignAssetTransfer\(""").findAll(move).count())
        // The asset coins and the fee coins are two parts of one partition of the scan's rows.
        assertTrue(Regex("""for \(utxo in partition\.assetBearing\)""").containsMatchIn(move))
        assertTrue(Regex("""val provenPlain\s*=\s*partition\.sweepable\.mapNotNull\s*\{\s*checked\.proven\(it\)\s*\}""").containsMatchIn(move))
        assertTrue(Regex("""val feeCandidates\s*=\s*provenPlain\.mapNotNull\s*\{\s*toSpend\(it, byAddress\)\s*\}""").containsMatchIn(move))
    }

    // ---- the wallet's own send ---------------------------------------------------------------

    /** The bytes signPreparedTransaction is handed are the bytes the core built: its selection
     *  takes each coin once. Nothing else in the app hands a signer bytes. */
    @Test fun `the bytes signers have one caller, which hands back the bytes the core built`() {
        val mains = listOf(File("src/main/java"), File("../app/src/main/java"))
        mains.forEach { assertTrue("${it.path} is missing — the gate is blind, not clean", it.isDirectory) }
        val sources = mains.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue("no Kotlin sources found — the gate is blind, not clean", sources.size > 100)

        val prepared = sources.filter { Regex("""NativeBridge\.signPreparedTransaction\(""").containsMatchIn(code(it)) }
        assertEquals(listOf("TransactionBuilder.kt"), prepared.map { it.name })
        val generic = sources.filter { Regex("""NativeBridge\.signTransaction\(""").containsMatchIn(code(it)) }
        assertEquals("NativeBridge.signTransaction has no caller", emptyList<String>(), generic.map { it.name })

        val builder = code(prepared.single())
        assertEquals(
            "the prepared bytes are createTransaction's",
            1, Regex("""NativeBridge\.createTransaction\(toAddress, amountSatoshis, feePerKb\)""").findAll(builder).count(),
        )
    }

    private fun code(path: String): String = code(File(path))

    private fun code(file: File): String {
        assertTrue("${file.path} is missing — this gate is watching a file that moved", file.exists())
        return file.readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines().joinToString("\n") { it.substringBefore("//") }
    }
}
