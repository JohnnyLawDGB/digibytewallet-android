package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.asset.send.AssetStackSource
import app.aroundtheblock.wallet.core.asset.send.StackEntry
import app.aroundtheblock.wallet.core.asset.send.StackLookup
import app.aroundtheblock.wallet.core.db.dao.TransactionDao
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The guarantee behind BB-2026-10-08-rico and DA-003 root cause 2: **an asset quantity counts
 * toward the displayed balance, and is labelled with an asset id, only when it is backed** — when
 * DigiAsset Core would deliver those units of that asset to that output.
 *
 * Each case runs the per-output path [AssetManager.processIncomingAssetTx] runs in production —
 * [transferOutputCredits] over the transaction's inputs, then
 * [AssetManager.persistDetectedAssetOutput], then, for an output decided by nobody,
 * [AssetManager.settleFromIndexer] — against an in-memory utxos table, and then reads the
 * balance the Assets screen shows ([AssetManager.computeHeldAssetBalancesImpl]) and the activity
 * row's count ([AssetManager.receivedBackedUnits]). The entry point itself cannot run on the JVM
 * (its prefix loads the native library); everything after the bridge reads is driven here.
 *
 * The OP_RETURN vectors are the reporters' (2026-10-08 intake). The victim's output is the one
 * the instructions name; "foreign" inputs are ones the wallet holds nothing about.
 */
class UnbackedAssetCreditTest {

    private val X = "La" + "x".repeat(36)
    private val Y = "La" + "y".repeat(36)
    private val A = "La" + "a".repeat(36)
    private val B = "La" + "b".repeat(36)

    private val ownedScript = byteArrayOf(0x51)
    private val ownedHex = "51"
    private val txid = "c".repeat(64)
    private val placeholder = "unresolved:$txid"

    // ── an in-memory utxos table ─────────────────────────────────────────────────────────────
    private val rows = LinkedHashMap<String, UtxoEntity>()
    private fun key(t: String, v: Int) = "$t:$v"
    private val utxoDao = mockk<UtxoDao>(relaxed = true).also { dao ->
        coEvery { dao.getAssetUtxoAt(any(), any()) } answers { rows[key(firstArg(), secondArg())] }
        coEvery { dao.insertAll(any()) } answers { firstArg<List<UtxoEntity>>().forEach { rows[key(it.txid, it.vout)] = it } }
        coEvery { dao.settleAssetCredit(any(), any(), any(), any(), any()) } answers {
            val k = key(arg(0), arg(1))
            rows[k] = rows.getValue(k).copy(assetId = arg(2), assetQuantity = arg(3), assetCredit = arg(4))
        }
        coEvery { dao.updateAssetQuantity(any(), any(), any()) } answers {
            val k = key(arg(0), arg(1)); rows[k] = rows.getValue(k).copy(assetQuantity = arg(2))
        }
        coEvery { dao.markAssetSource(any(), any(), any()) } answers {
            val k = key(arg(0), arg(1)); rows[k] = rows.getValue(k).copy(assetSource = arg(2))
        }
        coEvery { dao.getAllAssetUtxosNow() } answers { rows.values.toList() }
        coEvery { dao.getAssetUtxosForTxNow(any()) } answers { rows.values.filter { it.txid == firstArg<String>() } }
    }
    private val txDao = mockk<TransactionDao>(relaxed = true)

    // ── the indexer, as the send path already reads it ───────────────────────────────────────
    private val answers = HashMap<String, StackLookup>()
    private var lookups = 0
    private val indexer = AssetStackSource { t, v -> lookups++; answers[key(t, v)] ?: StackLookup.Unavailable }

