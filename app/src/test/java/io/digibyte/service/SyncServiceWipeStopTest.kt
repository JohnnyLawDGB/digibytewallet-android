package io.digibyte.service

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate for the sync service's part in a wipe: it stands down before the wipe erases
 * anything, and nothing it held of the wiped wallet is written afterwards.
 *
 * A scan because the service is an Android component this JVM cannot run. The wipe's side — that
 * it stops every registered session before the first erase — is covered by
 * `WalletSessionAfterWipeTest` in `core`; this pins the service's side of that contract:
 *  - it registers with the wallet in onCreate and deregisters in onDestroy;
 *  - standing down stops its loops, drops what it captured and not yet wrote, and stops the service;
 *  - its teardown writes nothing of the wiped wallet, and its native callbacks do nothing once it
 *    has stood down (a callback still in flight from the old session must not put a store back);
 *  - a start delivered to a service that stood down does not revive it; the next wallet this
 *    process opens gets a fresh one.
 */
class SyncServiceWipeStopTest {

    private val service = KotlinSourceGate.of(File("src/main/java/io/digibyte/service/SyncService.kt").readText())

    /** The body of the first function named [name], braces matched. */
    private fun body(name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(service.code) ?: error("no fun $name in SyncService.kt")
        val open = service.code.indexOf('{', declared.range.last)
        return service.blockAt(open) ?: error("unbalanced body of $name")
    }

    private fun text(range: IntRange) = service.code.substring(range)

    @Test fun `the service registers with the wallet for as long as it exists`() {
        assertEquals(1, service.calls("walletManager.addSessionStop", body("onCreate")).size)
        assertEquals(1, service.calls("walletManager.removeSessionStop", body("onDestroy")).size)
    }

    @Test fun `standing down stops the loops, drops the unwritten captures and stops the service`() {
        val standDown = body("standDownForWipe")
        val code = text(standDown)
        // Stopped AND waited for (bounded): a plain cancel() lets a step already under way write
        // after the erase, so the join inside the bound is what is pinned, not any cancel.
        val bounded = service.calls("withTimeoutOrNull", standDown)
        assertEquals("the stand-down does not wait, within a bound, for the loops to end", 1, bounded.size)
        assertEquals("the bound is not the stand-down's own", listOf("STAND_DOWN_BOUND_MS"), bounded.single().arguments)
        val joined = text(service.trailingBlock(bounded.single()) ?: error("no block under the bound"))
        for (scope in listOf("serviceScope", "recoveryScope")) {
            assertTrue(
                "$scope is not stopped and waited for when the service stands down",
                Regex("""$scope\s*\.\s*coroutineContext\s*\[\s*Job\s*]\s*\?\s*\.\s*cancelAndJoin\s*\(\s*\)""").containsMatchIn(joined),
            )
        }
        for (capture in listOf("pendingFilterHeaders", "pendingCfLedger", "lastSavedBlocksData")) {
            assertTrue("$capture outlives the stand-down", Regex("""$capture\s*=\s*null""").containsMatchIn(code))
        }
        assertTrue("the stand-down does not stop the service", service.calls("stopSelf", standDown).isNotEmpty())
        assertTrue("the stand-down leaves the service in the foreground", service.calls("stopForeground", standDown).isNotEmpty())
    }

    @Test fun `teardown after a stand-down writes nothing of the wiped wallet`() {
        val onDestroy = body("onDestroy")
        val branches = service.branchesOn("!stoodDownForWipe", onDestroy)
        assertTrue("onDestroy has no branch for a service that did not stand down", branches.isNotEmpty())
        for (write in listOf("persistBlocks", "flushFilterHeaders", "persistPeerPenalties")) {
            val calls = service.calls(write, onDestroy)
            assertTrue("scanner is blind: onDestroy no longer calls $write", calls.isNotEmpty())
            val outside = calls.filterNot { c -> branches.any { c.range.first in it } }
            assertTrue("onDestroy runs $write after a stand-down", outside.isEmpty())
        }
    }

