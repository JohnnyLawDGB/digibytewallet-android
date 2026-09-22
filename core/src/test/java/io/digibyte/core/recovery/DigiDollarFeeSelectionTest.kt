package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing the DGB that pays a DigiDollar transfer's consensus fee.
 *
 * The recovery flow used to do this inline with `findings.firstOrNull { it.utxos.isNotEmpty() }
 * ?: return null`. A wallet already swept of DGB that still holds dollars took that early return,
 * so the dollars were never mentioned at all — the exact silence the DigiDollar path exists to
 * end. Selecting nothing is a valid selection; it must still reach the transfer service, which
 * refuses honestly and reports the balance.
 */
class DigiDollarFeeSelectionTest {

    private val profileA = DerivationProfile.BUILT_INS.first()
    private val profileB = DerivationProfile.BUILT_INS[1]

    private fun result(
        profile: DerivationProfile,
        utxos: List<UtxoEntry>,
        derived: List<DerivedAddress>,
    ) = RecoveryScanService.ProfileResult(
        profile = profile,
        addresses = derived.map { it.address },
        derivedAddresses = derived,
        utxos = utxos,
        rawTxs = emptyMap(),
    )

    private fun derived(addr: String, chain: Int, index: Int) =
        DerivedAddress(address = addr, chain = chain, index = index)

    private fun utxo(
        addr: String,
        sats: Long,
        script: String? = "0014deadbeef",
        txid: String = "a".repeat(64),
        vout: Int = 0,
        height: Long = 24_119_554L,
    ) = UtxoEntry(
        address = addr, txid = txid, vout = vout, amountSatoshi = sats,
        scriptPubKeyHex = script, blockHeight = height,
    )

    private val plain = ForeignUtxoAssetClassifier.Verdict.PLAIN
    private val asset = ForeignUtxoAssetClassifier.Verdict.ASSET
    private val unknown = ForeignUtxoAssetClassifier.Verdict.UNKNOWN

    /** Every coin in [findings] classified as plain DGB — the ordinary wallet. */
    private fun allPlain(findings: List<RecoveryScanService.ProfileResult>) =
        findings.flatMap { it.utxos }.associateWith { plain }

    private val xq = "076cc826d55b011a868ca89317d79db554ab248c9736b6c34a89f4e6ba1159e9"
    private val recipient = "bb".repeat(32)
    private val changeAddr = "dgb1qxv7ml0y2j4q8k7dgn3szrz883ldxjze0djz0dr"

    /** A wallet whose dollars sit on [outpoints] separate outputs, one dollar each. */
    private fun dollarsOn(outpoints: Int) = DigiDollarScan.Result(
        cents = 100L * outpoints,
        holdings = (0 until outpoints).map { i ->
            DigiDollarScan.Holding(
                address = DigiDollarAddress("DD1x", xq, chain = 0, index = i),
                txid = "%064x".format(i + 1), vout = 0, scriptPubKeyHex = "5120$xq",
            )
        },
        reachable = true,
        unlocatableCents = 0L,
    )

    /**
     * The stop rule the recovery flow hands over: [DigiDollarTransferPlan], sizing the transfer
     * that moves [scan]. Every test here runs against it rather than against a figure of its own.
     */
    private fun sizedFor(scan: DigiDollarScan.Result): (List<ForeignAssetTransferPlan.Spend>) -> Boolean =
        { picked -> DigiDollarTransferService.feeInputsSuffice(scan, picked, recipient, changeAddr) }

    private fun select(
        findings: List<RecoveryScanService.ProfileResult>,
        verdicts: Map<UtxoEntry, ForeignUtxoAssetClassifier.Verdict> = allPlain(findings),
        excluded: Set<String> = emptySet(),
        scan: DigiDollarScan.Result = dollarsOn(1),
    ) = DigiDollarFeeSelection.from(findings, verdicts, excluded, sizedFor(scan))

    private fun outpoints(choice: DigiDollarFeeSelection.Choice) =
        choice.inputs.map { "${it.txid}:${it.vout}" }

