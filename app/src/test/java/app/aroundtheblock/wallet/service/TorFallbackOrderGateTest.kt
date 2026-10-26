package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate (GUARD): with the user's Tor setting on, app traffic goes direct only after the
 * "Tor unavailable" banner is raised. The sync service raises the banner flag in exactly one
 * place, [SyncService.raiseTorFallback], which sets the flag FIRST and only then tells TorManager
 * the fallback is announced (the call that lets the shared client go direct). Every degradation
 * path goes through it. What the announcement does to the route is covered by
 * `app.aroundtheblock.wallet.di.SharedClientRouteBuilderTest`.
 */
class TorFallbackOrderGateTest {

    private val sync = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncService.kt").readText())

    private fun body(name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(sync.code) ?: error("no fun $name")
        val open = sync.code.indexOf('{', declared.range.last)
        return sync.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `the banner is raised before the fallback is announced, in one place`() {
        val raise = body("raiseTorFallback")
        val sets = Regex("""_torFailureActive\.value\s*=\s*true""").findAll(sync.code).map { it.range.first }.toList()
        assertEquals("the banner flag is raised outside raiseTorFallback", listOf(true), sets.map { it in raise })
        val announces = sync.calls("torManager.announceClearnetFallback")
        assertEquals("the fallback is announced outside raiseTorFallback", 1, announces.size)
        assertTrue(announces.single().range.first in raise)
        assertTrue("the fallback is announced before the banner is raised", sets.single() < announces.single().range.first)
    }

    @Test fun `the banner is lowered when the user turns Tor off`() {
        val collects = Regex("""torManager\.enabled\.collect\s*\{""").findAll(sync.code).toList()
        assertEquals("the sync service does not observe the Tor setting", 1, collects.size)
        val block = sync.blockAt(sync.code.indexOf('{', collects.single().range.first)) ?: error("unbalanced")
        assertTrue(
            "the setting observer does not lower the banner",
            Regex("""_torFailureActive\.value\s*=\s*false""").containsMatchIn(sync.code.substring(block.first, block.last + 1)),
        )
    }

    @Test fun `every path that degrades to clearnet raises the banner through it`() {
        for (site in listOf("startSyncWithTor", "runTorFallbackWatchdog", "runPeerKeepalive")) {
            assertTrue("$site does not degrade through raiseTorFallback", sync.calls("raiseTorFallback", body(site)).isNotEmpty())
        }
        // The fourth: the Tor-state observer in onStartCommand, on TorState.Failed.
        assertEquals(4, sync.calls("raiseTorFallback").size)
        // Every place that clears the C core's proxy while Tor is on is one of these paths.
        for (clear in sync.calls("NativeBridge.clearSocksProxy")) {
            val block = sync.enclosingBlock(clear.range.first) ?: error("unbalanced")
            val disabledBranch = sync.code.substring(maxOf(0, block.first - 40), block.first).contains("else")
            assertTrue(
                "a proxy clear at ${clear.range.first} is neither the Tor-off branch nor followed by the banner",
                disabledBranch || sync.calls("raiseTorFallback", block).isNotEmpty(),
            )
        }
    }
}
