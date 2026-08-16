package io.digibyte.core.asset

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolving how many asset units a transaction's inputs carried — the missing half of the
 * implicit-change rule (see [AssetImplicitChangeTest]). The answer must be exact or
 * explicitly unknown: a guessed input total propagates into `sendAsset`'s OP_RETURN, where
 * over-stating it makes digiasset-core reject the transfer and burn the whole input.
 */
class AssetInputUnitsTest {

    private fun outpoints(vararg pairs: Pair<String, Int>) = pairs.toList()

    @Test fun sums_the_quantities_of_inputs_we_hold_rows_for() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("aa" to 0, "aa" to 2),
            rowQuantity = { txid, vout -> if (txid == "aa" && vout == 0) 100L else 0L },
            isAssetTx = { true },
        )
        assertEquals(100L, units)
    }

    @Test fun an_input_from_a_plain_dgb_tx_contributes_nothing() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "plaindgb" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else null },
            isAssetTx = { txid -> txid == "asset" },
        )
        assertEquals(40L, units)
    }

    /**
     * An untracked input from a transaction that DOES carry DigiAsset data could be holding
     * any number of units. Counting it as zero would under-state the input total and silently
     * strand the difference; the honest answer is "unknown".
     */
    @Test fun an_untracked_input_from_an_asset_tx_is_unknown() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "otherasset" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else null },
            isAssetTx = { true },
        )
        assertNull(units)
    }

    /** A funding transaction we cannot retrieve tells us nothing, so neither can we. */
    @Test fun an_unretrievable_funding_tx_is_unknown() = runBlocking {
        val units = resolveInputAssetUnits(
            inputs = outpoints("asset" to 0, "gone" to 1),
            rowQuantity = { txid, _ -> if (txid == "asset") 40L else null },
            isAssetTx = { txid -> if (txid == "gone") null else false },
        )
        assertNull(units)
    }

    /**
     * The live case: mainnet tx 6aa6d5c9… spends both outputs of the issuance tx —
     * the 100-unit marker at vout 0 and the plain DGB change at vout 2, which the wallet
     * also holds a row for at quantity 0. Total in: 100.
     */
    @Test fun resolves_the_live_transfer_inputs_to_one_hundred() = runBlocking {
        val issuance = "ee47fe8bacfceba55e8f99717e7133d991a74dc29fc1ee48ab370ee4c7e83de2"
        val units = resolveInputAssetUnits(
            inputs = outpoints(issuance to 0, issuance to 2),
            rowQuantity = { _, vout -> if (vout == 0) 100L else 0L },
            isAssetTx = { true },
        )
        assertEquals(100L, units)
    }
}
