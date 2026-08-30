package io.digibyte.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text gates for the two `KeyStoreManager` items from the 2026-08-30 audit (F9, F2-small).
 * The JVM suite cannot touch AndroidKeyStore, so these pin the source; each was RED against the
 * shipped tree.
 */
class KeyStoreHygieneSourceGateTest {

    /**
     * `KeyStoreManagerTest` deleted `dgb_wallet_master` — the production seed-wrapping key — in
     * `@Before`/`@After`. Run on a device that holds a wallet, that bricks the wallet
     * (`StaleDataWiper` already records "never delete dgb_wallet_master"). The instrumented test
     * must construct the manager on its own alias; the production default must not appear in it.
     */
    @Test
    fun `KeyStoreManagerTest never operates on the production key alias`() {
        val test = findSource("core/src/androidTest/java/io/digibyte/core/security/KeyStoreManagerTest.kt").readText()
        assertFalse("instrumented test must not name the production alias", test.contains("dgb_wallet_master"))
        assertFalse("instrumented test must not construct KeyStoreManager on the default alias",
            Regex("""KeyStoreManager\(\s*\)""").containsMatchIn(test))
        assertTrue("instrumented test must pass a test alias", test.contains("alias ="))
    }

    @Test
    fun `KeyStoreManager takes an injectable alias that defaults to the production one`() {
        val src = findSource("core/src/main/java/io/digibyte/core/security/KeyStoreManager.kt").readText()
        assertTrue(Regex("""alias:\s*String\s*=\s*KEY_ALIAS""").containsMatchIn(src))
    }

    /**
     * CLAUDE.md and THREAT_MODEL.md assert the seed key is hardware-backed; nothing ever checked.
     * `createKey` must query `KeyInfo.isInsideSecureHardware` after generation and log it, so a
     * device whose Keystore silently fell back to software is visible in logcat. It must never
     * throw — a probe failure is not a reason to refuse wallet creation.
     */
    @Test
    fun `createKey probes KeyInfo hardware backing without a throwing path`() {
        val src = findSource("core/src/main/java/io/digibyte/core/security/KeyStoreManager.kt").readText()
        val createKey = src.substringAfter("fun createKey(").substringBefore("fun encrypt(")
        assertTrue("createKey must query KeyInfo", createKey.contains("KeyInfo::class.java"))
        assertTrue("createKey must log isInsideSecureHardware", createKey.contains("isInsideSecureHardware"))
        assertTrue("the probe must be wrapped in runCatching", createKey.contains("runCatching"))
    }

    private fun findSource(path: String): File {
        val candidates = listOf(path, "../$path", "../../$path")
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: candidates.map { File(System.getProperty("user.dir"), it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$path not found")
    }
}
