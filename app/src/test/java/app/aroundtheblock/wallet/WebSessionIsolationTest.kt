package app.aroundtheblock.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebSessionIsolationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun webDir(): File = tmp.newFolder("app_webview").also { File(it, "Cookies").writeText("A") }

    @Test fun `owner A active B clears the web data and sets owner B`() {
        val dir = webDir(); var owner: String? = "A"
        assertTrue(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(dir) }, "B").run())
        assertFalse(dir.exists()); assertEquals("B", owner)
    }

    @Test fun `owner equals active deletes nothing`() {
        val dir = webDir(); var owner: String? = "B"
        assertTrue(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(dir) }, "B").run())
        assertTrue(File(dir, "Cookies").exists())
    }

    @Test fun `an absent owner with web data clears it`() {
        val dir = webDir(); var owner: String? = null
        assertTrue(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(dir) }, "B").run())
        assertFalse(dir.exists()); assertEquals("B", owner)
    }

    @Test fun `upgrade with only legacy adopts it as owner without deleting`() {
        val dir = webDir(); var owner: String? = null
        assertTrue(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(dir) }, "legacy", { true }).run())
        assertTrue(File(dir, "Cookies").exists()); assertEquals("legacy", owner)
    }

    @Test fun `absent owner with another wallet present clears even if legacy is active`() {
        val dir = webDir(); var owner: String? = null
        assertTrue(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(dir) }, "legacy", { false }).run())
        assertFalse(dir.exists()); assertEquals("legacy", owner)
    }

    @Test fun `absent owner with only legacy but another wallet active clears`() {
        val dir = webDir(); var owner: String? = null
        assertTrue(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(dir) }, "B", { true }).run())
        assertFalse(dir.exists())
    }

    @Test fun `a delete that cannot be verified leaves the owner and refuses`() {
        val dir = webDir(); var owner: String? = "A"
        val stuck = object : File(dir.path) {
            override fun exists() = true
        }
        assertFalse(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(stuck) }, "B").run())
        assertEquals("A", owner)
    }

    @Test fun `suffixed profile directories are found`() {
        val data = tmp.newFolder("data"); val cache = tmp.newFolder("cache")
        File(data, "app_webview").mkdir(); File(data, "app_webview_proc").mkdir(); File(data, "other").mkdir()
        val names = WebSessionIsolation.webDataDirs(data, cache).map { it.name }
        assertTrue(names.containsAll(listOf("app_webview", "app_webview_proc", "WebView")) && "other" !in names)
    }

    @Test fun `the web data list covers the Chromium cache and the HTTP auth database`() {
        val data = tmp.newFolder("data2"); val cache = tmp.newFolder("cache2")
        val dirs = WebSessionIsolation.webDataDirs(data, cache)
        assertTrue(File(cache, "org.chromium.android_webview") in dirs)
        assertTrue(File(data, "databases/http_auth.db") in dirs)
    }

    /** After a full wipe a new seed may be created as legacy again: owner == active must not let the
     *  wiped seed's DigiStamp session through. The wipe leaves the sentinel, never the old owner. */
    @Test fun `after a wipe the next launch clears the web data even for legacy`() {
        val dir = webDir(); var owner: String? = "legacy"
        val eraser = IdentitySessionEraser({ true }, { true }, { it() }, markWebDataWiped = { owner = WebSessionIsolation.OWNER_WIPED; true })

        assertTrue(eraser.eraseIdentitySessions())
        assertEquals(WebSessionIsolation.OWNER_WIPED, owner)
        assertTrue(WebSessionIsolation({ owner }, { owner = it; true }, { listOf(dir) }, "legacy", { true }).run())
        assertFalse("the wiped seed's web data survived the next launch", dir.exists())
        assertEquals("legacy", owner)
    }

    @Test fun `the sentinel is no wallet id`() {
        assertFalse(app.aroundtheblock.wallet.core.wallets.WalletIds.isValid(WebSessionIsolation.OWNER_WIPED))
    }

    @Test fun `marking the web data wiped writes the sentinel with a checked commit`() {
        val editor = io.mockk.mockk<android.content.SharedPreferences.Editor>()
        io.mockk.every { editor.putString(any(), any()) } returns editor
        io.mockk.every { editor.commit() } returns true
        val prefs = io.mockk.mockk<android.content.SharedPreferences> { io.mockk.every { edit() } returns editor }
        val ctx = io.mockk.mockk<android.content.Context>(relaxed = true)
        io.mockk.every { ctx.applicationContext } returns ctx
        io.mockk.every { ctx.getSharedPreferences(WebSessionIsolation.PREFS, any()) } returns prefs

        assertTrue(WebSessionIsolation.markWiped(ctx))
        io.mockk.verify { editor.putString(WebSessionIsolation.KEY_OWNER, WebSessionIsolation.OWNER_WIPED) }
        io.mockk.every { editor.commit() } returns false
        assertFalse("a write that did not land was reported", WebSessionIsolation.markWiped(ctx))
    }
}
