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
}
