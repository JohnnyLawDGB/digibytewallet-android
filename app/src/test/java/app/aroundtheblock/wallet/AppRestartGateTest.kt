package app.aroundtheblock.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One restart path (F16): no `exit(` anywhere, `killProcess(` only in the wipe hand-over file AppRestart reuses. */
class AppRestartGateTest {
    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `one restart path`() {
        val root = File("src/main/java")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scanner is blind: read only ${files.size} files", files.size >= 80)
        val exits = files.filter { code(it).contains(Regex("""Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exit\s*\(""")) }
        assertEquals("Runtime.exit outside AppRestart", emptyList<String>(), exits.map { it.name })
        val kills = files.filter { code(it).contains(Regex("""Process\s*\.\s*killProcess\s*\(""")) }.map { it.name }
        assertEquals(listOf("FreshStartAfterWipe.kt"), kills)
    }
}