    private fun manager(source: AssetStackSource? = indexer) =
        AssetManager(utxoDao, txDao, mockk(relaxed = true), mockk(relaxed = true), assetStackSource = source)

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ── inputs ───────────────────────────────────────────────────────────────────────────────
    /** One transaction input, described by what the wallet knows about it. */
    private sealed interface In {
        /** Someone else's coin: no row, and the wallet does not hold the transaction that made it. */
        data object Foreign : In
        /** A coin whose creating transaction the wallet holds and which has no data output. */
        data object Plain : In
        /** One of the wallet's own backed asset rows. */
        data class Own(val units: AssetUnits) : In
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /**
     * Receive transaction [txid] with OP_RETURN [script] and [outputCount] outputs, of which the
     * wallet owns [ownedVouts], spending [inputs]; returns the credit each owned output was given.
     */
    private suspend fun receive(
        mgr: AssetManager,
        script: String,
        inputs: List<In>,
        ownedVouts: List<Int>,
        outputCount: Int = 3,
    ): Map<Int, OutputCredit> {
        val header = requireNotNull(DigiAssetDecoder().decode(hex(script)))
        val outpoints = inputs.indices.map { i -> ("%064x".format(i + 1)) to i }
        val plainParents = HashSet<String>()
        inputs.forEachIndexed { i, input ->
            val (t, v) = outpoints[i]
            when (input) {
                In.Foreign -> Unit
                In.Plain -> plainParents.add(t)
                is In.Own -> rows[key(t, v)] = UtxoEntity(
                    txid = t, vout = v, scriptPubKey = ownedScript, satoshis = 600, blockHeight = 1,
                    isAsset = true, assetId = input.units.assetId, assetQuantity = input.units.count,
                    spent = true, assetSource = AssetSource.NATIVE, assetCredit = AssetCredit.VERIFIED,
                )
            }
        }
        val credits = transferOutputCredits(
            header = header,
            inputs = outpoints,
            outputCount = outputCount,
            vouts = ownedVouts,
            row = { t, v -> rows[key(t, v)] },
            parentHasDataOutput = { t -> if (t in plainParents) false else null },
            rowIsTargeted = { _, _ -> null },
            ruleFree = { true },
            payloadExact = AssetPayloadCheck.readsExactly(hex(script), header),
        ).credits
        for (vout in ownedVouts) {
            val credit = credits.getValue(vout)
            mgr.persistDetectedAssetOutput(
                txHashHex = txid, vout = vout, scriptPubKey = ownedScript, sats = 600, blockHeight = 0,
                placeholderAssetId = placeholder,
                computedQty = AssetTxQuantity.forOutputTotal(header, vout, 0, null, outputCount),
                credit = credit,
            )
            if (credit == OutputCredit.Unknown) mgr.settleFromIndexer(txid, vout, placeholder, nowMs = 0L)
        }
        return credits
    }

    private suspend fun balances(mgr: AssetManager): Map<String, Long> =
        mgr.computeHeldAssetBalancesImpl(setOf(ownedHex)) { _, _ -> AssetSpentState.HELD }!!
            .filterValues { it.quantity > 0L }.mapValues { it.value.quantity }

    private fun row(vout: Int) = rows.getValue(key(txid, vout))

    private val forged = listOf(
        // BB-2026-10-08-rico: [noskip→0,1][skip→0,999999] from a 1-unit carrier.
        Triple("6a0b4441031500018060f423f0", listOf(In.Own(AssetUnits(X, 1)), In.Plain), 0),
        // BB-2026-10-08-anmxx: 100 units to output 1, no asset input.
        Triple("6a0744410315012012", listOf(In.Plain), 1),
        // BB-2026-10-08-fzn (01): 1,000,000 units to output 0, no asset input.
        Triple("6a0744410315002016", listOf(In.Plain, In.Plain), 0),
        // BB-2026-10-08-toshit: 1,000,000 declared from a 1-unit carrier.
        Triple("6a0744410315002016", listOf(In.Own(AssetUnits(X, 1))), 0),
    )

    // ── RC1: forged credit ───────────────────────────────────────────────────────────────────

