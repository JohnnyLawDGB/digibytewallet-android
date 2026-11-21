package app.aroundtheblock.wallet

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [IdentitySessionEraser] ends two unrelated sessions in one call. What matters is that one
 * of them never decides whether the other is attempted, and that the result is true only
 * when both were confirmed. The WebView and Hub calls themselves need a device; here they are
 * the seams the production constructor fills in.
 */
class IdentitySessionEraserTest {

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    private val inline: (() -> Boolean) -> Boolean = { it() }

    @Test fun `both sessions are ended and the result is theirs`() {
        val calls = mutableListOf<String>()
        val eraser = IdentitySessionEraser({ calls += "hub"; true }, { calls += "web"; true }, inline)

        assertTrue(eraser.eraseIdentitySessions())
        assertEquals(listOf("hub", "web"), calls)
    }

    @Test fun `a hub session that cannot be ended does not skip the web session`() {
        for (hub in listOf<() -> Boolean>({ false }, { throw IllegalStateException("client unavailable") })) {
            var webRan = false
            val eraser = IdentitySessionEraser(hub, { webRan = true; true }, inline)

            assertFalse(eraser.eraseIdentitySessions())
            assertTrue("the web session was skipped", webRan)
        }
    }

    @Test fun `the wipe marks the web data wiped, and a mark that does not land is reported`() {
        var marked = 0
        assertTrue(IdentitySessionEraser({ true }, { true }, inline, markWebDataWiped = { marked++; true }).eraseIdentitySessions())
        assertEquals(1, marked)
        for (mark in listOf<() -> Boolean>({ false }, { throw IllegalStateException() })) {
            var webRan = false
            assertFalse(IdentitySessionEraser({ true }, { webRan = true; true }, inline, markWebDataWiped = mark).eraseIdentitySessions())
            assertTrue("the web session was skipped", webRan)
        }
    }

    @Test fun `a switch ends the web session without marking it wiped`() {
        var marked = 0
        assertTrue(IdentitySessionEraser({ true }, { true }, inline, markWebDataWiped = { marked++; true }).endWebSessionOnly())
        assertEquals(0, marked)
    }

    @Test fun `a web session that cannot be ended is reported`() {
        assertFalse(IdentitySessionEraser({ true }, { false }, inline).eraseIdentitySessions())
        assertFalse(IdentitySessionEraser({ true }, { throw IllegalStateException() }, inline).eraseIdentitySessions())
    }

    // ── what ending the web session rests on ─────────────────────────────────

    private fun webSessionEnd(
        releaseRetainedView: () -> Unit = {},
        removeCookies: () -> Unit = {},
        flushCookies: () -> Unit = {},
        clearWebStorage: () -> Unit = {},
        removeSessionFiles: () -> Boolean = { true },
    ) = WebSessionEnd(releaseRetainedView, removeCookies, flushCookies, clearWebStorage, removeSessionFiles)

    @Test fun `the web session's result is the removal the store took, with nothing read back`() {
        // The cookie store takes the removal on its own thread and answers through its callback, so
        // a question put to it in the next statement is about the state BEFORE the removal. The
        // evidence is the removal it accepted and the flush that forced its file to disk — and the
        // session's own files are not touched when the store is there to take it.
        val calls = mutableListOf<String>()
        val end = webSessionEnd(
            releaseRetainedView = { calls += "view" },
            removeCookies = { calls += "remove" },
            flushCookies = { calls += "flush" },
            clearWebStorage = { calls += "storage" },
            removeSessionFiles = { calls += "files"; true },
        )

        assertTrue(end.end())
        assertEquals(listOf("view", "remove", "flush", "storage"), calls)
    }

    @Test fun `a platform with no web view implementation removes the session's files instead`() {
        var removals = 0
        val absent = webSessionEnd(
            removeCookies = { throw IllegalStateException("no WebView implementation") },
            removeSessionFiles = { removals++; true },
        )

        assertTrue(absent.end())
        assertEquals(1, removals)

        // …and files that could not be removed are not a session that was ended.
        assertFalse(
            webSessionEnd(
                removeCookies = { throw IllegalStateException("no WebView implementation") },
                removeSessionFiles = { false },
            ).end(),
        )
    }

    @Test fun `the storage is still emptied when the retained view cannot be released`() {
        var storageCleared = false
        val end = webSessionEnd(
            releaseRetainedView = { throw IllegalStateException("view unavailable") },
            clearWebStorage = { storageCleared = true },
        )

        assertFalse("a view that would not let go is not a session that was ended", end.end())
        assertTrue("the jar and the storage were skipped", storageCleared)
    }

    @Test fun `the web session is ended through the main-thread runner, and its refusal is reported`() {
        var ranInside = false
        val eraser = IdentitySessionEraser({ true }, { ranInside = true; true }, { block -> block() })
        assertTrue(eraser.eraseIdentitySessions())
        assertTrue(ranInside)

        // A main thread that never ran the block within the wait: not confirmed.
        assertFalse(IdentitySessionEraser({ true }, { true }, { false }).eraseIdentitySessions())
    }

    /**
     * MARKET-SIGNIN-ID-HOST: the Market's session spans two hosts. The wipe and the wallet switch end
     * it by emptying the whole cookie jar and all web storage; nothing here is done per host, so a
     * second host cannot be left behind. A per-host removal would be a list to keep in step.
     */
    @Test fun `the web session is ended for every host at once, never host by host`() {
        val src = java.io.File("src/main/java/app/aroundtheblock/wallet/IdentitySessionEraser.kt").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue("the whole jar is no longer emptied", src.contains("CookieManager.getInstance().removeAllCookies"))
        assertTrue("web storage is no longer emptied whole", src.contains("WebStorage.getInstance().deleteAllData()"))
        for (perHost in listOf(".setCookie(", ".getCookie(", "deleteOrigin(", "digistamp.co")) {
            assertTrue("the web session is ended per host ($perHost)", !src.contains(perHost))
        }
    }
}