    @Test
    fun `no findings still yields a usable selection`() {
        val choice = select(emptyList())
        assertTrue("no DGB means no fee inputs", choice.inputs.isEmpty())
        assertEquals("a profile is always supplied", DerivationProfile.BUILT_INS.first(), choice.profile)
    }

    @Test
    fun `findings with no utxos still yield a usable selection`() {
        val choice = select(
            listOf(result(profileB, emptyList(), listOf(derived("D1", 0, 0))))
        )
        assertTrue(choice.inputs.isEmpty())
    }

    @Test
    fun `the first funded profile supplies the fee inputs`() {
        val choice = select(
            listOf(
                result(profileA, emptyList(), listOf(derived("D1", 0, 0))),
                result(profileB, listOf(utxo("D2", 20_000_000L)), listOf(derived("D2", 1, 3))),
            )
        )
        assertEquals(profileB, choice.profile)
        assertEquals(1, choice.inputs.size)
        assertEquals(20_000_000L, choice.inputs[0].amountSat)
        assertEquals("the true (chain,index) is carried, never a positional guess", 1, choice.inputs[0].chain)
        assertEquals(3, choice.inputs[0].index)
    }

    @Test
    fun `an output with no scriptPubKey cannot be signed and is dropped`() {
        val choice = select(
            listOf(result(profileB, listOf(utxo("D2", 20_000_000L, script = null)),
                listOf(derived("D2", 0, 0))))
        )
        assertTrue(choice.inputs.isEmpty())
    }

    // ---- which coins may pay the fee ----------------------------------------------------------

    /**
     * The fee is paid with plain DGB only. An output the classifier marked as carrying a
     * DigiAsset stays with the asset path, so its units stay with their output — the same rule
     * the sweep applies through [SweepPartition].
     */
    @Test
    fun `an asset-bearing output is never a fee coin`() {
        val held = utxo("D1", 90_000_000L, txid = "c".repeat(64))
        val coin = utxo("D1", 20_000_000L, txid = "d".repeat(64))
        val findings = listOf(result(profileA, listOf(held, coin), listOf(derived("D1", 0, 0))))

        val choice = select(findings, verdicts = mapOf(held to asset, coin to plain))

        assertEquals(listOf("${"d".repeat(64)}:0"), outpoints(choice))
    }

    /** "Could not tell" is never read as "plain": unanswered and never-asked are both held back. */
    @Test
    fun `an output that could not be classified is never a fee coin`() {
        val unanswered = utxo("D1", 90_000_000L, txid = "c".repeat(64))
        val neverAsked = utxo("D1", 80_000_000L, txid = "e".repeat(64))
        val coin = utxo("D1", 20_000_000L, txid = "d".repeat(64))
        val findings = listOf(
            result(profileA, listOf(unanswered, neverAsked, coin), listOf(derived("D1", 0, 0)))
        )

        // neverAsked is absent from the map altogether.
        val choice = select(findings, verdicts = mapOf(unanswered to unknown, coin to plain))

        assertEquals(listOf("${"d".repeat(64)}:0"), outpoints(choice))
    }

    /**
     * The DigiAsset moves run first. An outpoint one of them claimed is not available a second
     * time in the same pass, so the two transactions never name the same input.
     */
    @Test
    fun `an output already claimed earlier in the same pass is never a fee coin`() {
        val claimed = utxo("D1", 90_000_000L, txid = "c".repeat(64), vout = 1)
        val coin = utxo("D1", 20_000_000L, txid = "d".repeat(64))
        val findings = listOf(result(profileA, listOf(claimed, coin), listOf(derived("D1", 0, 0))))

        val choice = select(findings, excluded = setOf("${"c".repeat(64)}:1"))

        assertEquals(listOf("${"d".repeat(64)}:0"), outpoints(choice))
    }

    /**
     * Checked where every usable coin is offered — a wallet short of the fee — so an output with
     * no value has nowhere to go unnoticed behind a larger one.
     */
    @Test
    fun `an output with no value is never a fee coin`() {
        val empty = utxo("D1", 0L, txid = "c".repeat(64))
        val coin = utxo("D1", 3_000_000L, txid = "d".repeat(64))
        val findings = listOf(result(profileA, listOf(empty, coin), listOf(derived("D1", 0, 0))))

        assertEquals(listOf("${"d".repeat(64)}:0"), outpoints(select(findings)))
    }