    /** The attacker's inputs are his own coins, unknown to the wallet: the named output is held
     *  out of DGB spending (its claimed quantity stays on the row for that), but nothing counts,
     *  nothing is named, and the activity row claims no units. The indexer then reports what Core
     *  delivered — nothing — and the row settles at zero. */
    @Test fun every_forged_vector_from_unknown_inputs_counts_for_nothing() = runTest {
        for ((script, _, victim) in forged) {
            rows.clear(); answers.clear()
            val mgr = manager()
            answers[key(txid, victim)] = StackLookup.NotUnspent   // not yet confirmed: no answer
            val credits = receive(mgr, script, listOf(In.Foreign, In.Foreign), listOf(victim))

            assertEquals(script, OutputCredit.Unknown, credits[victim])
            assertEquals(script, AssetCredit.UNCHECKED, row(victim).assetCredit)
            assertTrue(script, row(victim).assetQuantity > 0L)          // the claim, for the hold rule only
            assertEquals(script, emptyMap<String, Long>(), balances(mgr))
            assertNull(script, mgr.receivedBackedUnits(rows.values.toList()))

            // Confirmed: Core gave the victim's output nothing.
            answers[key(txid, victim)] = StackLookup.Found(emptyList())
            mgr.settleFromIndexer(txid, victim, placeholder, nowMs = AssetManagerTestClock.later)
            assertEquals(script, AssetCredit.VERIFIED, row(victim).assetCredit)
            assertEquals(script, 0L, row(victim).assetQuantity)
            assertEquals(script, placeholder, row(victim).assetId)
            assertEquals(script, emptyMap<String, Long>(), balances(mgr))
            assertNull(script, mgr.receivedBackedUnits(rows.values.toList()))
        }
    }

    /** Where the wallet does know every input, Core's rules are applied on the device and the
     *  named output is backed by nothing. */
    @Test fun every_forged_vector_with_known_inputs_is_backed_by_nothing() = runTest {
        for ((script, inputs, victim) in forged) {
            rows.clear()
            val mgr = manager()
            val credits = receive(mgr, script, inputs, listOf(victim))

            val credit = credits.getValue(victim)
            assertTrue(script, credit is OutputCredit.Holds && credit.units == null)
            assertEquals(script, AssetCredit.BACKED, row(victim).assetCredit)
            assertEquals(script, 0L, row(victim).assetQuantity)
            assertEquals(script, emptyMap<String, Long>(), balances(mgr))
        }
        assertEquals("no indexer lookup was needed", 0, lookups)
    }

    /** The forged credit cannot reach the last output either: what the carrier really had goes
     *  there, and only that. */
    @Test fun a_voided_transfer_delivers_only_the_carried_units_to_the_last_output() = runTest {
        val mgr = manager()
        receive(mgr, "6a0b4441031500018060f423f0", listOf(In.Own(AssetUnits(X, 1)), In.Plain), listOf(0, 2))
        assertEquals(0L, row(0).assetQuantity)
        assertEquals(AssetUnits(X, 1), AssetUnits(row(2).assetId!!, row(2).assetQuantity))
        assertEquals(mapOf(X to 1L), balances(mgr))
    }

    // ── The honest control ───────────────────────────────────────────────────────────────────

    @Test fun the_honest_control_is_credited_exactly_as_core_from_known_inputs() = runTest {
        val mgr = manager()
        receive(mgr, "6a06444103150005", listOf(In.Own(AssetUnits(X, 9)), In.Plain), listOf(0, 2))
        assertEquals(AssetCredit.BACKED, row(0).assetCredit)
        assertEquals(X to 5L, row(0).assetId to row(0).assetQuantity)
        assertEquals(X to 4L, row(2).assetId to row(2).assetQuantity)
        assertEquals(mapOf(X to 9L), balances(mgr))
        assertEquals(0, lookups)
    }

    @Test fun the_honest_control_from_unknown_inputs_counts_once_the_indexer_confirms_it() = runTest {
        val mgr = manager()
        answers[key(txid, 0)] = StackLookup.Found(listOf(StackEntry(X, 5)))
        receive(mgr, "6a06444103150005", listOf(In.Foreign, In.Foreign), listOf(0))
        assertEquals(AssetCredit.VERIFIED, row(0).assetCredit)
        assertEquals(X to 5L, row(0).assetId to row(0).assetQuantity)
        assertEquals(mapOf(X to 5L), balances(mgr))
        assertEquals(5L, mgr.receivedBackedUnits(rows.values.toList()))
        coVerify { txDao.updateAssetId(txid, X) }
    }

