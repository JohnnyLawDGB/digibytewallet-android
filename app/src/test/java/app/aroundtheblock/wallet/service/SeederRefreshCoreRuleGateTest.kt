package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: whether a cached seeder list is fetched again is decided by the core
 * (BRSeederShouldRefresh in BRSeederPeers.h, NativeBridge.seederShouldRefresh), which iOS calls
 * too, and not by Kotlin. Until this moved, SeederRefreshPolicy.isStale was a second copy of the
 * rule with the interval passed in by each caller. The rule's values (empty, exactly one hour
 * kept, one millisecond more refreshed, never fetched, the clock going back, Long wrap) are
 * checked by the host KAT, seeder_peers_kat, which has a red arm for them (SEEDER_REFRESH_RED).
 */
class SeederRefreshCoreRuleGateTest {

    private val appRoot = File("src/main/java/app/aroundtheblock/wallet")
    private val sync = KotlinSourceGate.of(File(appRoot, "service/SyncService.kt").readText())
    private val worker = KotlinSourceGate.of(File(appRoot, "service/SyncWorker.kt").readText())
    private val rule = "NativeBridge.seederShouldRefresh"

    private fun body(source: KotlinSourceGate, name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(source.code) ?: error("no fun $name: update the gate")
        val open = source.code.indexOf('{', declared.range.last)
        return source.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `no Kotlin copy of the refresh rule is left`() {
        assertTrue("SeederRefreshPolicy.kt is back", !File(appRoot, "service/SeederRefreshPolicy.kt").exists())
        for ((name, src) in listOf("SyncService" to sync, "SyncWorker" to worker)) {
            assertTrue("$name calls a Kotlin staleness rule", src.calls("SeederRefreshPolicy.isStale").isEmpty())
            // The old copies: `now - lastFetch > interval` and `now - lastFetch < 60 * 60 * 1000L`. Any
            // subtraction of a last-fetch value counts, whatever stands on the left
            // (`System.currentTimeMillis() - lastFetch`), and so does subtracting a prefs read
            // (`now - prefs.getLong("last_fetch", 0L)`: the key is a literal, blanked in `code`).
            assertTrue("$name compares a seeder list's age itself", !Regex(
                """-\s*([A-Za-z_]\w*\s*\.\s*)*\w*[lL]ast_?[fF]etch\w*|-\s*\w+\s*\.\s*getLong\s*\("""
            ).containsMatchIn(src.code))
        }
        assertTrue("an Android-only seeder interval is back", !Regex("""\bBLOOM_REFRESH_INTERVAL_MS\b""").containsMatchIn(sync.code))
    }

    @Test fun `each inject function asks the core before it asks for a refresh`() {
        for (name in listOf("injectPeers", "injectFilterPeers", "injectDandelionPeers")) {
            val b = body(sync, name)
            val asks = sync.calls(rule, b)
            assertEquals("$name must ask $rule once", 1, asks.size)
            val guarded = sync.guardedBy(asks.single()) ?: error("$name: the refresh is not under `if ($rule(...))`")
            val requests = sync.calls("requestSeederRefresh", b)
            assertTrue("$name asks for a refresh", requests.isNotEmpty())
            // injectPeers also calls the two others, which ask for themselves; its own request is the guarded one.
            assertTrue("$name asks for a refresh the core did not call for",
                requests.any { it.range.first in guarded && it.range.last in guarded })
        }
    }

    @Test fun `the background refresh decides each list with the core`() {
        val refresh = body(sync, "refreshSeederPools")
        // The filter and general pools (one fetch serves both) and the Dandelion pool.
        val asks = sync.calls(rule, refresh)
        assertEquals(3, asks.size)
        // `val filterStale = <ask> || <ask>`, read from the calls' own ranges (their arguments hold lambdas).
        val decl = Regex("""val\s+filterStale\s*=\s*""").find(sync.code, refresh.first)
        assertTrue("no `val filterStale =` in refreshSeederPools", decl != null && decl.range.first in refresh)
        assertEquals("filterStale is not the core's answer for the filter pools", decl!!.range.last + 1, asks[0].range.first)
        assertEquals("filterStale is not the core's answer for both filter pools", "||",
            sync.code.substring(asks[0].range.last + 1, asks[1].range.first).trim())
    }

    @Test fun `the worker's refresh is the core's answer`() {
        val inject = body(worker, "injectCachedPeers")
        val ask = worker.calls(rule, inject)
        assertEquals(1, ask.size)
        // The count is the core's "is it empty": no cached answer is 0 (refresh now), one is 1.
        // Inverted, an empty cache would wait out the hour and a cached one be refetched each run.
        assertEquals("SyncWorker passes the wrong cached count", "if (cachedJson == null) 0 else 1", ask.single().arguments[2])
        assertTrue("injectCachedPeers does not return $rule's answer",
            Regex("""return\s+NativeBridge\s*\.\s*seederShouldRefresh\(""").containsMatchIn(worker.code.substring(inject)))
    }
}