    @Test
    fun `a profile holding only an output with no value offers no fee coin`() {
        val empty = utxo("D2", 0L, txid = "c".repeat(64))
        val findings = listOf(result(profileB, listOf(empty), listOf(derived("D2", 0, 0))))

        assertTrue(select(findings).inputs.isEmpty())
    }

    /** One outpoint is one input, however many times a lookup lists it. */
    @Test
    fun `an outpoint listed twice is offered once`() {
        val first = utxo("D1", 6_000_000L, txid = "c".repeat(64))
        val again = utxo("D1", 6_000_000L, txid = "c".repeat(64), height = 0L)
        val coin = utxo("D1", 5_000_000L, txid = "d".repeat(64))
        val findings = listOf(
            result(profileA, listOf(first, again, coin), listOf(derived("D1", 0, 0)))
        )

        assertEquals(
            listOf("${"c".repeat(64)}:0", "${"d".repeat(64)}:0"),
            outpoints(select(findings)),
        )
    }

    // ---- which profile pays -------------------------------------------------------------------

    /**
     * Holding coins is not the same as holding coins that can pay. A first profile whose every
     * output is held back, already claimed, or unsignable is passed over for one that can pay.
     */
    @Test
    fun `a first profile with no usable coins falls through to the next`() {
        val held = utxo("D1", 90_000_000L, txid = "c".repeat(64))
        val unanswered = utxo("D1", 80_000_000L, txid = "e".repeat(64))
        val claimed = utxo("D1", 70_000_000L, txid = "f".repeat(64))
        val unsignable = utxo("D1", 60_000_000L, script = null, txid = "9".repeat(64))
        val coin = utxo("D2", 20_000_000L, txid = "d".repeat(64))
        val findings = listOf(
            result(profileA, listOf(held, unanswered, claimed, unsignable),
                listOf(derived("D1", 0, 0))),
            result(profileB, listOf(coin), listOf(derived("D2", 1, 3))),
        )

        val choice = select(
            findings,
            verdicts = mapOf(held to asset, unanswered to unknown, claimed to plain,
                unsignable to plain, coin to plain),
            excluded = setOf("${"f".repeat(64)}:0"),
        )

        assertEquals(profileB, choice.profile)
        assertEquals(listOf("${"d".repeat(64)}:0"), outpoints(choice))
    }

    /** One transfer signs with one profile, so one that covers the fee beats one that cannot. */
    @Test
    fun `a later profile that covers the fee is preferred over an earlier one that cannot`() {
        val small = utxo("D1", 3_000_000L, txid = "c".repeat(64))
        val enough = utxo("D2", 15_000_000L, txid = "d".repeat(64))
        val findings = listOf(
            result(profileA, listOf(small), listOf(derived("D1", 0, 0))),
            result(profileB, listOf(enough), listOf(derived("D2", 0, 0))),
        )

        val choice = select(findings)

        assertEquals(profileB, choice.profile)
        assertEquals(listOf("${"d".repeat(64)}:0"), outpoints(choice))
    }

    // ---- how many coins -----------------------------------------------------------------------

    /**
     * Largest first, and no further than the fee needs. One coin that covers the fee is the whole
     * selection however many small ones sit beside it; the rest are left for the sweep.
     */
    @Test
    fun `one large coin among many small ones is the whole selection`() {
        val small = (0 until 500).map { utxo("D1", 10_000L, txid = "b".repeat(64), vout = it) }
        val large = utxo("D1", 50_000_000L, txid = "d".repeat(64))
        val utxos = small.take(250) + large + small.drop(250)
        val findings = listOf(result(profileA, utxos, listOf(derived("D1", 0, 0))))

        val choice = select(findings)

        assertEquals(listOf("${"d".repeat(64)}:0"), outpoints(choice))
    }

