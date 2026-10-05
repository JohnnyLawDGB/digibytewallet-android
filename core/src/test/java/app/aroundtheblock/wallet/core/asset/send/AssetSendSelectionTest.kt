package app.aroundtheblock.wallet.core.asset.send

import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AssetInputCheck] and [AssetSendSelection]: an asset send spends only outputs the indexer
 *  confirms hold exactly the asset at the recorded quantity, all from one address. */
class AssetSendSelectionTest {

    private val asset = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch"
    private val other = "Ua7Q1otherAssetIdForTheTests0000000000"
    private val x = byteArrayOf(1, 1)
    private val y = byteArrayOf(2, 2)

    private fun row(txid: String, qty: Long, script: ByteArray = x) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = DA_MARKER_SATS, blockHeight = 1L,
        isAsset = true, assetId = asset, assetQuantity = qty,
    )

    private fun fee(txid: String, script: ByteArray = x) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = 1_000_000L, blockHeight = 1L,
    )

    private fun found(vararg e: Pair<String, Long>) = StackLookup.Found(e.map { StackEntry(it.first, it.second) })

    /** A source that answers from [stacks] and records what it was asked. */
    private class FakeSource(val stacks: Map<String, StackLookup>) : AssetStackSource {
        val asked = mutableListOf<String>()
        override suspend fun stackOf(txid: String, vout: Int): StackLookup {
            asked += txid
            return stacks[txid] ?: StackLookup.Unavailable
        }
    }

    private fun choose(
        source: AssetStackSource?, rows: List<UtxoEntity>, qty: Long,
        fees: List<UtxoEntity> = listOf(fee("f")), parentHasData: (String) -> Boolean? = { false },
    ) = runBlocking { AssetSendSelection(source, parentHasData).choose(asset, rows, fees, qty, 100_000L) }

    // ── AssetInputCheck ──────────────────────────────────────────────────────────────────────

    @Test fun only_exactly_the_asset_at_the_recorded_quantity_is_verified() {
        val j = { l: StackLookup, q: Long -> AssetInputCheck.judge(l, asset, q) }
        assertEquals(AssetInputCheck.Verdict.VERIFIED, j(found(asset to 5), 5))
        assertEquals(AssetInputCheck.Verdict.MISMATCH, j(found(asset to 5), 4))
        assertEquals(AssetInputCheck.Verdict.MISMATCH, j(found(asset to 5, other to 1), 5))
        assertEquals(AssetInputCheck.Verdict.MISMATCH, j(found(other to 1, asset to 5), 5))
        assertEquals(AssetInputCheck.Verdict.MISMATCH, j(found(other to 5), 5))
        assertEquals(AssetInputCheck.Verdict.MISMATCH, j(found(), 5))
        assertEquals(AssetInputCheck.Verdict.MISMATCH, j(found(asset to 0), 0))
        assertEquals(AssetInputCheck.Verdict.MISMATCH, j(StackLookup.NotUnspent, 5))
        assertEquals(AssetInputCheck.Verdict.UNAVAILABLE, j(StackLookup.Unavailable, 5))
    }

    // ── AssetSendSelection ───────────────────────────────────────────────────────────────────

    @Test fun confirmed_inputs_are_planned() {
        val src = FakeSource(mapOf("a" to found(asset to 5)))
        val out = choose(src, listOf(row("a", 5)), 3)
        assertTrue(out is AssetSendSelection.Outcome.Planned)
        assertEquals(listOf("a"), (out as AssetSendSelection.Outcome.Planned).plan.assetInputs.map { it.txid })
    }

    @Test fun an_input_holding_something_else_is_set_aside_and_the_send_uses_another() {
        val src = FakeSource(mapOf("big" to found(other to 9), "small" to found(asset to 4)))
        val out = choose(src, listOf(row("big", 9), row("small", 4)), 3)
        assertTrue(out is AssetSendSelection.Outcome.Planned)
        assertEquals(listOf("small"), (out as AssetSendSelection.Outcome.Planned).plan.assetInputs.map { it.txid })
    }

    @Test fun when_no_confirmed_inputs_cover_the_amount_nothing_is_planned() {
        val src = FakeSource(mapOf("a" to found(other to 5)))
        assertEquals(AssetSendSelection.Outcome.Unconfirmed, choose(src, listOf(row("a", 5)), 3))
    }

    @Test fun no_answer_stops_the_send() {
        val src = FakeSource(emptyMap())
        assertEquals(AssetSendSelection.Outcome.Unverified, choose(src, listOf(row("a", 5)), 3))
        assertEquals(AssetSendSelection.Outcome.Unverified, choose(null, listOf(row("a", 5)), 3))
    }

    @Test fun an_output_the_indexer_reports_spent_is_set_aside() {
        val src = FakeSource(mapOf("a" to StackLookup.NotUnspent, "b" to found(asset to 5)))
        val out = choose(src, listOf(row("a", 9), row("b", 5)), 3)
        assertEquals(listOf("b"), (out as AssetSendSelection.Outcome.Planned).plan.assetInputs.map { it.txid })
    }

    @Test fun an_amount_spread_over_two_addresses_is_refused_with_the_largest_single_amount() {
        val src = FakeSource(mapOf("a" to found(asset to 3), "b" to found(asset to 2)))
        val out = choose(src, listOf(row("a", 3, x), row("b", 2, y)), 4)
        assertEquals(AssetSendSelection.Outcome.SpansAddresses(3), out)
        assertTrue("nothing is asked before a plan exists", src.asked.isEmpty())
    }

    @Test fun inputs_come_from_one_address_and_change_returns_there() {
        val src = FakeSource(mapOf("a1" to found(asset to 2), "a2" to found(asset to 2), "b" to found(asset to 1)))
        val out = choose(src, listOf(row("a1", 2, x), row("a2", 2, x), row("b", 1, y)), 3)
        val plan = (out as AssetSendSelection.Outcome.Planned).plan
        assertTrue(plan.assetInputs.all { it.scriptPubKey.contentEquals(x) })
        assertTrue(plan.sourceScript.contentEquals(x))
    }

    @Test fun fee_coins_from_elsewhere_must_come_from_transactions_without_data() {
        val src = FakeSource(mapOf("a" to found(asset to 5)))
        val fees = listOf(fee("plain", y), fee("fromAssetTx", y))
        val out = choose(src, listOf(row("a", 5)), 3, fees, parentHasData = { it == "fromAssetTx" })
        val plan = (out as AssetSendSelection.Outcome.Planned).plan
        assertTrue(plan.dgbInputs.none { it.txid == "fromAssetTx" })
    }

    @Test fun a_shortfall_caused_by_the_fee_coin_rule_says_so() {
        val src = FakeSource(mapOf("a" to found(asset to 5)))
        val out = choose(src, listOf(row("a", 5)), 3, listOf(fee("fromAssetTx", y)), parentHasData = { true })
        assertTrue(out is AssetSendSelection.Outcome.NotPlanned)
        assertTrue((out as AssetSendSelection.Outcome.NotPlanned).message.contains("ordinary payments"))
    }

    @Test fun a_confirmed_output_is_not_asked_again_in_the_same_process() {
        val src = FakeSource(mapOf("a" to found(asset to 5)))
        val sel = AssetSendSelection(src) { false }
        runBlocking {
            sel.choose(asset, listOf(row("a", 5)), listOf(fee("f")), 3, 100_000L)
            sel.choose(asset, listOf(row("a", 5)), listOf(fee("f")), 2, 100_000L)
        }
        assertEquals(listOf("a"), src.asked)
    }

    /** An output the indexer has not seen yet (unconfirmed, or the indexer behind) is left out of
     *  this send only; once the indexer reports it, a later send may use it. An output the indexer
     *  reports holding something else stays set aside. */
    @Test fun an_output_the_indexer_has_not_seen_yet_is_asked_again_later() {
        var answer: StackLookup = StackLookup.NotUnspent
        val src = object : AssetStackSource {
            override suspend fun stackOf(txid: String, vout: Int) =
                if (txid == "new") answer else found(other to 9)
        }
        val sel = AssetSendSelection(src) { false }
        runBlocking {
            assertEquals(AssetSendSelection.Outcome.Unconfirmed, sel.choose(asset, listOf(row("new", 5)), listOf(fee("f")), 3, 100_000L))
            answer = found(asset to 5)
            val out = sel.choose(asset, listOf(row("new", 5)), listOf(fee("f")), 3, 100_000L)
            assertTrue(out is AssetSendSelection.Outcome.Planned)

            assertEquals(AssetSendSelection.Outcome.Unconfirmed, sel.choose(asset, listOf(row("wrong", 9)), listOf(fee("f")), 3, 100_000L))
            assertTrue("held as set aside", sel.setAsideOutpoints().any { it.startsWith("wrong:") })
            assertTrue("not held as set aside", sel.setAsideOutpoints().none { it.startsWith("new:") })
        }
    }
}
