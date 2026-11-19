package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.reconcile.DigiDollarHoldingResult
import app.aroundtheblock.wallet.core.reconcile.ReconcileResult
import app.aroundtheblock.wallet.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QUICKSTART-DD: the phrase scan ([RecoveryScanService.scan], which the onboarding recovery scan
 * and a restore's quick start share) looks up DigiDollar with the seed while it is alive, as
 * Recover funds ([RecoveryScanService.scanFromSeed]) always has. Until 4.1.5 it never looked, so a
 * restored wallet holding dollars had a quick start that said nothing about them.
 *
 *  - what the lookup reports is carried in `State.Done.digiDollar`;
 *  - a lookup that could not be made (it threw, or answered null) is null, and one that was not
 *    finished keeps `reachable = false`: neither is ever a zero, and neither fails the DGB answer;
 *  - the seed's last use is deriving the DigiDollar addresses: it is zeroed before the endpoint is
 *    asked and stays zeroed however the scan ends;
 *  - the lookup has a time budget, and one that runs out of it is null too, with the DGB answer kept;
 *  - only a scan asked for DigiDollar (the quick start) looks it up.
 *
 * NativeBridge cannot load on the host JVM, so the scan is driven through `scanPhrase`, whose
 * parameters stand in for the native calls: the DigiDollar derivation and the lookup over the
 * derived addresses (`scanDigiDollar`, whose gap rule is native) are stood in for whole. What the
 * lookup does with an endpoint's answers is [DigiDollarScan]'s and [DigiDollarHoldingLookup]'s,
 * tested there and here; that production passes the real ones is the source gate's, at the end.
 */
class RecoveryScanDigiDollarTest {
    private val profile = DerivationProfile.BUILT_INS.first { it.isNative }.copy(gapExternal = 2, gapInternal = 1)
    private val addr = "dgb1qquickstartexample"
    private val coin = UtxoEntry("aa".repeat(32), 0, 150_000_000L, addr, 100L, "0014" + "11".repeat(20))
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    /** A rule that never widens: the first answer is the finished one. */
    private object Finished : RecoveryGapRule {
        override fun next(chain: Int, count: Int, highestUsed: Int?) = count
        override fun outcome(
            externalCount: Int, internalCount: Int, nextExternal: Int, nextInternal: Int,
            usedExternal: Int?, usedInternal: Int?, round: Int, bound: Int,
        ) = RecoveryGapRule.FINISHED
        override fun roundBound(startExternal: Int, startInternal: Int) = 4
    }

    private fun service() = RecoveryScanService(
        FakeUtxoSource(mapOf(addr to ReconcileResult(listOf(coin), emptyMap(), 200L))), listOf(profile), Finished,
    )

    private fun dollars(cents: Long, reachable: Boolean = true) =
        DigiDollarScan.Result(cents = cents, holdings = emptyList(), reachable = reachable, unlocatableCents = cents)

    /** The seed the scan derives, the one it handed to the DigiDollar derivation, and what it was
     *  when the DigiDollar endpoint was asked. */
    private class Seeds {
        val derived = ByteArray(64) { (it + 1).toByte() }
        var handed: ByteArray? = null
        var aliveWhenHanded = false
        var zeroedWhenAsked: Boolean? = null
    }

    private val ddAddress = DigiDollarAddress("DDexample", "ab".repeat(32), chain = 0, index = 0)

    private fun scan(
        seeds: Seeds = Seeds(),
        service: RecoveryScanService = service(),
        lookUp: Boolean = true,
        budgetMs: Long = 30_000L,
        digiDollar: suspend () -> DigiDollarScan.Result?,
    ): RecoveryScanService.State = runBlocking {
        service.scanPhrase(
            mnemonic = phrase,
            passphrase = null,
            digiDollar = lookUp,
            seedOf = { _, _ -> seeds.derived },
            derive = { _, ps -> ps.associateWith { listOf(DerivedAddress(addr, chain = 0, index = 0)) } },
            outputScripts = { emptySet() },
            scriptOf = { null },
            digiDollarAddresses = { seed ->
                seeds.handed = seed
                seeds.aliveWhenHanded = seed.any { it != 0.toByte() }
                listOf(ddAddress)
            },
            digiDollarLookup = { addresses ->
                assertEquals(listOf(ddAddress), addresses)
                seeds.zeroedWhenAsked = seeds.derived.all { it == 0.toByte() }
                digiDollar()
            },
            digiDollarBudgetMs = budgetMs,
        )
    }

    private fun done(state: RecoveryScanService.State): RecoveryScanService.State.Done {
        assertTrue("the scan did not finish: $state", state is RecoveryScanService.State.Done)
        return state as RecoveryScanService.State.Done
    }

    @Test fun `the phrase scan reports what the DigiDollar lookup found`() {
        val svc = service()
        val d = done(scan(service = svc) { dollars(100L) })
        assertEquals("the scan did not look up DigiDollar", 100L, d.digiDollar?.cents)
        assertTrue(d.digiDollar!!.reachable)
        assertEquals(150_000_000L, d.totalBalanceSat)
        assertEquals("the service's state does not carry it", d, svc.state.value)
    }

    @Test fun `a DigiDollar lookup that throws is not a zero and does not fail the DGB answer`() {
        val d = done(scan { throw IllegalStateException("endpoint down") })
        assertNull("a lookup that threw must read as not made", d.digiDollar)
        assertEquals(150_000_000L, d.totalBalanceSat)
        assertFalse(d.anyBackendUnreachable)
    }

    @Test fun `a DigiDollar lookup that could not be made is null, never zero`() {
        var asked = false
        val d = done(scan { asked = true; null })
        assertTrue("the scan did not look up DigiDollar", asked)
        assertNull(d.digiDollar)
        assertEquals(150_000_000L, d.totalBalanceSat)
    }

    @Test fun `an unfinished DigiDollar lookup stays unfinished`() {
        val d = done(scan { dollars(0L, reachable = false) })
        assertNotNull("the scan did not look up DigiDollar", d.digiDollar)
        assertFalse("an address that could not be asked reads as a finished zero", d.digiDollar!!.reachable)
        assertEquals(150_000_000L, d.totalBalanceSat)
    }

    @Test fun `the seed handed to the DigiDollar lookup is zeroed when the scan ends`() {
        val seeds = Seeds()
        done(scan(seeds) { dollars(100L) })
        assertSame("the lookup was not given the scan's own seed", seeds.derived, seeds.handed)
        assertTrue("the seed was already zeroed when the lookup needed it", seeds.aliveWhenHanded)
        assertTrue("the seed outlived the scan", seeds.derived.all { it == 0.toByte() })
    }

    @Test fun `the seed is zeroed when the DigiDollar lookup throws`() {
        val seeds = Seeds()
        done(scan(seeds) { throw IllegalStateException("endpoint down") })
        assertSame(seeds.derived, seeds.handed)
        assertTrue("the seed outlived a scan whose DigiDollar lookup threw", seeds.derived.all { it == 0.toByte() })
    }

    @Test fun `the seed is zeroed when the DigiDollar lookup is cancelled`() {
        val seeds = Seeds()
        val state = scan(seeds) { throw kotlinx.coroutines.CancellationException("left the screen") }
        assertSame(seeds.derived, seeds.handed)
        assertTrue("a cancelled lookup went on to give an answer", state is RecoveryScanService.State.Failed)
        assertTrue("the seed outlived a cancelled scan", seeds.derived.all { it == 0.toByte() })
    }

    @Test fun `Recover funds takes the same path and leaves its caller's seed alone`() = runBlocking {
        val seed = ByteArray(64) { 9 }
        val d = service().classifyThenDigiDollar(
            seed,
            derive = { _, ps -> ps.associateWith { listOf(DerivedAddress(addr, chain = 0, index = 0)) } },
            outputScripts = { emptySet() },
            scriptOf = { null },
            digiDollar = { dollars(250L) },
        )
        assertEquals(250L, d.digiDollar?.cents)
        assertEquals(150_000_000L, d.totalBalanceSat)
        assertTrue("the shared path zeroed a seed it does not own", seed.all { it == 9.toByte() })
    }

    @Test fun `the seed is zeroed before the DigiDollar endpoint is asked`() {
        val seeds = Seeds()
        done(scan(seeds) { dollars(100L) })
        assertTrue("the DigiDollar addresses were derived from a dead seed", seeds.aliveWhenHanded)
        assertEquals("the seed was still alive while the endpoint was asked", true, seeds.zeroedWhenAsked)
    }

    @Test fun `the seed is zeroed when the DigiDollar addresses cannot be derived`() {
        val seeds = Seeds()
        val state = runBlocking {
            service().scanPhrase(
                mnemonic = phrase, passphrase = null, digiDollar = true,
                seedOf = { _, _ -> seeds.derived },
                derive = { _, ps -> ps.associateWith { listOf(DerivedAddress(addr, chain = 0, index = 0)) } },
                outputScripts = { emptySet() }, scriptOf = { null },
                digiDollarAddresses = { throw IllegalStateException("native derive failed") },
                digiDollarLookup = { error("asked with no addresses") },
            )
        }
        assertNull("a derivation that failed reads as an answer", done(state).digiDollar)
        assertEquals(150_000_000L, done(state).totalBalanceSat)
        assertTrue(seeds.derived.all { it == 0.toByte() })
    }

    /**
     * A DigiDollar endpoint that never answers must not hold the DGB answer. The lookup here BLOCKS
     * its thread, as an HTTP call does: a timeout alone cannot end that, so the scan must not be
     * waiting on the thread. It ends as "could not be checked" (null), never a zero.
     */
    @Test fun `a DigiDollar lookup that never answers gives the DGB answer within the budget`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val seeds = Seeds()
        try {
            val started = System.nanoTime()
            val d = done(scan(seeds, budgetMs = 300L) {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS)
                dollars(100L)
            })
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("the scan waited $tookMs ms on a lookup with a 300 ms budget", tookMs < 5_000)
            assertNull("a lookup that ran out of time reads as an answer", d.digiDollar)
            assertEquals("the DGB answer was lost", 150_000_000L, d.totalBalanceSat)
            assertFalse(d.anyBackendUnreachable)
            assertEquals("the lookup left running was holding a live seed", true, seeds.zeroedWhenAsked)
        } finally {
            release.countDown()
        }
    }

    @Test fun `a scan that was not asked for DigiDollar derives no DigiDollar address and asks nothing`() {
        val seeds = Seeds()
        val d = done(scan(seeds, lookUp = false) { dollars(100L) })
        assertNull("the DigiDollar addresses were derived", seeds.handed)
        assertNull("the DigiDollar endpoint was asked", seeds.zeroedWhenAsked)
        assertNull("a lookup that was not made reads as an answer", d.digiDollar)
        assertEquals(150_000_000L, d.totalBalanceSat)
        assertTrue("the seed outlived the scan", seeds.derived.all { it == 0.toByte() })
    }

    @Test fun `a round's window is taken from the addresses derived once, in derivation order`() {
        fun a(chain: Int, index: Int) = DigiDollarAddress("DD$chain-$index", "cd".repeat(32), chain, index)
        val all = (0 until 5).map { a(0, it) } + (0 until 4).map { a(1, it) }
        assertEquals(listOf(a(0, 0), a(0, 1), a(1, 0)), digiDollarWindow(all, external = 2, internal = 1))
        assertEquals(all, digiDollarWindow(all, external = 5, internal = 4))
        // A slot the derivation dropped stays dropped; nothing shifts into its place.
        assertEquals(listOf(a(0, 0), a(0, 2), a(1, 0)), digiDollarWindow(all - a(0, 1), external = 3, internal = 1))
    }

    @Test fun `with stop-at-first-unanswered nothing is asked after an address goes unanswered`() = runBlocking {
        val askedOf = mutableListOf<String>()
        val answers = mapOf("a" to DigiDollarHoldingResult(0L, 0, emptyList()), "b" to null)
        val lookup = DigiDollarHoldingLookup(stopAtFirstUnanswered = true) { askedOf += it; answers[it] }
        assertNotNull(lookup.holdingFor("a"))
        assertFalse(lookup.stopped)
        assertNull(lookup.holdingFor("b"))
        assertTrue("an unanswered address did not stop the lookup", lookup.stopped)
        assertNull("an address not asked reads as answered", lookup.holdingFor("c"))
        assertNotNull("an answer already given was lost", lookup.holdingFor("a"))
        assertEquals("the endpoint was asked again after it went unanswered", listOf("a", "b"), askedOf)

        // Through the assembly: the wallet reads as not finished, never as holding none.
        val scan = DigiDollarHoldingLookup(stopAtFirstUnanswered = true) { askedOf += it; null }
        val result = DigiDollarScan.assemble(
            addresses = listOf("x", "y", "z").mapIndexed { i, dd -> DigiDollarAddress(dd, "ef".repeat(32), 0, i) },
            holdingFor = { scan.holdingFor(it.ddAddress) },
            outputsFor = { null },
        )
        assertFalse(result.reachable)
        assertEquals(listOf("a", "b", "x"), askedOf)
    }

    @Test fun `Recover funds' lookup asks every address, each once`() = runBlocking {
        val askedOf = mutableListOf<String>()
        val lookup = DigiDollarHoldingLookup(stopAtFirstUnanswered = false) { askedOf += it; null }
        for (dd in listOf("a", "b", "a", "c")) assertNull(lookup.holdingFor(dd))
        assertFalse(lookup.stopped)
        assertEquals(listOf("a", "b", "c"), askedOf)
    }

    /** Source gate: one copy of "classify, then DigiDollar", used by both scans. */
    @Test fun `both scans go through the one classify-then-DigiDollar path`() {
        val src = File("src/main/java/app/aroundtheblock/wallet/core/recovery/RecoveryScanService.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
        fun body(name: String): String {
            val at = Regex("""fun\s+$name\s*\(""").find(src)?.range?.last ?: error("no fun $name: update the gate")
            val close = Regex("""\)\s*:\s*State(\.Done)?\s*(=|\{)""").find(src, at)?.range?.last ?: error("no body of $name")
            val open = src.indexOf('{', close)
            var depth = 0
            for (i in open until src.length) when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
            error("unbalanced body of $name")
        }
        for (name in listOf("scanFromSeed", "scanPhrase")) {
            assertEquals("$name does not use the shared path once", 1, Regex("""\bclassifyThenDigiDollar\s*\(""").findAll(body(name)).count())
            assertFalse("$name classifies on its own", Regex("""\bclassifyWidening\s*\(""").containsMatchIn(body(name)))
        }
        assertTrue("scan is not scanPhrase", Regex("""\):\s*State\s*=\s*scanPhrase\(mnemonic,\s*passphrase,\s*digiDollar\)""").containsMatchIn(src))

        // Production asks the REAL lookups. Every behavioural test above passes its own, and the app
        // tests mock the service, so nothing else fails when a default becomes `{ null }`: the bug
        // this item fixes (the quick scan never asks), and Recover funds losing its dollars with it.
        fun defaultOf(function: String, parameter: String): String? {
            val at = Regex("""fun\s+$function\s*\(""").find(src)?.range?.last ?: error("no fun $function: update the gate")
            val end = Regex("""\)\s*:\s*State(\.Done)?\s*(=|\{)""").find(src, at)?.range?.first ?: error("no end of $function's parameters")
            return Regex("""\b$parameter\s*:[^=]*(?:->[^=]*)?=\s*([^\n]*?),?\s*\n""").find(src.substring(at, end + 1) + "\n")?.groupValues?.get(1)?.trim()
        }
        assertEquals("Recover funds does not ask the DigiDollar endpoint", "::digiDollarOfSeed", defaultOf("classifyThenDigiDollar", "digiDollar"))
        assertEquals("the phrase scan does not derive the DigiDollar addresses", "::digiDollarAddressesToCap", defaultOf("scanPhrase", "digiDollarAddresses"))
        assertEquals("the phrase scan does not ask the DigiDollar endpoint", "::digiDollarOfAddresses", defaultOf("scanPhrase", "digiDollarLookup"))
        assertEquals("the phrase scan's budget is not the constant", "QUICK_DIGI_DOLLAR_BUDGET_MS", defaultOf("scanPhrase", "digiDollarBudgetMs"))
        assertTrue("scanFromSeed overrides the shared path's lookup",
            Regex("""\bclassifyThenDigiDollar\(\s*seedBytes\s*\)""").containsMatchIn(body("scanFromSeed")))

        // The three lookups are thin: each is the one scan (scanDigiDollar) or the one derivation.
        assertTrue("Recover funds' lookup is not the full scan over its seed", Regex(
            """fun\s+digiDollarOfSeed\(seedBytes: ByteArray\)[^=]*=\s*scanDigiDollar\(stopAtFirstUnanswered\s*=\s*false\)\s*\{\s*external,\s*internal\s*->\s*DigiDollarAddress\.parseAll\(NativeBridge\.deriveDigiDollarAddresses\(seedBytes,\s*external,\s*internal\)\)\s*\}""",
        ).containsMatchIn(src))
        assertTrue("the phrase scan's lookup is not the scan over its derived addresses, stopped at the first unanswered", Regex(
            """fun\s+digiDollarOfAddresses\(all: List<DigiDollarAddress>\)[^=]*=\s*scanDigiDollar\(stopAtFirstUnanswered\s*=\s*true\)\s*\{\s*external,\s*internal\s*->\s*digiDollarWindow\(all,\s*external,\s*internal\)\s*\}""",
        ).containsMatchIn(src))
        assertTrue("the phrase scan does not derive to the rule's cap", Regex(
            """NativeBridge\.deriveDigiDollarAddresses\(seedBytes,\s*DigiDollarGapRule\.cap\(0\),\s*DigiDollarGapRule\.cap\(1\)\)""",
        ).containsMatchIn(src))
        // scanDigiDollar: asked through the one lookup, and ended when it stopped.
        assertTrue("scanDigiDollar does not ask the endpoint",
            Regex("""DigiDollarHoldingLookup\(stopAtFirstUnanswered\)\s*\{\s*utxoSource\.fetchDigiDollar\(it\)\s*\}""").containsMatchIn(src))
        assertTrue("scanDigiDollar's answers do not come from the lookup", Regex("""val\s+h\s*=\s*lookup\.holdingFor\(a\.ddAddress\)""").containsMatchIn(src))
        assertTrue("scanDigiDollar goes on after the lookup stopped", Regex("""if\s*\(lookup\.stopped\)\s*return\s+result\b(?!\.)""").containsMatchIn(src))
        // In the phrase scan the seed is zeroed in the finally of the derivation, before the lookup.
        assertTrue("the phrase scan does not zero the seed between deriving and asking", Regex(
            """if\s*\(digiDollar\)\s*digiDollarAddresses\(s\)\s*else\s*null\s*\}\s*finally\s*\{\s*s\.fill\(0\)\s*\}\s*addresses\?\.let\s*\{\s*withinBudget\(digiDollarBudgetMs\)\s*\{\s*digiDollarLookup\(it\)\s*\}\s*\}""",
        ).containsMatchIn(body("scanPhrase")))
        // No second caller: the scan is called by its two lookups, and each lookup and the
        // derivation is named once, as a default.
        assertEquals("scanDigiDollar has a caller other than the two lookups", 2, Regex("""(?<!fun )\bscanDigiDollar\s*\(""").findAll(src).count())
        for (name in listOf("digiDollarOfSeed", "digiDollarOfAddresses", "digiDollarAddressesToCap")) {
            assertEquals("$name is used other than as the one default", 1, Regex("""(?<!fun )\b$name\b""").findAll(src).count())
            assertEquals("$name is not a default", 1, Regex("""=\s*::$name\b""").findAll(src).count())
        }
    }
}