    @Test
    fun `coins are taken largest first and selection stops when the fee is covered`() {
        val findings = listOf(
            result(
                profileA,
                listOf(
                    utxo("D1", 4_000_000L, txid = "1".repeat(64)),
                    utxo("D1", 7_000_000L, txid = "2".repeat(64)),
                    utxo("D1", 1_000_000L, txid = "3".repeat(64)),
                    utxo("D1", 5_000_000L, txid = "4".repeat(64)),
                ),
                listOf(derived("D1", 0, 0)),
            )
        )

        val choice = select(findings)

        // 0.07 + 0.05 covers the 0.1 DGB fee; 0.04 and 0.01 are not needed.
        assertEquals(listOf(7_000_000L, 5_000_000L), choice.inputs.map { it.amountSat })
        assertTrue(choice.inputs.sumOf { it.amountSat } >= DigiDollarTransferPlan.DD_MIN_FEE_SATS)
    }

    // ---- covered means covered for THIS transfer -----------------------------------------------
    //
    // The fee is sized from the transfer's own shape, so a wallet whose dollars sit on many
    // outputs is charged more than the floor. Selection stops where that sizing is satisfied.

    private fun threeCoins() = listOf(
        result(
            profileA,
            listOf(
                utxo("D1", 10_000_000L, txid = "1".repeat(64)),
                utxo("D1", 10_000_000L, txid = "2".repeat(64)),
                utxo("D1", 10_000_000L, txid = "3".repeat(64)),
            ),
            listOf(derived("D1", 0, 0)),
        )
    )

    @Test
    fun `selection stops where the fee this transfer is charged is covered`() {
        val choice = select(threeCoins(), scan = dollarsOn(700))

        // One coin meets the floor and not the sized fee; two meet both; the third is not needed.
        assertEquals(listOf(10_000_000L, 10_000_000L), choice.inputs.map { it.amountSat })
    }

    @Test
    fun `the coins picked for a transfer of many outpoints are coins it can be planned with`() {
        val scan = dollarsOn(700)
        val choice = select(threeCoins(), scan = scan)

        val planned = DigiDollarTransferPlan.build(
            holdings = scan.holdings, totalCents = scan.movableCents, feeInputs = choice.inputs,
            recipientKeyHex = recipient, changeAddress = changeAddr,
            feePerKb = DigiDollarTransferService.DEFAULT_FEE_PER_KB,
        )

        assertTrue("planned as $planned", planned is DigiDollarTransferPlan.Result.Ok)
    }

    /**
     * Where no amount of DGB would let the transfer be planned — here, no outpoint to move —
     * the selection does not gather coins for it.
     */
    @Test
    fun `when more DGB would change nothing the selection does not grow`() {
        val findings = listOf(
            result(
                profileA,
                listOf(
                    utxo("D1", 3_000_000L, txid = "1".repeat(64)),
                    utxo("D1", 2_000_000L, txid = "2".repeat(64)),
                    utxo("D1", 1_000_000L, txid = "3".repeat(64)),
                ),
                listOf(derived("D1", 0, 0)),
            )
        )

        val choice = select(findings, scan = dollarsOn(0))

        assertEquals(listOf(3_000_000L), choice.inputs.map { it.amountSat })
    }

    /**
     * When nothing covers the fee, every usable coin of the best-funded profile is still handed
     * over, so the refusal the transfer service produces states the true shortfall.
     */
    @Test
    fun `coins that cannot cover the fee are all offered so the shortfall is exact`() {
        val findings = listOf(
            result(profileA, listOf(utxo("D1", 1_000_000L, txid = "1".repeat(64))),
                listOf(derived("D1", 0, 0))),
            result(
                profileB,
                listOf(
                    utxo("D2", 2_000_000L, txid = "2".repeat(64)),
                    utxo("D2", 3_000_000L, txid = "3".repeat(64)),
                ),
                listOf(derived("D2", 0, 0)),
            ),
        )

        val choice = select(findings)

        assertEquals(profileB, choice.profile)
        assertEquals(5_000_000L, choice.inputs.sumOf { it.amountSat })
    }
}