    // ── RC2: the asset is the one the units came from ────────────────────────────────────────

    /** BB-2026-10-08-luis: inputs [X:1][Y:1,000,000]. Output 0 holds Y, and is named Y — not X,
     *  which is what the first input's history says. */
    @Test fun a_second_inputs_asset_is_named_as_itself_from_known_inputs() = runTest {
        val mgr = manager()
        receive(mgr, "6a09444103158201002644",
            listOf(In.Own(AssetUnits(X, 1)), In.Own(AssetUnits(Y, 1_000_000))), listOf(0))
        assertEquals(Y to 1_000_000L, row(0).assetId to row(0).assetQuantity)
        assertEquals(mapOf(Y to 1_000_000L), balances(mgr))
    }

    /** BB-2026-10-08-tsaqif: inputs [B:10][A:10]. */
    @Test fun a_skip_past_the_first_carrier_names_the_second_carriers_asset() = runTest {
        val mgr = manager()
        receive(mgr, "6a0844410315810a000a",
            listOf(In.Own(AssetUnits(B, 10)), In.Own(AssetUnits(A, 10))), listOf(0, 1))
        assertEquals(A to 10L, row(0).assetId to row(0).assetQuantity)
        assertEquals(B to 10L, row(1).assetId to row(1).assetQuantity)
    }

    /** From someone else's inputs the name, like the count, is the indexer's — and it replaces
     *  what a pre-4.0.89 row was labelled with. */
    @Test fun the_indexer_names_the_asset_and_replaces_a_first_input_label() = runTest {
        val mgr = manager()
        rows[key(txid, 0)] = UtxoEntity(
            txid = txid, vout = 0, scriptPubKey = ownedScript, satoshis = 600, blockHeight = 1,
            isAsset = true, assetId = X, assetQuantity = 1_000_000, assetSource = AssetSource.NATIVE,
        )   // written by 4.0.88: named by the first input's walk, credit unchecked
        assertEquals("a pre-upgrade row counts for nothing", emptyMap<String, Long>(), balances(mgr))

        answers[key(txid, 0)] = StackLookup.Found(listOf(StackEntry(Y, 1_000_000)))
        receive(mgr, "6a09444103158201002644", listOf(In.Foreign, In.Foreign), listOf(0))
        assertEquals(Y to 1_000_000L, row(0).assetId to row(0).assetQuantity)
        assertEquals(mapOf(Y to 1_000_000L), balances(mgr))
    }

    /** A mixed-asset input: one input holding X then Y. Each instruction takes from the top. */
    @Test fun a_mixed_input_delivers_each_asset_to_its_own_output() = runTest {
        val mgr = manager()
        val script = DigiAssetEncoder.encodeTransferScript(3, listOf(
            DigiAssetEncoder.TransferInstruction(false, false, false, 0, 3),
            DigiAssetEncoder.TransferInstruction(false, false, false, 1, 4),
        )).joinToString("") { "%02x".format(it) }
        // Input 0 is one coin holding [X:3, Y:4]; the wallet's one-asset rows cannot describe it,
        // so it is supplied as a known stack directly.
        val header = requireNotNull(DigiAssetDecoder().decode(hex(script)))
        val allocation = AssetTransferAllocator.allocate(
            header, listOf(listOf(AssetUnits(X, 3), AssetUnits(Y, 4))), 3,
        ) { true }
        assertEquals(OutputCredit.Holds(AssetUnits(X, 3), X), AssetCreditRules.forOutput(allocation, header, 0, 3))
        assertEquals(OutputCredit.Holds(AssetUnits(Y, 4), Y), AssetCreditRules.forOutput(allocation, header, 1, 3))
        // Both instructions aimed at one output: it holds two assets, which no row can represent.
        val both = DigiAssetEncoder.encodeTransferScript(3, listOf(
            DigiAssetEncoder.TransferInstruction(false, false, false, 0, 3),
            DigiAssetEncoder.TransferInstruction(false, false, false, 0, 4),
        ))
        val h2 = requireNotNull(DigiAssetDecoder().decode(both))
        val a2 = AssetTransferAllocator.allocate(h2, listOf(listOf(AssetUnits(X, 3), AssetUnits(Y, 4))), 3) { true }
        assertEquals(OutputCredit.Mixed, AssetCreditRules.forOutput(a2, h2, 0, 3))

        // The same, reported by the indexer for a received output: not counted, not named.
        answers[key(txid, 0)] = StackLookup.Found(listOf(StackEntry(X, 3), StackEntry(Y, 4)))
        receive(mgr, script, listOf(In.Foreign), listOf(0))
        assertEquals(AssetCredit.MIXED, row(0).assetCredit)
        assertEquals(placeholder, row(0).assetId)
        assertEquals(emptyMap<String, Long>(), balances(mgr))
    }

