package io.digibyte.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text gate on the logcat-leak class from the 2026-08-30 external audit (F5/F6).
 *
 * These are static checks on the Kotlin source, not behavioural tests: the JVM unit suite
 * cannot run `android.util.Log`, and a redaction regression is a one-line edit that no
 * runtime test would exercise. Each assertion names the exact line that shipped the leak so
 * a future reader knows what it is guarding, and each one was RED against the shipped tree
 * before the redaction landed.
 */
class LogRedactionSourceGateTest {

    /** `DigiScopeClient.kt:131` logged `body=${responseBody?.take(300)}` — the login callback
     *  body IS the Hub session JWT. Logcat is readable by any app with READ_LOGS on older
     *  devices and by anyone with adb on a debug build; a JWT there is a Hub session leak. */
    @Test
    fun `DigiScopeClient never logs the login callback body`() {
        val logLines = logLinesOf("core/src/main/java/io/digibyte/core/digiscope/DigiScopeClient.kt")
        // The shipped line was `body=${responseBody?.take(300)}`; a length is fine, the content is not.
        val bodyValue = Regex("""\$\{?responseBody(\?\.take\(|\}|\s|"|$)""")
        val offenders = logLines.filter { it.contains("body=") || bodyValue.containsMatchIn(it) }
        assertTrue("login callback body must not be logged (it carries the JWT): $offenders",
            offenders.isEmpty())
    }

    /** CLAUDE.md promises "wallet address redacted from logs" for Digi-ID. `DigiScopeClient.kt:100`
     *  and `DigiIdManager.kt:105` both interpolated the full signing address. */
    @Test
    fun `Digi-ID login paths never log the wallet address`() {
        listOf(
            "core/src/main/java/io/digibyte/core/digiscope/DigiScopeClient.kt",
            "core/src/main/java/io/digibyte/core/digiid/DigiIdManager.kt",
        ).forEach { path ->
            val offenders = logLinesOf(path).filter { it.contains("\$address") || it.contains("\${address") }
            assertTrue("$path must not log the wallet address: $offenders", offenders.isEmpty())
        }
    }

    /** The asset clients log the failing `$url` on every non-2xx / throw, and the history
     *  endpoint URL is `.../history/<wallet address>` — the same address leak by another door.
     *  `DigistampClient` carries no address in its routes today; it is gated so a future
     *  address-bearing route cannot re-open the leak silently. */
    @Test
    fun `asset network clients never log a URL that carries a wallet address`() {
        listOf(
            "core/src/main/java/io/digibyte/core/asset/network/DigiScopeAssetClient.kt",
            "core/src/main/java/io/digibyte/core/asset/network/DigistampAssetClient.kt",
            "core/src/main/java/io/digibyte/core/digistamp/DigistampClient.kt",
        ).forEach { path ->
            val offenders = logLinesOf(path).filter { it.contains("\$url") || it.contains("\${url") }
            assertTrue("$path must log a redacted endpoint, not the raw URL: $offenders", offenders.isEmpty())
        }
    }

    /** No Kotlin log line anywhere in core/ or app/ may interpolate a token, JWT, seed,
     *  mnemonic, passphrase, private key, or PIN value. Naming a concept in a message is fine
     *  ("no token") — interpolating the value (`$token`, `${jwt`) is not. */
    @Test
    fun `no Kotlin log line interpolates a secret value`() {
        val secretInterpolation = Regex(
            """\$\{?\s*(token|jwt|sessionToken|seed|mnemonic|phrase|passphrase|privKey|privateKey|wif|pin|pinHash)\b""",
            RegexOption.IGNORE_CASE
        )
        val violations = mutableListOf<String>()
        listOf("core/src/main/java", "app/src/main/java").forEach { root ->
            findSourceRoot(root).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                collectLogStatements(file.readText()).forEach { stmt ->
                    if (secretInterpolation.containsMatchIn(stmt)) violations.add("${file.name}: $stmt")
                }
            }
        }
        assertTrue("log statements interpolating a secret value: $violations", violations.isEmpty())
    }

    // Multi-line Log.x( ... ) calls are common in this codebase, so a line-based grep would miss
    // an argument on the next line. Collect each call from `Log.x(` to its balancing paren.
    private fun collectLogStatements(content: String): List<String> {
        val out = mutableListOf<String>()
        val start = Regex("""\bLog\.[diwev]\(""")
        var m = start.find(content)
        while (m != null) {
            var depth = 0
            var i = m.range.last
            while (i < content.length) {
                when (content[i]) { '(' -> depth++; ')' -> depth-- }
                if (depth == 0) break
                i++
            }
            out.add(content.substring(m.range.first, minOf(i + 1, content.length)).replace('\n', ' '))
            m = start.find(content, i + 1)
        }
        return out
    }

    private fun logLinesOf(path: String): List<String> = collectLogStatements(findSource(path).readText())

    private fun findSource(path: String): File {
        val candidates = listOf(path, "../$path", "../../$path")
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: candidates.map { File(System.getProperty("user.dir"), it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$path not found")
    }

    private fun findSourceRoot(path: String): File {
        val f = findSource(path)
        assertTrue("$path is not a directory", f.isDirectory)
        return f
    }
}
