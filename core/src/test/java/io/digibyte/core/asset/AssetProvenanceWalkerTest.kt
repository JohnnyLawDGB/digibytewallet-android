package io.digibyte.core.asset

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parent-walk must not lose track of what it already proved.
 *
 * WHAT WENT WRONG, on an S25 Ultra 2026-08-23. A DigiAsset sent back to the wallet showed no
 * name and no artwork. The walk was working perfectly — it resolved twelve parents in a row —
 * and then:
 *
 *     M3 walk[11]: transfer 15fc8fe74353... → parent a3fb285a1c5c...:1
 *     M3 walk: exceeded 12 hops from b12f141525cd...
 *     heldBalances: La3t7Jdv=0(1u), unresolv=1(1u), La8knZNC=10(1u)
 *
 * Two faults, and the second is why it could never recover:
 *
 * 1. `MAX_WALK_DEPTH = 12`, documented as "real chains rarely exceed 2-3". Every send-and-receive
 *    round trip adds two hops, so the asset being used to TEST transfers is the one guaranteed to
 *    walk itself out of range.
 * 2. Nothing kept what the walk learned. `walkedInSession` recorded which txids had been walked,
 *    never what they resolved to — so each attempt re-traversed the whole chain from scratch,
 *    twelve network round trips to end up exactly where it started. A capped walk that discards
 *    its partial progress fails identically forever.
 *
 * Fault 2 is the one that matters. With progress kept, the cap bounds work per attempt rather
 * than bounding what is reachable at all — the same lesson the compact-filter abandonment work
 * arrived at from the other direction.
 *
 * These tests drive the walker through fakes, so a thirty-deep chain costs nothing and the
 * number of fetches is directly observable — which is the only way to prove "it didn't re-walk".
 */
class AssetProvenanceWalkerTest {

    // ---- fakes -----------------------------------------------------------------------------

    private class FakeStore : ProvenanceStore {
        val assets = mutableMapOf<String, ResolvedAssetFacts>()
        val frontiers = mutableMapOf<String, WalkFrontier>()

