package app.aroundtheblock.wallet.core.recovery

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
 *  - the seed handed to the lookup is zeroed when the scan ends, on success and on a throw.
 *
 * NativeBridge cannot load on the host JVM, so the scan is driven through `scanPhrase`, whose
 * parameters stand in for the native calls. The DigiDollar lookup itself (`scanDigiDollar`: the
 * gap rule and the address derivation, both native) is stood in for whole; what it does with an
 * endpoint's answers is [DigiDollarScan]'s, tested there.
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

    /** The seed the scan derives, and the one it handed to the DigiDollar lookup. */
    private class Seeds {
        val derived = ByteArray(64) { (it + 1).toByte() }
        var handed: ByteArray? = null
        var aliveWhenHanded = false
    }

    private fun scan(
        seeds: Seeds = Seeds(),
        service: RecoveryScanService = service(),
        digiDollar: suspend (ByteArray) -> DigiDollarScan.Result?,
    ): RecoveryScanService.State = runBlocking {
        service.scanPhrase(
            mnemonic = phrase,
            passphrase = null,
            seedOf = { _, _ -> seeds.derived },
            derive = { _, ps -> ps.associateWith { listOf(DerivedAddress(addr, chain = 0, index = 0)) } },
            outputScripts = { emptySet() },
            scriptOf = { null },
            digiDollar = { seed ->
                seeds.handed = seed
                seeds.aliveWhenHanded = seed.any { it != 0.toByte() }
                digiDollar(seed)
            },
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
        assertTrue("scan is not scanPhrase", Regex("""\):\s*State\s*=\s*scanPhrase\(mnemonic,\s*passphrase\)""").containsMatchIn(src))
        // Called only as the shared path's and scanPhrase's default lookup: no second caller.
        assertEquals("scanDigiDollar is called outside the shared path", 0, Regex("""(?<!::)\bscanDigiDollar\s*\((?!seedBytes: ByteArray\))""").findAll(src).count())
    }
}
