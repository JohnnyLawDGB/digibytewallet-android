package app.aroundtheblock.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Lock decisions never read an unreadable PIN store as "no PIN" (4.1.2 row 23, review R10).
 *
 * The reactive lock asked `try { pinManager.hasPin() } catch (e: Exception) { false }`: a store that
 * threw on a warm resume left the wallet screen up while the wallet was Locked, and the inactivity
 * timer skipped its tick. Both now ask PinManager.pinAssumedForLock(), which counts a throw as a PIN.
 */
class PinReadFailClosedGateTest {

    private val root = File("..").canonicalFile
    private val lockSites = listOf(
        "app/src/main/java/app/aroundtheblock/wallet/ui/navigation/AppNavigation.kt",
        "app/src/main/java/app/aroundtheblock/wallet/MainActivity.kt",
    )

    @Test fun `no lock site turns a failed PIN read into no PIN`() {
        val failOpen = Regex("""hasPin\(\)\s*\}\s*catch\s*\([^)]*\)\s*\{\s*false\s*\}""")
        val hits = lockSites.filter { failOpen.containsMatchIn(File(root, it).readText()) }
        assertEquals("a lock site reads a failed PIN read as no PIN", emptyList<String>(), hits)
    }

    @Test fun `the reactive lock and the inactivity timer ask pinAssumedForLock`() {
        val missing = lockSites.filter { !File(root, it).readText().contains("pinManager.pinAssumedForLock()") }
        assertEquals("a lock site does not ask pinAssumedForLock", emptyList<String>(), missing)
    }
}
