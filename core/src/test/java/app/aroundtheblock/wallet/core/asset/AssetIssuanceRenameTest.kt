package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.dao.TransactionDao
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.ipfs.AssetMetadataService
import app.aroundtheblock.wallet.core.model.AssetOperation
import android.util.Log
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The issuance walk's rename and its repeat, as BRAssetDetect.h states them (THE RENAME, THE
 * ISSUANCE WALK; asset_detect_kat's rename_rows and issuance_walk): only an "unresolved:"
 * placeholder is renamed, never a derived id, and only when the carrier is the transaction's
 * first OP_RETURN output. On every answer the transaction is marked walked and the answer's facts
 * are stored. After the first answer in a process, the walk is due again only while a row is
 * unresolved that the rename can rename.
 *
 * `processIncomingAssetTx` itself reads [app.aroundtheblock.wallet.core.bridge.NativeBridge],
 * which does not load on the host JVM (see [AssetPersistOnDetectTest]), so the rule is driven
 * through its seams: [carrierIsFirstOpReturn], [issuanceRenameAllowed], [issuanceWalkDue] and
 * [AssetManager.issuanceWalk] with the walk passed in. The scripts and ids are the KAT's.
 */
class AssetIssuanceRenameTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaSvc = mockk<AssetMetadataService>(relaxed = true)
    private lateinit var mgr: AssetManager

    private val txid = "71".repeat(32)
    private val unresolved = "unresolved:$txid"
    private val x = "La" + "x".repeat(36)
    private val y = "La" + "y".repeat(36)
    private val answerY = AssetManager.ResolvedAsset(assetId = y, totalSupply = 22L, divisibility = 0, metadataCid = null)

    // asset_detect_kat's scripts: an issuance carrier, locked (…10) and unlocked (…00), and a
    // plain OP_RETURN that is not a carrier.
    private val lockedCarrier = hex("6a0a" + "44410305" + "2016" + "02" + "2016" + "10")
    private val unlockedCarrier = hex("6a0a" + "44410305" + "2016" + "02" + "2016" + "00")
    private val plainNote = hex("6a0568656c6c6f")
    private val ours = hex("0014" + "11".repeat(20))

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        mgr = AssetManager(utxoDao, txDao, metaDao, metaSvc)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ---- which output the carrier is ---------------------------------------------------------

    @Test fun the_kat_scripts_decode_as_issuances_locked_and_unlocked() {
        val d = DigiAssetDecoder()
        val locked = d.decode(lockedCarrier)
        val unlocked = d.decode(unlockedCarrier)
        assertEquals(AssetOperation.ISSUANCE, locked?.operation)
        assertTrue(locked!!.locked)
        assertEquals(AssetOperation.ISSUANCE, unlocked?.operation)
        assertFalse(unlocked!!.locked)
        assertEquals("a plain OP_RETURN is not a carrier", null, d.decode(plainNote))
    }

    @Test fun a_single_carrier_is_the_first_op_return() {
        val scripts = listOf(ours, unlockedCarrier, ours)
        assertTrue(carrierIsFirstOpReturn(scripts, scripts[1]))
    }

    @Test fun the_last_of_two_carriers_is_not_the_first_op_return() {
        // rename_rows: the carrier read is the last one (unlocked), behind a locked carrier.
        val scripts = listOf(ours, lockedCarrier, unlockedCarrier, ours)
        assertFalse(carrierIsFirstOpReturn(scripts, scripts[2]))
        assertTrue(carrierIsFirstOpReturn(scripts, scripts[1]))
    }

    @Test fun a_carrier_behind_a_plain_op_return_is_not_the_first_op_return() {
        // issuance_walk: a plain OP_RETURN, then the carrier.
        val scripts = listOf(ours, plainNote, unlockedCarrier, ours)
        assertFalse(carrierIsFirstOpReturn(scripts, scripts[2]))
    }

    @Test fun the_first_op_return_is_told_by_position_not_by_contents() {
        val scripts = listOf(ours, unlockedCarrier.copyOf(), unlockedCarrier, ours)
        assertFalse("the second of two equal carriers is not the first OP_RETURN", carrierIsFirstOpReturn(scripts, scripts[2]))
    }

    // ---- the rename's condition -------------------------------------------------------------

    @Test fun only_an_unresolved_placeholder_with_the_first_op_return_carrier_may_be_renamed() {
        assertTrue(issuanceRenameAllowed(unresolved, placeholderDerived = false, carrierIsFirstOpReturn = true))
        assertFalse("a derived id is never renamed", issuanceRenameAllowed(x, placeholderDerived = true, carrierIsFirstOpReturn = true))
        assertFalse("a carrier that is not the first OP_RETURN", issuanceRenameAllowed(unresolved, false, carrierIsFirstOpReturn = false))
        assertFalse("an id that is not a placeholder", issuanceRenameAllowed(x, placeholderDerived = false, carrierIsFirstOpReturn = true))
    }

    @Test fun the_walk_is_due_as_the_core_says() {
        for (walked in listOf(false, true)) for (unres in listOf(false, true)) for (renamable in listOf(false, true)) {
            assertFalse("a transfer never takes the issuance walk",
                issuanceWalkDue(isIssuance = false, walkedInSession = walked, anyUnresolved = unres, renameAllowed = renamable))
            assertEquals("walked=$walked unresolved=$unres renamable=$renamable",
                !walked || (unres && renamable), issuanceWalkDue(true, walked, unres, renamable))
        }
    }

    // ---- the walk -----------------------------------------------------------------------------

    @Test fun a_first_op_return_carrier_under_a_placeholder_is_renamed() = runTest {
        mgr.issuanceWalk(
            txHashHex = txid, placeholderAssetId = unresolved,
            renameAllowed = issuanceRenameAllowed(unresolved, false, carrierIsFirstOpReturn = true),
            anyStillUnresolved = true, resolve = { answerY },
        )
        coVerify(exactly = 1) { utxoDao.replaceAssetId(unresolved, y) }
        coVerify(exactly = 1) { txDao.updateAssetId(txid, y) }
        coVerify(exactly = 1) { metaDao.insertChainFacts(match { it.assetId == y && it.totalSupply == 22L }) }
    }

    @Test fun a_derived_id_is_never_renamed() = runTest {
        mgr.issuanceWalk(
            txHashHex = txid, placeholderAssetId = x,
            renameAllowed = issuanceRenameAllowed(x, placeholderDerived = true, carrierIsFirstOpReturn = true),
            anyStillUnresolved = false, resolve = { answerY },
        )
        coVerify(exactly = 0) { utxoDao.replaceAssetId(any(), any()) }
        coVerify(exactly = 0) { txDao.updateAssetId(any(), any()) }
        coVerify(exactly = 1) { metaDao.insertChainFacts(match { it.assetId == y }) }
    }

    @Test fun a_carrier_that_is_not_the_first_op_return_is_not_renamed_and_its_facts_are_stored() = runTest {
        val scripts = listOf(ours, lockedCarrier, unlockedCarrier, ours)
        mgr.issuanceWalk(
            txHashHex = txid, placeholderAssetId = unresolved,
            renameAllowed = issuanceRenameAllowed(unresolved, false, carrierIsFirstOpReturn(scripts, scripts[2])),
            anyStillUnresolved = true, resolve = { answerY },
        )
        coVerify(exactly = 0) { utxoDao.replaceAssetId(any(), any()) }
        coVerify(exactly = 0) { txDao.updateAssetId(any(), any()) }
        coVerify(exactly = 1) { metaDao.insertChainFacts(match { it.assetId == y }) }
        coVerify(exactly = 1) { metaDao.updateChainFacts(y, 22L, 0, null) }
    }

    @Test fun an_empty_answer_renames_nothing() = runTest {
        mgr.issuanceWalk(
            txHashHex = txid, placeholderAssetId = unresolved, renameAllowed = true, anyStillUnresolved = true,
            resolve = { answerY.copy(assetId = "") },
        )
        coVerify(exactly = 0) { utxoDao.replaceAssetId(any(), any()) }
        coVerify(exactly = 0) { txDao.updateAssetId(any(), any()) }
    }

    @Test fun the_walk_that_cannot_rename_runs_once_per_process() = runTest {
        var walks = 0
        repeat(3) {
            mgr.issuanceWalk(
                txHashHex = txid, placeholderAssetId = unresolved,
                renameAllowed = issuanceRenameAllowed(unresolved, false, carrierIsFirstOpReturn = false),
                anyStillUnresolved = true, resolve = { walks++; answerY },
            )
        }
        assertEquals("walked once for its facts, not on every pass", 1, walks)
        coVerify(exactly = 1) { metaDao.insertChainFacts(any()) }
    }

    @Test fun the_walk_repeats_while_a_renamable_row_is_unresolved() = runTest {
        // An unlocked issuance whose carrier is the first OP_RETURN: the walk is due on every pass
        // while its rows stay unresolved; a walk that has not answered does not mark it walked.
        var walks = 0
        repeat(3) {
            mgr.issuanceWalk(
                txHashHex = txid, placeholderAssetId = unresolved, renameAllowed = true,
                anyStillUnresolved = true, resolve = { walks++; null },
            )
        }
        assertEquals(3, walks)
        mgr.issuanceWalk(txid, unresolved, renameAllowed = true, anyStillUnresolved = true, resolve = { walks++; answerY })
        mgr.issuanceWalk(txid, y, renameAllowed = false, anyStillUnresolved = false, resolve = { walks++; answerY })
        assertEquals("answered and renamed: not walked again this process", 4, walks)
    }

    @Test fun a_named_issuance_is_walked_once_for_its_facts() = runTest {
        var walks = 0
        repeat(2) {
            mgr.issuanceWalk(txid, x, renameAllowed = false, anyStillUnresolved = false, resolve = { walks++; answerY.copy(assetId = x) })
        }
        assertEquals(1, walks)
        coVerify(exactly = 0) { utxoDao.replaceAssetId(any(), any()) }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