        override suspend fun assetFor(txid: String) = assets[txid]
        override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) {
            txids.forEach { assets[it] = facts }
        }
        override suspend fun frontierFor(startTxid: String) = frontiers[startTxid]
        override suspend fun putFrontier(frontier: WalkFrontier) {
            frontiers[frontier.startTxid] = frontier
        }
        override suspend fun clearFrontier(startTxid: String) { frontiers.remove(startTxid) }
    }

    private val facts = ResolvedAssetFacts(
        assetId = "La3t7JdvChangPabloEscobar",
        totalSupply = 21L,
        divisibility = 0,
        metadataCid = "bafkreiexample",
    )

    /** A chain of [length] transfers ending in an issuance. tx0 → tx1 → … → issuance. */
    private fun chain(length: Int): Map<String, AssetProvenanceWalker.Hop> =
        buildMap {
            for (i in 0 until length) put("tx$i", AssetProvenanceWalker.Hop.Transfer("tx${i + 1}"))
            put("tx$length", AssetProvenanceWalker.Hop.Issuance(facts))
        }

    /** Counts fetches so "did it re-walk?" is answerable rather than assumed. */
    private class Counting(private val hops: Map<String, AssetProvenanceWalker.Hop>) {
        var fetches = 0
        val hop: suspend (String) -> AssetProvenanceWalker.Hop = { txid ->
            fetches++
            hops[txid] ?: AssetProvenanceWalker.Hop.Unavailable
        }
    }

    // ---- tests -----------------------------------------------------------------------------

    @Test
    fun `resolves a short chain`() = runTest {
        val src = Counting(chain(2))
        val walker = AssetProvenanceWalker(src.hop, FakeStore(), maxHopsPerAttempt = 12)

        assertEquals(facts, walker.resolve("tx0"))
    }

    /**
     * The reported bug. A chain deeper than one attempt's budget must still resolve — the cap
     * limits work per attempt, not how far back the wallet can ever see.
     */
    @Test
    fun `resolves a chain deeper than one attempt's cap, across attempts`() = runTest {
        val src = Counting(chain(30))
        val store = FakeStore()
        val walker = AssetProvenanceWalker(src.hop, store, maxHopsPerAttempt = 10)

        var result: ResolvedAssetFacts? = null
        var attempts = 0
        while (result == null && attempts < 10) {
            result = walker.resolve("tx0")
            attempts++
        }

        assertEquals("the deep chain must resolve", facts, result)
        assertEquals("and take the expected number of attempts, not spin", 4, attempts)
    }

    /**
     * The fault that made the bug permanent. Re-walking from scratch means a chain that failed
     * once fails forever, and burns a full chain's worth of network calls each time it does.
     */
    @Test
    fun `a resumed attempt does not re-walk what it already covered`() = runTest {
        val src = Counting(chain(30))
        val store = FakeStore()
        val walker = AssetProvenanceWalker(src.hop, store, maxHopsPerAttempt = 10)

        walker.resolve("tx0")
        val afterFirst = src.fetches
        walker.resolve("tx0")
        val secondAttemptCost = src.fetches - afterFirst

        assertEquals("first attempt spends its whole budget", 10, afterFirst)
        assertEquals(
            "the second attempt must continue, not restart — restarting is the defect",
            10, secondAttemptCost,
        )
    }

    /** Once resolved, asking again must cost nothing at all. */
    @Test
    fun `a resolved asset is answered from memory with no fetches`() = runTest {
        val src = Counting(chain(3))
        val store = FakeStore()
        val walker = AssetProvenanceWalker(src.hop, store, maxHopsPerAttempt = 12)

        walker.resolve("tx0")
        val afterFirst = src.fetches
        val again = walker.resolve("tx0")

        assertEquals(facts, again)
        assertEquals("a repeat resolve must touch the network zero times", afterFirst, src.fetches)
    }

    /**
     * The case that actually fixes the user's asset: they sent it away, so the wallet already
     * knew the asset for that transaction. Receiving it back should cost one hop, not thirty.
     */
    @Test
    fun `a known ancestor short-circuits the walk`() = runTest {
        val src = Counting(chain(30))
        val store = FakeStore()
        // tx1 is our own earlier send — already resolved, back when the chain was shorter.
        store.assets["tx1"] = facts
        val walker = AssetProvenanceWalker(src.hop, store, maxHopsPerAttempt = 10)

        assertEquals(facts, walker.resolve("tx0"))
        assertEquals("one hop to reach the ancestor we already knew", 1, src.fetches)
    }

    /**
     * A network blip must not throw away proven progress — that is the same mistake as capping
     * without resuming, just triggered by weather instead of depth.
     */
    @Test
    fun `a transient failure keeps the frontier`() = runTest {
        val hops = chain(30).toMutableMap()
        hops["tx5"] = AssetProvenanceWalker.Hop.Unavailable
        val store = FakeStore()
        val walker = AssetProvenanceWalker(Counting(hops).hop, store, maxHopsPerAttempt = 10)

        assertNull(walker.resolve("tx0"))
        val frontier = store.frontierFor("tx0")
        assertNotNull("a transient failure must leave a resume point", frontier)
        assertEquals("resuming at the tx that was unreachable", "tx5", frontier!!.resumeTxid)
    }

    /** A burn or an unsupported issuance is terminal — retrying it forever is waste. */
    @Test
    fun `a terminal dead end clears the frontier`() = runTest {
        val hops = chain(30).toMutableMap()
        hops["tx5"] = AssetProvenanceWalker.Hop.DeadEnd
        val store = FakeStore()
        store.frontiers["tx0"] = WalkFrontier("tx0", "tx0", 0)
        val walker = AssetProvenanceWalker(Counting(hops).hop, store, maxHopsPerAttempt = 10)

        assertNull(walker.resolve("tx0"))
        assertNull("a terminal result must not leave a resume point", store.frontierFor("tx0"))
    }

    /** A chain that points back at itself must stop rather than spend its whole budget looping. */
    @Test
    fun `a cycle is detected`() = runTest {
        val src = Counting(
            mapOf(
                "tx0" to AssetProvenanceWalker.Hop.Transfer("tx1"),
                "tx1" to AssetProvenanceWalker.Hop.Transfer("tx0"),
            )
        )
        val walker = AssetProvenanceWalker(src.hop, FakeStore(), maxHopsPerAttempt = 50)

        assertNull(walker.resolve("tx0"))
        assertEquals("stops at the repeat rather than burning the budget", 2, src.fetches)
    }
}
