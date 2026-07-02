package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacySweepInputsTest {
    private val legacyProfile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }

    private fun profileWith(
        derived: List<DerivedAddress>,
        utxos: List<UtxoEntry>,
    ) = RecoveryScanService.ProfileResult(
        profile = legacyProfile,
        addresses = derived.map { it.address },
        derivedAddresses = derived,
        utxos = utxos,
        rawTxs = emptyMap(),
    )

    /** #3: a UTXO on the post-gap address signs with its CARRIED (chain,index),
     *  even though an empty slot was filtered out of the derived list. The old
     *  positional logic would have signed "addrE2" as index 1 and "addrI0" as
     *  external (chain 0, index 2). */
    @Test
    fun assembleSweepInputs_usesCarriedChainIndex_notPosition() {
        val derived = mapDerived(arrayOf("addrE0", "", "addrE2", "addrI0"), gapExternal = 3)
        val result = profileWith(
            derived,
            listOf(
                UtxoEntry("bb".repeat(32), 1, 500L, "addrE2", 10L, "76a914bb88ac"),
                UtxoEntry("cc".repeat(32), 0, 700L, "addrI0", 11L, "76a914cc88ac"),
            ),
        )

        val inputs = assembleSweepInputs(result)

        assertEquals(listOf(0, 1), inputs.chains)  // external, then internal
        assertEquals(listOf(2, 0), inputs.indices) // true derivation indices
        assertEquals(1200L, inputs.totalIn)
        assertEquals(emptyList<String>(), inputs.skippedNoScript)
    }

    /** #4: one null-scriptPubKey row is collected + skipped, not fatal to the
     *  whole profile. The good UTXO still builds; the bad address is reported. */
    @Test
    fun assembleSweepInputs_nullScript_skipsOneKeepsRest() {
        val derived = mapDerived(arrayOf("addrE0", "addrE1"), gapExternal = 200)
        val result = profileWith(
            derived,
            listOf(
                UtxoEntry("dd".repeat(32), 0, 400L, "addrE0", 10L, scriptPubKeyHex = null),
                UtxoEntry("ee".repeat(32), 0, 900L, "addrE1", 11L, "76a914ee88ac"),
            ),
        )

        val inputs = assembleSweepInputs(result)

        assertEquals(1, inputs.txids.size)               // only the good one
        assertEquals(900L, inputs.totalIn)               // null-script amount NOT counted
        assertEquals(listOf("addrE0"), inputs.skippedNoScript)
    }
}