    /** The walk's facts are accepted only for the asset a row is credited with. */
    @Test fun walk_facts_are_taken_only_for_the_named_asset() = runTest {
        fun facts(id: String) = ResolvedAssetFacts(id, 100, 0, null, 1, true)
        val byTx = mapOf("start" to facts(X), "p1" to facts(X), "p2" to facts(Y))
        val found = factsForNamedAsset(Y, "start", { listOf("p1", "p2") }, { null }) { byTx[it] }
        assertEquals(Y, found?.assetId)
        val none = factsForNamedAsset(A, "start", { listOf("p1", "p2") }, { null }) { byTx[it] }
        assertNull("another lineage's asset is never returned", none)
    }

    // ── Issuance: the units are where Core's instructions put them ────────────────────────────

    private val Z = "La" + "z".repeat(36)   // stands in for the derived id of a new asset

    /** Issue [script] in [txid] with [outputCount] outputs, the wallet owning [ownedVouts]. */
    private suspend fun issue(mgr: AssetManager, script: String, ownedVouts: List<Int>, outputCount: Int): Map<Int, OutputCredit> {
        val header = requireNotNull(DigiAssetDecoder().decode(hex(script)))
        val credits = issuanceOutputCredits(header, Z, outputCount, ownedVouts,
            AssetPayloadCheck.readsExactly(hex(script), header)).credits
        for (vout in ownedVouts) {
            val credit = credits.getValue(vout)
            mgr.persistDetectedAssetOutput(
                txHashHex = txid, vout = vout, scriptPubKey = ownedScript, sats = 600, blockHeight = 0,
                placeholderAssetId = Z,
                computedQty = AssetTxQuantity.forOutputTotal(header, vout, 0, null, outputCount),
                credit = credit,
            )
            if (credit == OutputCredit.Unknown) mgr.settleFromIndexer(txid, vout, Z, nowMs = 0L)
        }
        return credits
    }

    /** Review vector: an issuance of 1,000,000 whose instruction sends everything to output 1,
     *  received at output 0 (the first non-OP_RETURN output, where the issuer's total used to be
     *  credited). Core delivers nothing to output 0. */
    @Test fun an_issuance_credits_only_where_its_instructions_put_the_units() = runTest {
        // The review's bytes as written (push length one short of the payload) and as intended.
        for (script in listOf(
            "6a09" + "44410305" + "2016" + "01" + "2016" + "10",
            "6a0a" + "44410305" + "2016" + "01" + "2016" + "10",
        )) {
            rows.clear()
            val mgr = manager()
            val credits = issue(mgr, script, listOf(0, 1), outputCount = 3)
            assertEquals(script, AssetCredit.BACKED, row(0).assetCredit)
            assertEquals(script, 0L, row(0).assetQuantity)
            assertNull(script, mgr.receivedBackedUnits(rows.filterKeys { it.endsWith(":0") }.values.toList()))
            if (script.startsWith("6a0a")) {
                // Read exactly: output 1, where the instruction sends the units, holds them.
                assertEquals(OutputCredit.Holds(AssetUnits(Z, 1_000_000), Z), credits[1])
                assertEquals(mapOf(Z to 1_000_000L), balances(mgr))
            } else {
                // Not read exactly: output 1 waits for the indexer, which has not answered.
                assertEquals(OutputCredit.Unknown, credits[1])
                assertEquals(emptyMap<String, Long>(), balances(mgr))
            }
        }
    }

