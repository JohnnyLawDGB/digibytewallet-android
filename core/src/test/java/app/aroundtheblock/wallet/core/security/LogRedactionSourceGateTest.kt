package app.aroundtheblock.wallet.core.security

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
        val logLines = logLinesOf("core/src/main/java/app/aroundtheblock/wallet/core/digiscope/DigiScopeClient.kt")
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
            "core/src/main/java/app/aroundtheblock/wallet/core/digiscope/DigiScopeClient.kt",
            "core/src/main/java/app/aroundtheblock/wallet/core/digiid/DigiIdManager.kt",
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
            "core/src/main/java/app/aroundtheblock/wallet/core/asset/network/DigiScopeAssetClient.kt",
            "core/src/main/java/app/aroundtheblock/wallet/core/asset/network/DigistampAssetClient.kt",
            "core/src/main/java/app/aroundtheblock/wallet/core/digistamp/DigistampClient.kt",
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

    /** A full txid in logcat ties the device to an on-chain transaction; R8 keeps the Log calls in
     *  release. The codebase logs a short prefix (`txid.take(12)`), and every log line in core/
     *  and app/ must too: a txid-bearing value (`txid`, `txHash`, `txHashHex`, any name ending in
     *  them) is interpolated only through `.take(` / `.takeLast(`, or as a count. */
    @Test
    fun `no Kotlin log line interpolates a full txid`() {
        val chainId = Regex("""(txid|txhash)""", RegexOption.IGNORE_CASE)
        val bare = Regex("""\$(?!\{)([A-Za-z_]\w*)""")
        val braced = Regex("""\$\{([^{}]*)\}""")
        val shortened = Regex("""\.(take|takeLast)\(|\.size\b|\.count\b|\.isEmpty|\.isNotEmpty""")
        val violations = mutableListOf<String>()
        listOf("core/src/main/java", "app/src/main/java").forEach { root ->
            findSourceRoot(root).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                collectLogStatements(file.readText()).forEach { stmt ->
                    val full = bare.findAll(stmt).map { it.groupValues[1] }.filter { chainId.containsMatchIn(it) } +
                        braced.findAll(stmt).map { it.groupValues[1] }
                            .filter { chainId.containsMatchIn(it) && !shortened.containsMatchIn(it) }
                    if (full.any()) violations.add("${file.name}: ${full.toList()} in $stmt")
                }
            }
        }
        assertTrue("log statements interpolating a full txid:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    /** The gate above must see what it is meant to see. */
    @Test
    fun `the txid gate collects a multi-line log call and Log_wtf`() {
        val stmts = collectLogStatements("Log.w(TAG,\n  \"x \${tx.txid}\")\nLog.wtf(TAG, \"y\")")
        assertTrue(stmts.toString(), stmts.size == 2 && stmts[0].contains("tx.txid"))
    }

    /**
     * 4.1.2 row 7 (K1): a transaction row never goes to logcat. The txid gate above matches names, and
     * `txDetails...forEach { Log.d(TAG, "tx: $it") }` passed it while logging five full rows -- txid,
     * amounts, addresses -- every minute. This one follows the VALUES: a variable assigned from a
     * native call that returns wallet transaction data, any variable derived from one, and `it` inside
     * a block that iterates one, may not be interpolated into a log statement.
     */
    @Test
    fun `no Kotlin log line interpolates a value read from the wallet's transactions`() {
        val violations = mutableListOf<String>()
        listOf("core/src/main/java", "app/src/main/java").forEach { root ->
            findSourceRoot(root).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                txRowLogs(file.readText()).forEach { violations.add("${file.name}: $it") }
            }
        }
        assertTrue("log statements carrying wallet transaction data:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    /** The value gate must see the shape that leaked, and a named variant of it. */
    @Test
    fun `the transaction-value gate catches the row logger it was written for`() {
        val leaked = """
            val txDetails = NativeBridge.getTransactionDetails()
            if (now % 60000 < 5000) {
                txDetails.trim().lines().take(5).forEach { android.util.Log.d("WalletVM", "tx: ${'$'}it") }
            }
        """.trimIndent()
        assertTrue("the original row logger is not caught", txRowLogs(leaked).isNotEmpty())
        val named = "val rows = NativeBridge.getTransactionDetails().lines()\nval first = rows.first()\nLog.i(TAG, \"row ${'$'}first\")"
        assertTrue("a derived variable is not followed", txRowLogs(named).isNotEmpty())
        val counted = "val rows = NativeBridge.getTransactionDetails().lines()\nLog.i(TAG, \"rows=${'$'}{rows.size}\")"
        assertTrue("a count of rows is not a row", txRowLogs(counted).isEmpty())
    }

    private val txDataSource = Regex(
        """NativeBridge\.(getTransactionDetails|getAllTransactionHashes|getTransactionOutputsForHash|""" +
            """getTransactionInputsForHash|getSerializedTransactionForHash|getSpendableDigiByteUtxos|dumpAllAddresses)\("""
    )

    // Per function: a name is a transaction value only in the function that read it (one SyncService
    // function's `raw` is a serialized transaction, another's is a host:port).
    private fun txRowLogs(content: String): List<String> =
        Regex("""(?=\n[ \t]*(?:(?:private|internal|public|override|suspend|inline|protected)\s+)*fun\s)""")
            .split(content).flatMap { txRowLogsIn(it) }

    private fun txRowLogsIn(content: String): List<String> {
        val assign = Regex("""\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+)?=([^\n]*)""")
        val tainted = mutableSetOf<String>()
        var grew = true
        while (grew) {
            grew = false
            assign.findAll(content).forEach { m ->
                val name = m.groupValues[1]; val rhs = m.groupValues[2]
                // Derived: the right-hand side starts from a tainted value (`rows.first()`, `x.trim().lines()`).
                val head = Regex("""^\s*([A-Za-z_]\w*)""").find(rhs)?.groupValues?.get(1)
                if (name !in tainted && (txDataSource.containsMatchIn(rhs) || head in tainted)) {
                    tainted.add(name); grew = true
                }
            }
        }
        if (tainted.isEmpty()) return emptyList()
        // A count, or the short prefix this codebase logs instead of a txid (`.take(12)`), is not a row.
        val shortened = Regex("""\.(size|count|isEmpty|isNotEmpty|length)\b|\.(take|takeLast)\(""")
        val out = mutableListOf<String>()
        collectLogStatements(content).forEach { stmt ->
            val bare = Regex("""\$(?!\{)([A-Za-z_]\w*)""").findAll(stmt).map { it.groupValues[1] }
            val braced = Regex("""\$\{([^{}]*)\}""").findAll(stmt).map { it.groupValues[1] }
                .filter { !shortened.containsMatchIn(it) }
                .mapNotNull { Regex("""^\s*([A-Za-z_]\w*)""").find(it)?.groupValues?.get(1) }
            if ((bare + braced).any { it in tainted }) out.add(stmt)
        }
        // `it` inside a block that iterates a tainted value: from the tainted name to the block's end.
        tainted.forEach { name ->
            Regex("""\b${Regex.escape(name)}\b[^\n{]*\.(forEach|onEach|map|mapNotNull|filter|forEachIndexed)\s*\{""")
                .findAll(content).forEach { m ->
                    var depth = 0; var i = m.range.last
                    while (i < content.length) {
                        when (content[i]) { '{' -> depth++; '}' -> depth-- }
                        if (depth == 0) break
                        i++
                    }
                    collectLogStatements(content.substring(m.range.last, minOf(i + 1, content.length)))
                        .filter { Regex("""\$\{?it\b""").containsMatchIn(it) }
                        .forEach { out.add(it) }
                }
        }
        return out
    }

    // Multi-line Log.x( ... ) calls are common in this codebase, so a line-based grep would miss
    // an argument on the next line. Collect each call from `Log.x(` to its balancing paren.
    private fun collectLogStatements(content: String): List<String> {
        val out = mutableListOf<String>()
        val start = Regex("""\bLog\.(?:[diwev]|wtf)\(""")
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