    @Test fun `every native callback does nothing once the service stood down`() {
        val handler = Regex("""private\s+val\s+syncCallback\s*=\s*object\s*:\s*NativeCallback\s*\{""").find(service.code)
            ?: error("scanner is blind: no syncCallback object")
        val block = service.blockAt(handler.range.last) ?: error("unbalanced syncCallback")
        val overrides = Regex("""override\s+fun\s+(\w+)\s*\([^)]*\)\s*\{""").findAll(service.code)
            .filter { it.range.first in block }
            .toList()
        assertTrue("scanner is blind: found ${overrides.size} callbacks", overrides.size >= 10)
        val unguarded = overrides.filterNot { m ->
            service.code.substring(m.range.last + 1).trimStart().startsWith("if (stoodDownForWipe) return")
        }.map { it.groupValues[1] }
        assertEquals("callbacks that still act after the stand-down", emptyList<String>(), unguarded)
    }

    @Test fun `a start delivered after the stand-down does not revive the service`() {
        val start = body("onStartCommand")
        val guard = service.branchesOn("stoodDownForWipe", start)
        assertEquals("onStartCommand does not refuse a service that stood down", 1, guard.size)
        assertTrue("the refusal must leave onStartCommand", text(guard.single()).contains("return START_NOT_STICKY"))
        val firstSetup = service.calls("NativeBridge.setCallbackHandler", start).minOfOrNull { it.range.first } ?: error("scanner is blind")
        assertTrue("the refusal comes after the service already set itself up", guard.single().first < firstSetup)
        assertTrue(
            "the service does not come back for the next wallet this process opens",
            service.calls("startAgainForTheNextWallet", body("standDownForWipe")).isNotEmpty(),
        )
    }

    @Test fun `the next wallet opened in this process gets a service, and not before`() {
        val watcher = body("startAgainForTheNextWallet")
        val code = text(watcher)
        val left = Regex("""\.\s*dropWhile\s*\{\s*it\s+is\s+WalletState\.Unlocked\s*}""").find(code)
            ?: error("the watcher does not wait for the wipe to move the wallet out of Unlocked")
        val back = Regex("""\.\s*first\s*\{\s*it\s+is\s+WalletState\.Unlocked\s*}""").find(code)
            ?: error("the watcher does not wait for the next wallet to be open")
        assertTrue("the watcher waits for the next wallet before the wipe has taken the old one", left.range.first < back.range.first)
        val starts = service.calls("ContextCompat.startForegroundService", watcher)
        assertEquals("the watcher starts nothing for the next wallet", 1, starts.size)
        assertEquals(
            "the watcher starts something other than this service",
            "Intent(app, SyncService::class.java)", starts.single().arguments.getOrNull(1),
        )
        assertTrue("the start comes before the next wallet is open", starts.single().range.first - watcher.first > back.range.last)
        val gone = code.indexOf("instanceAlive.get()")
        assertTrue("the start does not wait for the instance that stood down", gone >= 0 && gone < starts.single().range.first - watcher.first)
    }

    /**
     * The service's own writers of the wiped stores ask whether it stood down AFTER the last native
     * read they make and BEFORE the write: a step that outlived the bounded join then writes
     * nothing, including what a native call it was parked in hands back late.
     */
    @Test fun `the service's writers write nothing once it stood down`() {
        val writes = listOf(".commit()", ".apply()", "Store.write(")
        for (writer in listOf("persistBlocks", "flushFilterHeaders", "persistPeerPenalties", "persistWalletTransactionsCheckpoint", "startFilterHeaderWriter")) {
            val code = text(body(writer))
            val firstWrite = writes.map { code.indexOf(it) }.filter { it >= 0 }.minOrNull()
                ?: error("scanner is blind: $writer writes nothing")
            val lastRead = code.lastIndexOf("NativeBridge.", firstWrite).coerceAtLeast(0)
            assertTrue(
                "$writer writes without asking whether the service stood down, after its last native read",
                code.substring(lastRead, firstWrite).contains("stoodDownForWipe"),
            )
        }
        // The wallet's transaction set, wherever the service writes it.
        val written = service.written
        val sites = Regex("""putString\(\s*"saved_transactions"""").findAll(written).map { it.range.first }.toList()
        assertTrue("scanner is blind: found ${sites.size} writes of the transaction set", sites.size >= 3)
        for (site in sites) {
            val read = written.lastIndexOf("getSerializedTransactions()", site)
            assertTrue("scanner is blind: no native read before the write at $site", read >= 0)
            assertTrue(
                "the transaction set is written without asking whether the service stood down (at $site)",
                service.code.substring(read, site).contains("stoodDownForWipe"),
            )
        }
    }
}