    /** No instruction: the whole issue is the remainder, which goes to the LAST output. When that
     *  is the OP_RETURN Core burns it; nothing reaches output 0. */
    @Test fun an_issuance_with_no_instruction_lands_on_the_last_output_or_nowhere() = runTest {
        val noInstruction = "6a07" + "44410305" + "2016" + "10"
        issue(manager(), noInstruction, listOf(0), outputCount = 2)          // [victim, OP_RETURN]
        assertEquals(0L, row(0).assetQuantity)
        assertEquals(AssetCredit.BACKED, row(0).assetCredit)

        rows.clear()
        val mgr = manager()
        issue(mgr, noInstruction, listOf(0, 2), outputCount = 3)            // [a, OP_RETURN, last]
        assertEquals(0L, row(0).assetQuantity)
        assertEquals(Z to 1_000_000L, row(2).assetId to row(2).assetQuantity)
        assertEquals(mapOf(Z to 1_000_000L), balances(mgr))
    }

    @Test fun an_honest_issuance_to_output_zero_is_credited_in_full() = runTest {
        val mgr = manager()
        issue(mgr, "6a0a" + "44410305" + "2016" + "00" + "2016" + "10", listOf(0), outputCount = 2)
        assertEquals(AssetCredit.BACKED, row(0).assetCredit)
        assertEquals(mapOf(Z to 1_000_000L), balances(mgr))
    }

    /** An instruction asking for more than was issued voids them all: everything to the last. */
    @Test fun an_issuance_instruction_beyond_the_issue_voids_to_the_last_output() = runTest {
        val mgr = manager()
        // qty 10 (0x0a), instruction -> 0 for 20 (0x14), flags 0x10.
        issue(mgr, "6a08" + "44410305" + "0a" + "00" + "14" + "10", listOf(0, 2), outputCount = 3)
        assertEquals(0L, row(0).assetQuantity)
        assertEquals(10L, row(2).assetQuantity)
    }

    /** A payload not read exactly as Core reads it decides nothing on the device: the issuer's
     *  claim is held out of DGB spends but not counted. Here, an amount cut off mid-field. */
    @Test fun an_issuance_payload_not_read_exactly_is_left_unchecked() = runTest {
        val truncated = "6a09" + "44410305" + "2016" + "01" + "20" + "10"
        val header = requireNotNull(DigiAssetDecoder().decode(hex(truncated)))
        assertEquals(false, AssetPayloadCheck.readsExactly(hex(truncated), header))
        val mgr = manager()
        val credits = issue(mgr, truncated, listOf(0, 1), outputCount = 3)
        assertEquals(OutputCredit.Unknown, credits[1])          // named by the cut-off instruction
        assertEquals(AssetCredit.UNCHECKED, row(1).assetCredit)
        assertEquals(emptyMap<String, Long>(), balances(mgr))
    }

    /** A rule-bearing issuance (opcode 3/4): its rules block is not parsed, so never BACKED. */
    @Test fun a_rule_bearing_issuance_is_never_backed_on_the_device() = runTest {
        val h = DecodedAssetHeader(
            version = 3, opcode = 3, operation = app.aroundtheblock.wallet.core.model.AssetOperation.ISSUANCE,
            metadataHash = null, metadataCid = null, totalQuantity = 5, divisibility = 0, locked = true,
            aggregation = Aggregation.AGGREGATABLE, transferInstructions = emptyList(),
        )
        assertEquals(false, AssetPayloadCheck.readsExactly(hex("6a07" + "44410303" + "05" + "0010"), h))
        val r = AssetTransferAllocator.allocateIssuance(h, Z, 2, payloadExact = false)
        assertTrue(r is AssetTransferAllocator.Result.Indeterminate)
        assertEquals(OutputCredit.Unknown, AssetCreditRules.forOutput(r, h, 1, 2))
    }

