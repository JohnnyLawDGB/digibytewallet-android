package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DA-014-REF: one carrier rule, following DigiAsset Core, at every site that asks "does this transaction
 * carry a DigiAsset, and which output is the carrier" ([DigiAssetCarrierRule]).
 *
 * Driven through the pre-spend pass, which runs the two decisions the other sites share: the
 * carrier choice for the transaction itself, and the parent check that decides whether an input
 * with no row can hold units. A parent the rule cannot decide keeps the child's remainder held.
 */
class AssetCarrierReferenceRuleTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val held = mutableListOf<Pair<String, Int>>()

    private val ours = "0014" + "11".repeat(20)
    private val theirs = "0014" + "22".repeat(20)

    /** Transfer, one instruction: 10 units to output 0. */
    private val transferTenToZero = "6a0644410115000a"
    /** Transfer, one instruction: 10 units to output 2. */
    private val transferTenToTwo = "6a0644410115020a"
    /** A plain OP_RETURN (four bytes, not tagged "DA"): DigiByte Core's nulldata. */
    private val plain = "6a04deadbeef"

    private fun id(tag: String) = tag.padEnd(64, '0')
    private val child = id("c1")
    private val parent = id("e1")
    private val backed = id("f1")

    private val wallet = LinkedHashMap<String, Array<String>>()
    private val unlisted = HashMap<String, Array<String>>()
    private val inputs = HashMap<String, Array<String>>()

    private fun manager() = AssetManager(
        utxoDao = utxoDao,
        transactionDao = mockk(relaxed = true),
        metadataDao = mockk<AssetMetadataDao>(relaxed = true),
        metadataService = mockk(relaxed = true),
        registerAssetOutpoint = { txid, vout -> held += txid to vout; true },
    )

    private suspend fun AssetManager.pass(): Int = holdAssetOutputsBeforeSpendImpl(
        txHashes = { wallet.keys.toTypedArray() },
        outputsOf = { wallet[it] ?: unlisted[it] },
        inputsOf = { inputs[it] },
        ownedScriptHexes = { setOf(ours) },
    )

    /** [backed]:1 is a row of exactly ten units: what a ten-unit instruction hands out. */
    private fun backTenUnits() {
        coEvery { utxoDao.getAssetUtxoAt(backed, 1) } returns UtxoEntity(
            txid = backed, vout = 1, scriptPubKey = ByteArray(0), satoshis = 700L, blockHeight = 1L,
            isAsset = true, assetId = "La", assetQuantity = 10L,
        )
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ── the carrier choice for the transaction itself ─────────────────────────

    /**
     * RED on the last-DA-carrier rule. [ours, carrier, plain OP_RETURN, ours]: DigiAsset Core reads
     * the LAST nulldata output, the plain one, so the transaction is no DigiAsset transaction and
     * credits nothing. The wallet may not credit the carrier's instruction; it holds every owned
     * output instead (the inputs are known to carry exactly what the instruction names, so the
     * last-DA-carrier rule held only output 0).
     */
    @Test fun `a plain OP_RETURN after the carrier holds every owned output`() = runTest {
        backTenUnits()
        wallet[child] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|0|$plain", "3|50000|$ours")
        inputs[child] = arrayOf("$backed|1")

        manager().pass()

        assertEquals(setOf(child to 0, child to 3), held.toSet())
    }

    /** RED on the last-DA-carrier rule: a bare OP_RETURN (no push at all) is nulldata too. */
    @Test fun `a bare OP_RETURN after the carrier holds every owned output`() = runTest {
        backTenUnits()
        wallet[child] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|0|6a", "3|50000|$ours")
        inputs[child] = arrayOf("$backed|1")

        manager().pass()

        assertEquals(setOf(child to 0, child to 3), held.toSet())
    }

    /** GUARD: an OP_RETURN followed by a non-push opcode is not nulldata, so DigiAsset Core does
     *  not read it; the carrier before it still decides, and only what it names is held. */
    @Test fun `a non-push OP_RETURN after the carrier is not the reference carrier`() = runTest {
        backTenUnits()
        wallet[child] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|0|6a76", "3|50000|$ours")
        inputs[child] = arrayOf("$backed|1")

        manager().pass()

        assertEquals(listOf(child to 0), held)
    }

    /** RED on the last-DA-carrier rule. [plain OP_RETURN, carrier]: DigiAsset Core offers the FIRST
     *  nulldata output to its other readers before the asset reader, so a second nulldata output
     *  means the wallet cannot vouch for the asset reading: every owned output is held. */
    @Test fun `a plain OP_RETURN before the carrier holds every owned output`() = runTest {
        backTenUnits()
        wallet[child] = arrayOf("0|700|$ours", "1|0|$plain", "2|0|$transferTenToZero", "3|50000|$ours")
        inputs[child] = arrayOf("$backed|1")

        manager().pass()

        assertEquals(setOf(child to 0, child to 3), held.toSet())
    }

    /** RED on the last-DA-carrier rule: two carriers are two nulldata outputs; every owned output
     *  is held (the last-carrier rule held only output 2, the one the last carrier names). */
    @Test fun `two carriers hold every owned output`() = runTest {
        backTenUnits()
        wallet[child] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|700|$ours", "3|0|$transferTenToTwo", "4|50000|$ours")
        inputs[child] = arrayOf("$backed|1")

        manager().pass()

        assertEquals(setOf(child to 0, child to 2, child to 4), held.toSet())
    }

    // ── the parent check ─────────────────────────────────────────────────────

    /** The child: ten units to output 0 (ours), the carrier at 1, our output last. Its inputs are
     *  [backed]:1 (ten units) and [parent]:0, which has no row: whether the child's last output
     *  can carry a remainder turns on whether [parent] can hold units at all. */
    private fun childSpendingParent() {
        backTenUnits()
        wallet[child] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|50000|$ours")
        inputs[child] = arrayOf("$backed|1", "$parent|0")
    }

    /** RED on the first-OP_RETURN parent check. [plain, carrier]: the parent is a DigiAsset
     *  transaction, so its output may hold units and the child's remainder stays held. */
    @Test fun `a parent with a plain OP_RETURN before its carrier may hold units`() = runTest {
        childSpendingParent()
        unlisted[parent] = arrayOf("0|700|$theirs", "1|0|$plain", "2|0|$transferTenToZero")

        manager().pass()

        assertEquals(setOf(child to 0, child to 2), held.toSet())
    }

    /** RED on the first-OP_RETURN parent check: a parent output line that cannot be read could be
     *  its carrier, so the parent is undecided, never "no asset". */
    @Test fun `a parent with an unreadable output is undecided and keeps the remainder held`() = runTest {
        childSpendingParent()
        unlisted[parent] = arrayOf("0|700|$theirs", "1|0|$plain", "garbage")

        manager().pass()

        assertEquals(setOf(child to 0, child to 2), held.toSet())
    }

    /** GUARD: [carrier, plain]: DigiAsset Core reads it as plain, but it is tagged, so the wallet
     *  cannot rule units out (Undecided), and the child's remainder stays held. */
    @Test fun `a parent with a plain OP_RETURN after its carrier keeps the remainder held`() = runTest {
        childSpendingParent()
        unlisted[parent] = arrayOf("0|700|$theirs", "1|0|$transferTenToZero", "2|0|$plain")

        manager().pass()

        assertEquals(setOf(child to 0, child to 2), held.toSet())
    }

    /** GUARD: a parent with two carriers is a DigiAsset transaction. */
    @Test fun `a parent with two carriers keeps the remainder held`() = runTest {
        childSpendingParent()
        unlisted[parent] = arrayOf("0|700|$theirs", "1|0|$transferTenToZero", "2|0|$transferTenToTwo")

        manager().pass()

        assertEquals(setOf(child to 0, child to 2), held.toSet())
    }

    /** GUARD (the other side): a parent with only a plain OP_RETURN carries no asset, so the
     *  backed ten units are the whole input and the child's last output is ordinary change. */
    @Test fun `a parent with only a plain OP_RETURN carries nothing`() = runTest {
        childSpendingParent()
        unlisted[parent] = arrayOf("0|700|$theirs", "1|0|$plain")

        manager().pass()

        assertEquals(listOf(child to 0), held)
        assertTrue(held.none { it == child to 2 })
    }
}
