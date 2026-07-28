package io.digibyte.core.reconcile

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paced-convoy fetch, spec Part E / GATE 3 — **the coverage predicate that is
 * allowed to clear the abandoned-band surfacing.**
 *
 * `importPlannedHistory(...).covered` is the ONLY thing standing between a
 * reconcile and `markRecovered()`. Getting it wrong in the permissive direction is
 * the one failure the whole safety coupling forbids: the banner clears, "Synced"
 * fires, and a transaction inside the abandoned band is silently gone. That path
 * runs UNATTENDED (`SyncService.maybeRunConfirmationReconcile` and
 * `PostUpgradeReconciler` both supply `appContext`), so it can auto-clear with no
 * user action at all.
 *
 * **The specific trap these tests pin.** A `false` from
 * `NativeBridge.registerRawTransaction` is NOT "already known". Per
 * `native/src/main/jni/bridge/jni_transaction.c:372-434` it returns `JNI_FALSE`
 * for: null wallet, `BRTransactionParse` failure, unsigned tx, and
 * `BRWalletRegisterTransaction` rejecting the tx as not associated with the wallet.
 * The one duplicate branch is UNREACHABLE from this loop, because
 * `planHistoryImport` has already dropped every wallet-known txid. So a `false`
 * here means the tx did not land — the band is not covered.
 */
class HistoryImportCoverageTest {

    private fun tx(id: String, height: Long = 23_900_121L) = AddressTx(id, height)
    private fun raw(hex: String = "0100", height: Long = 23_900_121L) =
        RawTxEntry(hex, height, 1_700_000_000L)

    private suspend fun run(
        planned: List<AddressTx>,
        fetch: suspend (AddressTx) -> RawTxEntry? = { raw() },
        decode: (String) -> ByteArray? = { byteArrayOf(1, 2) },
        register: (ByteArray, Long, Long) -> Boolean = { _, _, _ -> true },
    ) = importPlannedHistory(planned, fetch, decode, register)

    @Test fun everyPlannedTxRegistered_isCovered() = runTest {
        val out = run(listOf(tx("a"), tx("b"), tx("c")))
        assertEquals(3, out.imported)
        assertEquals(0, out.unrecovered)
        assertTrue(out.covered)
    }

    @Test fun nothingPlanned_isCovered() = runTest {
        // The node answered for the whole owned set and had nothing we were
        // missing — that IS full coverage.
        val out = run(emptyList())
        assertTrue(out.covered)
        assertEquals(0, out.imported)
    }

    /**
     * THE IMPORTANT ONE. The node reports tx T at a height inside the abandoned
     * band, the fetch and hex-decode both succeed, and the wallet then REJECTS it
     * (parse failure / unsigned / not associated). T is still missing, so this pass
     * must NOT be allowed to clear the banner.
     */
    @Test fun registerRejection_isNotCoverage() = runTest {
        val out = run(
            listOf(tx("in-the-band")),
            register = { _, _, _ -> false },
        )
        assertEquals(0, out.imported)
        assertEquals(1, out.unrecovered)
        assertFalse(
            "a tx the wallet refused to register is STILL MISSING — treating it as " +
                "coverage clears the abandoned-band banner and fires Synced over a " +
                "transaction that was never recovered",
            out.covered,
        )
    }

    /** One rejection among many successes must still sink the whole pass — coverage
     *  is all-or-nothing, not a majority vote. */
    @Test fun oneRejectionAmongManySuccesses_sinksCoverage() = runTest {
        var n = 0
        val out = run(
            listOf(tx("ok1"), tx("bad"), tx("ok2")),
            register = { _, _, _ -> ++n != 2 },   // the second one is rejected
        )
        assertEquals(2, out.imported)
        assertEquals(1, out.unrecovered)
        assertFalse(out.covered)
    }

    @Test fun fetchFailure_isNotCoverage() = runTest {
        val out = run(listOf(tx("a")), fetch = { null })
        assertFalse(out.covered)
        assertEquals(1, out.unrecovered)
    }

    @Test fun hexDecodeFailure_isNotCoverage() = runTest {
        val out = run(listOf(tx("a")), decode = { null })
        assertFalse(out.covered)
        assertEquals(1, out.unrecovered)
    }

    /** Every per-tx failure mode is counted the same way — no branch is exempt.
     *  One tx fails at each stage, one succeeds. */
    @Test fun allThreeFailureModes_areCountedTogether() = runTest {
        val out = importPlannedHistory(
            listOf(tx("no-fetch"), tx("bad-hex"), tx("rejected"), tx("fine")),
            // The txid rides through as the hex so each stage can pick its victim.
            fetch = { t -> if (t.txid == "no-fetch") null else raw(hex = t.txid) },
            decodeHex = { hex -> if (hex == "bad-hex") null else hex.toByteArray() },
            register = { bytes, _, _ -> String(bytes) != "rejected" },
        )
        assertEquals(1, out.imported)
        assertEquals(3, out.unrecovered)
        assertFalse(out.covered)
    }

    /** The block metadata the node supplied is what gets registered — a pass that
     *  silently dropped the confirming height would leave the tx at TX_UNCONFIRMED
     *  and withhold its DigiDollar/asset credit. */
    @Test fun registersWithTheNodesConfirmingHeight() = runTest {
        var seenHeight = -1L
        var seenTime = -1L
        importPlannedHistory(
            listOf(tx("a")),
            fetch = { RawTxEntry("00", 23_900_123L, 1_712_000_000L) },
            decodeHex = { byteArrayOf(0) },
            register = { _, h, t -> seenHeight = h; seenTime = t; true },
        )
        assertEquals(23_900_123L, seenHeight)
        assertEquals(1_712_000_000L, seenTime)
    }
}