    /** Counts `DigiAsset::processIssuance` rejects issue nothing. */
    @Test fun an_issuance_core_rejects_issues_nothing() {
        fun h(q: Long) = DecodedAssetHeader(
            version = 3, opcode = 5, operation = app.aroundtheblock.wallet.core.model.AssetOperation.ISSUANCE,
            metadataHash = null, metadataCid = null, totalQuantity = q, divisibility = 0, locked = true,
            aggregation = Aggregation.AGGREGATABLE, transferInstructions = emptyList(),
        )
        assertEquals(AssetTransferAllocator.Result.NotAnAssetTransfer, AssetTransferAllocator.allocateIssuance(h(0), Z, 2, true))
        assertEquals(AssetTransferAllocator.Result.NotAnAssetTransfer,
            AssetTransferAllocator.allocateIssuance(h(AssetTransferAllocator.MAX_ISSUANCE + 1), Z, 2, true))
        assertTrue(AssetTransferAllocator.allocateIssuance(h(AssetTransferAllocator.MAX_ISSUANCE), Z, 2, true)
            is AssetTransferAllocator.Result.Allocated)
    }

    // ── Offline, retries, finality ───────────────────────────────────────────────────────────

    @Test fun with_no_indexer_an_unknown_receipt_stays_unchecked_and_uncounted() = runTest {
        val mgr = manager(source = null)
        receive(mgr, "6a06444103150005", listOf(In.Foreign), listOf(0))
        assertEquals(AssetCredit.UNCHECKED, row(0).assetCredit)
        assertEquals(emptyMap<String, Long>(), balances(mgr))
    }

    @Test fun no_answer_is_asked_again_only_after_the_recheck_interval() = runTest {
        val mgr = manager()
        receive(mgr, "6a06444103150005", listOf(In.Foreign), listOf(0))   // Unavailable
        assertEquals(1, lookups)
        mgr.settleFromIndexer(txid, 0, placeholder, nowMs = 1_000L)
        assertEquals("asked again too soon", 1, lookups)
        answers[key(txid, 0)] = StackLookup.Found(listOf(StackEntry(X, 5)))
        mgr.settleFromIndexer(txid, 0, placeholder, nowMs = AssetManagerTestClock.later)
        assertEquals(2, lookups)
        assertEquals(mapOf(X to 5L), balances(mgr))
    }

    @Test fun a_settled_row_is_final() = runTest {
        val mgr = manager()
        answers[key(txid, 0)] = StackLookup.Found(listOf(StackEntry(X, 5)))
        receive(mgr, "6a06444103150005", listOf(In.Foreign), listOf(0))
        val asked = lookups
        // Re-detected on the next sweep, with a different claim: nothing changes, nobody is asked.
        mgr.persistDetectedAssetOutput(txid, 0, ownedScript, 600, 0, placeholder, 1_000_000, credit = OutputCredit.Unknown)
        mgr.settleFromIndexer(txid, 0, placeholder, nowMs = AssetManagerTestClock.later)
        assertEquals(asked, lookups)
        assertEquals(X to 5L, row(0).assetId to row(0).assetQuantity)
    }

    @Test fun an_output_no_instruction_names_and_not_last_holds_nothing_whatever_the_inputs() = runTest {
        val mgr = manager()
        // Four outputs; the instruction names 0, the leftover goes to 3. Output 1 is ours.
        receive(mgr, "6a06444103150005", listOf(In.Foreign), listOf(1), outputCount = 4)
        assertEquals(AssetCredit.BACKED, row(1).assetCredit)
        assertEquals(0L, row(1).assetQuantity)
        assertEquals("no lookup for an output nothing can reach", 0, lookups)
    }
}

/** Times for [AssetManager.settleFromIndexer] calls in these tests. */
private object AssetManagerTestClock {
    /** Past the recheck interval after a call at time 0. */
    const val later = 10 * 60_000L
}
