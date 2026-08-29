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

    private fun utxo(addr: String, sats: Long, script: String? = "0014deadbeef") = UtxoEntry(
        address = addr, txid = "a".repeat(64), vout = 0, amountSatoshi = sats,
        scriptPubKeyHex = script, blockHeight = 24_119_554L,
    )

    @Test
    fun `no findings still yields a usable selection`() {
        val choice = DigiDollarFeeSelection.from(emptyList())
        assertTrue("no DGB means no fee inputs", choice.inputs.isEmpty())
        assertEquals("a profile is always supplied", DerivationProfile.BUILT_INS.first(), choice.profile)
    }

    @Test
    fun `findings with no utxos still yield a usable selection`() {
        val choice = DigiDollarFeeSelection.from(
            listOf(result(profileB, emptyList(), listOf(derived("D1", 0, 0))))
        )
        assertTrue(choice.inputs.isEmpty())
    }

    @Test
    fun `the first funded profile supplies the fee inputs`() {
        val choice = DigiDollarFeeSelection.from(
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
        val choice = DigiDollarFeeSelection.from(
            listOf(result(profileB, listOf(utxo("D2", 20_000_000L, script = null)),
                listOf(derived("D2", 0, 0))))
        )
        assertTrue(choice.inputs.isEmpty())
    }
}
