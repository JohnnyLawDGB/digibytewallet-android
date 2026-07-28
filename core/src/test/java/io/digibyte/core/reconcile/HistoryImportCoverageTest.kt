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

    // ── the known-set / duplicate interaction (fix round 2) ───────────────────
    //
    // Every case above stubs `register` directly, which bypasses the real
    // known-set→plan→duplicate interaction entirely. That is exactly why nothing
    // caught the regression these tests now pin.
    //
    // `NativeBridge.getTransactionDetails()` truncates to the 100 MOST RECENT wallet
    // txs (`jni_wallet.c:781`, `startIdx = txCount - 100`), while
    // `DgbNodeClient.addressHistoryBatch` returns the FULL unbounded per-address
    // history. So on a wallet with >100 txs, every already-confirmed txid older than
    // the cap survives `planHistoryImport` into `toImport`, fetches and decodes
    // fine, and then hits `registerRawTransaction`'s duplicate branch
    // (`jni_transaction.c:418`, `existing->blockHeight != TX_UNCONFIRMED` →
    // `JNI_FALSE`). Now that a `false` counts against coverage, that would make
    // `covered` essentially unreachable for exactly the wallets that matter — old,
    // deep ones, which are precisely the wallets that accumulate both >100 txs AND
    // deep abandoned bands. Result: the reconcile genuinely closes the gap but the
    // banner never clears and Synced is withheld forever.

    /** A 150-tx wallet: the plan must exclude every already-confirmed txid, not
     *  just the 100 the capped details string happened to carry. */
    @Test fun knownSetIsUncapped_soTruncatedOutOlderTxidsNeverEnterThePlan() {
        val walletTxids = (1..150).map { "wallet-tx-%03d".format(it) }
        val known = knownTxidsForHistoryPlan(
            allHashes = walletTxids.toTypedArray(),
            details = cappedDetails(walletTxids),
        )
        walletTxids.forEach {
            assertTrue("$it is wallet-known but absent from the plan's known set", it in known)
        }

        val nodeHistory = (walletTxids + "missing-from-the-band").map { AddressTx(it, 23_900_121L) }
        val toImport = planHistoryImport(listOf(nodeHistory), known)
        assertEquals(listOf("missing-from-the-band"), toImport.map { it.txid })
    }

    /**
     * End-to-end through the REAL plan path, with `register` simulating the actual
     * JNI contract (`false` for anything the wallet already holds). Coverage must
     * still be achievable on a >100-tx wallet — otherwise the CF-independent
     * recovery backstop is permanently dead where it is needed most.
     */
    @Test fun wideWallet_reconcileCanStillAchieveCoverage() = runTest {
        val walletTxids = (1..150).map { "wallet-tx-%03d".format(it) }
        val alreadyInWallet = walletTxids.toSet()
        val missing = "missing-from-the-band"

        val known = knownTxidsForHistoryPlan(walletTxids.toTypedArray(), cappedDetails(walletTxids))
        val nodeHistory = (walletTxids + missing).map { AddressTx(it, 23_900_121L) }
        val toImport = planHistoryImport(listOf(nodeHistory), known)

        val out = importPlannedHistory(
            toImport = toImport,
            // Carry the txid through as the hex so `register` can act like the wallet.
            fetch = { tx -> RawTxEntry(tx.txid, tx.height, 1_700_000_000L) },
            decodeHex = { hex -> hex.toByteArray() },
            // The real jni_transaction.c behaviour: a tx already in the wallet
            // returns JNI_FALSE from the duplicate branch.
            register = { bytes, _, _ -> String(bytes) !in alreadyInWallet },
        )

        assertEquals(1, out.imported)
        assertEquals(0, out.unrecovered)
        assertTrue(
            "a >100-tx wallet can never prove coverage — the reconcile re-fetches the " +
                "missing tx but the truncated-out older txids ride along as duplicates " +
                "and sink the pass, so the banner nags forever over a gap that closed",
            out.covered,
        )
    }

    /** Null array (no wallet loaded) must not blow up or silently widen the plan;
     *  it degrades to the details-derived set. */
    @Test fun nullHashArray_fallsBackToTheDetailsDerivedSet() {
        val known = knownTxidsForHistoryPlan(null, cappedDetails(listOf("a", "b")))
        assertEquals(setOf("a", "b"), known)
    }

    /** Blank/short entries in the native array are ignored rather than poisoning
     *  the known set with an empty txid that would drop a real entry. */
    @Test fun blankHashEntriesAreIgnored() {
        val known = knownTxidsForHistoryPlan(arrayOf("aaa", "", "  ", "bbb"), "")
        assertEquals(setOf("aaa", "bbb"), known)
    }

    /**
     * NULL elements must not throw. `jni_wallet.c:729` returns a NON-null array of
     * `txCount` NULL slots when the `BRTransaction**` malloc fails, and a failed
     * `NewStringUTF` leaves an individual slot NULL — despite the JNI declaration
     * saying `Array<String>`. A bare `it.trim()` throws NPE straight out of
     * `reconcileAddressHistory()`, on the GATE-3 recovery path.
     */
    @Test fun nullHashElementsAreSkipped_notThrown() {
        val known = knownTxidsForHistoryPlan(arrayOf("aaa", null, "bbb", null), "")
        assertEquals(setOf("aaa", "bbb"), known)
    }

    /** The all-NULL array the malloc-failure path produces degrades to the details
     *  set rather than exploding. */
    @Test fun allNullHashArray_degradesToTheDetailsSet() {
        val known = knownTxidsForHistoryPlan(
            arrayOfNulls<String>(3),
            "ccc|100|1|23900000|1700000000|0|100",
        )
        assertEquals(setOf("ccc"), known)
    }

    // ── the capped-fallback degradation must be LOUD (fix round 3a) ───────────
    //
    // If getAllTransactionHashes() ever returns null or throws, the known set
    // silently reverts to the 100-capped details set — which silently reinstates the
    // Critical fixed in round 2. These pin the CONDITION the warn keys on. (The
    // emission itself is not asserted: android.util.Log is not stubbed in this
    // module's unit tests, and reconcileAddressHistory() is not host-reachable
    // anyway — see the report. Contorting the code for an injectable logger was not
    // worth it; what matters is that the predicate is right and the caller warns.)

    @Test fun nullArrayWithTransactionsPresent_isADegradationWorthWarningAbout() {
        assertTrue(isCappedKnownSetFallback(null, "aaa|100|1|23900000|1700000000|0|100"))
    }

    /** No wallet loaded → both sources empty. That is not a degradation, and warning
     *  on it every reconcile would make the real signal invisible. */
    @Test fun nullArrayWithNoTransactions_isNotADegradation() {
        assertFalse(isCappedKnownSetFallback(null, ""))
        assertFalse(isCappedKnownSetFallback(null, "   \n  "))
    }

    /** A present array — even an empty or all-null one — is not the fallback case;
     *  only a NULL array loses the uncapped source entirely. */
    @Test fun presentArray_isNeverTheFallbackCase() {
        assertFalse(isCappedKnownSetFallback(emptyArray<String>(), "aaa|1|1|1|1|0|1"))
        assertFalse(isCappedKnownSetFallback(arrayOf("aaa"), "aaa|1|1|1|1|0|1"))
    }

    /** Behaviour on the fallback is unchanged — it still plans, just off the capped
     *  set. The warn is diagnostics, not a behaviour change. */
    @Test fun fallbackStillProducesTheCappedKnownSet() {
        val details = "aaa|100|1|23900000|1700000000|0|100\nbbb|100|1|23900001|1700000000|0|100"
        assertEquals(setOf("aaa", "bbb"), knownTxidsForHistoryPlan(null, details))
    }

    /** Production truncation: `getTransactionDetails` carries only the 100 most
     *  recent txs, oldest-first ordering (`BRWalletTransactions`). */
    private fun cappedDetails(walletTxids: List<String>): String =
        walletTxids.takeLast(100)
            .joinToString("\n") { "$it|100|1|23900000|1700000000|0|100" }

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
