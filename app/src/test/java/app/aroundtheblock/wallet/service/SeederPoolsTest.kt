package app.aroundtheblock.wallet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEEDER-PEER-CAP: a seeder answer adds a bounded number of peers, and the cached pool that
 * merges answers stays bounded by dropping its oldest.
 *
 * The limits themselves are the core's and are checked by the host KAT (seeder_peers_kat). Here
 * [poolDrop] and the per-answer limit are written out again in plain Kotlin with the same numbers,
 * so these tests show what the merge does with them; the source checks at the end show that the
 * two places that read a seeder answer ask the core, not a number of their own.
 */
class SeederPoolsTest {

    private val max = 1024
    private val poolMax = 4096
    private fun accepted(listed: Int) = minOf(listed.coerceAtLeast(0), max)
    private fun poolDrop(pooled: Int, adding: Int): Int {
        if (pooled > poolMax) return if (adding >= poolMax) pooled else pooled - (poolMax - adding)
        val room = poolMax - pooled
        return if (adding <= room) 0 else minOf(adding - room, pooled)
    }

    private fun peers(from: Int, count: Int) = (from until from + count).map { Triple("10.0.${it / 250}.${it % 250}", 12024, 0x44dL) }
    private fun merge(pool: List<Triple<String, Int, Long>>, fresh: List<Triple<String, Int, Long>>) =
        SeederPools.merged(pool, fresh, accepted(fresh.size), { it.first to it.second }, ::poolDrop)

    @Test
    fun `an ordinary answer joins the pool whole, and one already held is not added again`() {
        val pool = peers(0, 30)
        val out = merge(pool, peers(20, 20))                // 10 known, 10 new
        assertEquals(40, out.size)
        assertEquals(pool, out.take(30))
        assertEquals(peers(30, 10), out.drop(30))
        assertEquals("a repeat inside one answer is one entry", 31, merge(pool, peers(30, 1) + peers(30, 1)).size)
    }

    @Test
    fun `services are not part of what makes two entries the same peer`() {
        val pool = listOf(Triple("10.0.0.1", 12024, 0x40dL))
        assertEquals(pool, merge(pool, listOf(Triple("10.0.0.1", 12024, 0x44dL))))
    }

    @Test
    fun `a long answer adds no more than the limit`() {
        val out = merge(emptyList(), peers(0, 14_000))
        assertEquals(max, out.size)
        assertEquals("the first ones listed are the ones taken", peers(0, max), out)
    }

    @Test
    fun `a pool at its limit makes room by dropping its oldest`() {
        val pool = peers(0, poolMax)
        val out = merge(pool, peers(100_000, 200))
        assertEquals(poolMax, out.size)
        assertEquals("the oldest 200 went", pool.drop(200), out.take(poolMax - 200))
        assertEquals("the new ones are at the end", peers(100_000, 200), out.takeLast(200))
    }

    @Test
    fun `an hour after hour of new addresses never grows the pool past its limit`() {
        var pool: List<Triple<String, Int, Long>> = emptyList()
        repeat(48) { hour -> pool = merge(pool, peers(1_000_000 + hour * 14_000, 14_000)) }
        assertEquals(poolMax, pool.size)
        assertEquals("the newest answer's entries are all there", peers(1_000_000 + 47 * 14_000, max), pool.takeLast(max))
    }

    @Test
    fun `a pool left oversized by an older build is brought back to the limit`() {
        val out = merge(peers(0, 20_000), peers(500_000, 5))
        assertEquals(poolMax, out.size)
        assertEquals(peers(500_000, 5), out.takeLast(5))
    }

    @Test
    fun `a drop rule that answers nonsense cannot remove more than the pool or go negative`() {
        val pool = peers(0, 10)
        val fresh = peers(50, 3)
        assertEquals(3, SeederPools.merged(pool, fresh, 3, { it.first to it.second }) { _, _ -> 99 }.size)
        assertEquals(13, SeederPools.merged(pool, fresh, 3, { it.first to it.second }) { _, _ -> -5 }.size)
        assertEquals(10, SeederPools.merged(pool, fresh, -1, { it.first to it.second }, ::poolDrop).size)
    }

    // ---- the two readers of a seeder answer ask the core ----

    private val service = File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText()
    private val worker = File("src/main/java/app/aroundtheblock/wallet/service/SyncWorker.kt").readText()

    @Test
    fun `SyncService reads only as many entries of an answer as the core accepts`() {
        val parse = service.substringAfter("private fun parsePeersJson(").substringBefore("\n    }\n")
        assertTrue("the count comes from the core", parse.contains("NativeBridge.seederPeersAccepted(arr.length())"))
        assertTrue("and the loop stops there", Regex("""for \(i in 0 until taken\)""").containsMatchIn(parse))
        assertTrue("no loop over the whole array is left", !parse.contains("0 until arr.length()"))
    }

    @Test
    fun `SyncService's general pool is merged through the bounded merge with the core's rules`() {
        val refresh = service.substringAfter("private fun refreshSeederPools()").substringBefore("private fun fetchFromSeeder(")
        assertTrue(refresh.contains("SeederPools.merged("))
        assertTrue(refresh.contains("maxAdd = NativeBridge.seederPeersAccepted(fresh.size)"))
        assertTrue(refresh.contains("poolDrop = NativeBridge::seederPoolDrop"))
        assertTrue("the old grow-only merge is gone", !refresh.contains("if (seen.add(p.first to p.second)) pool.add(p)"))
    }

    @Test
    fun `SyncWorker injects only as many entries as the core accepts`() {
        val inject = worker.substringAfter("private fun injectPeersJson(").substringBefore("\n    }\n")
        assertTrue(inject.contains("0 until NativeBridge.seederPeersAccepted(peers.length())"))
        assertTrue(!inject.contains("0 until peers.length()"))
    }
}
