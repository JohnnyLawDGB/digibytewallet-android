package app.aroundtheblock.wallet.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Transaction log tags (4.1.3, review R7). The known answers are HMAC-SHA256 computed outside the
 * app (Python hmac) and are the same ones log_tag_kat holds the C core's BRLogTxTag to, so Kotlin and
 * native lines about one transaction carry one tag.
 */
class LogTagTest {
    private val key = ByteArray(32) { it.toByte() }

    @After fun clear() = LogTag.setKey(null)

    @Test fun `tags match the independent HMAC known answers`() {
        LogTag.setKey(key)
        assertEquals("cd29415f", LogTag.tx("00000000000000000000000000000000000000000000000000000000000000ff"))
        assertEquals("02764d55", LogTag.tx("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"))
        assertEquals("02764d55", LogTag.tx("A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"))
        assertEquals("02764d55:3", LogTag.outpoint("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90:3"))
    }

    @Test fun `no key, or anything that is not a txid, gives no tag`() {
        assertEquals("--------", LogTag.tx("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"))
        LogTag.setKey(key)
        assertEquals("--------", LogTag.tx(null))
        assertEquals("--------", LogTag.tx("abc"))
        assertEquals("--------", LogTag.tx("z".repeat(64)))
    }

    /** Gate: no log line in the app or core sources names a transaction by a txid prefix. */
    @Test fun `no log line carries a txid prefix`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val uiAllowed = setOf("AssetDetailScreen.kt", "AssetSendScreen.kt")   // shown to the user, not logged
        val pattern = Regex("""\$\{[A-Za-z_][A-Za-z_0-9.]*(txid|Txid|txHash|TxHash|hash|Hash)[A-Za-z_0-9]*\??\.take\(\d+\)\}""")
        val hits = listOf("app/src/main/java", "core/src/main/java").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.extension == "kt" && it.name !in uiAllowed }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
            }.toList()
        }
        assertTrue("txid prefixes in log lines (use LogTag.tx): $hits", hits.isEmpty())
    }
}
