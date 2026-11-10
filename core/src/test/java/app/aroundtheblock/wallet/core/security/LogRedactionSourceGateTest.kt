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
        // A keyed log tag (LogTag.tx / LogTag.outpoint, 4.1.3 review R7) names no transaction on chain.
        val shortened = Regex("""\.(take|takeLast)\(|\.size\b|\.count\b|\.isEmpty|\.isNotEmpty|LogTag\.(tx|outpoint)\(""")
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

    /** K1-RECOVERY: the collector sees a call through a sink the file injects, in both shapes the
     *  recovery services use, and does not take an unrelated `log(` for one. */
    @Test
    fun `the log collector sees calls through an injected logger`() {
        val twoArg = "class S(private val log: (level: Char, message: String) -> Unit) {\n" +
            "  fun f() { log('w',\n    \"x ${'$'}destAddress\") }\n}"
        val stmts = collectLogStatements(twoArg)
        assertTrue(stmts.toString(), stmts.size == 1 && stmts[0].contains("destAddress"))
        val oneArg = "class S(private val log: (String) -> Unit) { fun f() { log(\"y ${'$'}{inputs.totalIn}\") } }"
        assertTrue(collectLogStatements(oneArg).toString(), collectLogStatements(oneArg).single().contains("totalIn"))
        val named = "class S(private val progressLogger: (String) -> Unit) { fun f() { progressLogger(\"z\") } }"
        assertTrue(collectLogStatements(named).toString(), collectLogStatements(named).size == 1)
        // No sink declared, or a method that merely shares the name: not a log statement.
        assertTrue(collectLogStatements("val y = Math.log(2.0) + log(3.0)").isEmpty())
        assertTrue(collectLogStatements("class S(private val log: (String) -> Unit) { val y = Math.log(2.0) }").isEmpty())
        assertTrue(collectLogStatements("class S(val onDialog: (String) -> Unit) { fun f() { onDialog(\"a\") } }").isEmpty())
    }

    /**
     * K1-RECOVERY: no log line from a recovery, sweep or foreign-transfer path -- direct `Log.x(` or an
     * injected sink, both ship in release -- carries an address, an exact amount (sats, a fee, change,
     * cents, asset units), an outpoint, a txid or a script. The free-text `detail` of a refusal is
     * barred too: it is written for the user's screen and carries amounts and outpoints. Allowed: a
     * count (`.size`), the keyed tag (`LogTag.tx` / `LogTag.outpoint`), a reason enum, a profile label.
     */
    @Test
    fun `K1-RECOVERY no recovery or sweep log line carries an address, amount, outpoint, txid or script`() {
        val violations = mutableListOf<String>()
        recoverySources().forEach { file ->
            collectLogStatements(file.readText()).forEach { stmt ->
                val bad = recoveryLeaks(stmt)
                if (bad.isNotEmpty()) violations.add("${file.name}: $bad in $stmt")
            }
        }
        assertTrue("K1-RECOVERY log statements carrying chain or amount data:\n" + violations.joinToString("\n"),
            violations.isEmpty())
    }

    /** The recovery rule must flag each class it names and pass the shapes it allows. */
    @Test
    fun `K1-RECOVERY rule flags each leak class and passes counts and tags`() {
        val d = '$'
        listOf(
            "log('i', \"-> ${d}destAddress\")",
            "log(\"${d}{inputs.totalIn} sat in\")",
            "log(\"fee ${d}feeSat sat\")",
            "log('i', \"change ${d}{r.plan.outputs.first().amountSat} sats\")",
            "log('w', \"${d}{utxo.txid}:${d}{utxo.vout}: not moved\")",
            "log('w', \"${d}op: not moved\")",
            "log('w', \"${d}{v.outpoint}: ${d}{v.detail}\")",
            "log(\"txid=${d}{txid ?: \"not relayed\"}\")",
            "log('i', \"pool ${d}{feePool.sumOf { it.amountSat }} sats\")",
            "log('i', \"script ${d}{out.scriptPubKeyHex}\")",
            "log('i', \"planned ${d}{r.plan.assetUnits} unit(s)\")",
        ).forEach { assertTrue("not flagged: $it", recoveryLeaks(it).isNotEmpty()) }
        listOf(
            "log('i', \"${d}{LogTag.outpoint(item.outpoint)}: MOVED in ${d}{LogTag.tx(txid)}\")",
            "log('i', \"pool ${d}{feePool.size} outpoint(s)\")",
            "log('i', \"${d}{fan.plan.outputs.count { it.isFeeOutput }} fee output(s)\")",
            "log('w', \"profile=${d}{result.profile.label}: refused — ${d}{r.reason}\")",
            "log('w', \"not moved — transfer rules ${d}ruleState\")",
            "log('w', \"${d}{LogTag.outpoint(RawTxBinding.outpoint(utxo))}: not moved\")",
        ).forEach { assertTrue("wrongly flagged: $it -> ${recoveryLeaks(it)}", recoveryLeaks(it).isEmpty()) }
    }

    /** Every Kotlin file on a recovery, sweep or foreign-transfer path: the core recovery package, the
     *  app's recovery UI, and any other file whose name says recover or sweep. */
    private fun recoverySources(): List<File> {
        val files = listOf("core/src/main/java", "app/src/main/java").flatMap { root ->
            findSourceRoot(root).walkTopDown().filter { it.extension == "kt" }.filter { f ->
                val path = f.invariantSeparatorsPath
                path.contains("/core/recovery/") || path.contains("/ui/recovery/") ||
                    Regex("""(?i)(recover|sweep)""").containsMatchIn(f.name)
            }.toList()
        }
        // The gate must be looking at the services it was written for.
        listOf("ForeignAssetTransferService.kt", "LegacySweepService.kt", "DigiDollarTransferService.kt",
            "RecoverFundsViewModel.kt").forEach { n ->
            assertTrue("$n is not in the K1-RECOVERY scope", files.any { it.name == n })
        }
        return files
    }

    /** The interpolated expressions of [stmt] that name chain or amount data. */
    private fun recoveryLeaks(stmt: String): List<String> {
        val leak = Regex(
            """(?i)(address|addr\b|txid|txhash|outpoint|^op$|vout|script|sat|amount|fee|change|totalIn|cents|units|balance|detail|hex)"""
        )
        val allowed = Regex("""\.(size|count)\b|\.isEmpty|\.isNotEmpty|^\s*LogTag\.(tx|outpoint)\(.*\)\s*$""")
        val exprs = mutableListOf<String>()
        Regex("""\$(?!\{)([A-Za-z_]\w*)""").findAll(stmt).forEach { exprs.add(it.groupValues[1]) }
        // `${...}` with nested braces (`${xs.sumOf { it.amountSat }}`) read to the balancing brace.
        var i = stmt.indexOf("\${")
        while (i >= 0) {
            var depth = 0; var j = i + 1
            while (j < stmt.length) {
                when (stmt[j]) { '{' -> depth++; '}' -> depth-- }
                if (depth == 0) break
                j++
            }
            exprs.add(stmt.substring(i + 2, minOf(j, stmt.length)))
            i = stmt.indexOf("\${", j)
        }
        return exprs.filter { leak.containsMatchIn(it) && !allowed.containsMatchIn(it) }
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
    // The file's injected sinks are declared once (a constructor parameter), so they are read from the
    // whole file and handed to each function's search.
    private fun txRowLogs(content: String): List<String> {
        val sinks = injectedLoggerNames(content)
        return Regex("""(?=\n[ \t]*(?:(?:private|internal|public|override|suspend|inline|protected)\s+)*fun\s)""")
            .split(content).flatMap { txRowLogsIn(it, sinks) }
    }

    private fun txRowLogsIn(content: String, sinks: Set<String>): List<String> {
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
        collectLogStatements(content, sinks).forEach { stmt ->
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
                    collectLogStatements(content.substring(m.range.last, minOf(i + 1, content.length)), sinks)
                        .filter { Regex("""\$\{?it\b""").containsMatchIn(it) }
                        .forEach { out.add(it) }
                }
        }
        return out
    }

    // Multi-line Log.x( ... ) calls are common in this codebase, so a line-based grep would miss
    // an argument on the next line. Collect each call from `Log.x(` to its balancing paren.
    //
    // K1-RECOVERY: a service that logs through an INJECTED sink (`private val log: (Char, String) -> Unit`,
    // `log: (String) -> Unit`) never writes `Log.x(` at its call sites, so a gate that matched only that
    // text saw none of its lines. Every file is therefore also searched for calls to any logger it
    // declares itself (see [injectedLoggerNames]); a call to one is a log statement like any other.
    private fun collectLogStatements(content: String, names: Set<String> = injectedLoggerNames(content)): List<String> {
        // `(?<![.\w])`: the injected `log(`, not `Math.log(` or `catalog(`.
        val sinks = names.joinToString("") { "|(?<![.\\w])" + Regex.escape(it) }
        val start = Regex("""(?:\bLog\.(?:[diwev]|wtf)$sinks)\(""")
        val out = mutableListOf<String>()
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

    /**
     * K1-RECOVERY: the loggers a file declares for itself -- a property or parameter of function type
     * that takes a String and returns Unit, named like a log sink (`log`, `logger`, `debugLog`,
     * `logSink`, ...) -- plus [KNOWN_LOGGERS], the names this codebase is known to inject. Found from
     * the declaration, so a new service with its own sink is seen without editing this test.
     */
    private fun injectedLoggerNames(content: String): Set<String> {
        val decl = Regex("""\b([A-Za-z_]\w*)\s*:\s*\(([^()]*)\)\s*->\s*Unit\b""")
        // `log`, `logger`, `sink`, or camelCase ending in one (`debugLog`, `progressLogger`, `logSink`);
        // case-sensitive so `onDialog` / `catalog` are not taken for sinks.
        val loggerName = Regex("""^(log|logger|sink|[a-z]\w*(Log|Logger|Sink))$""")
        return decl.findAll(content)
            .filter { Regex("""\bString\b""").containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] }
            .filter { loggerName.matches(it) || it in KNOWN_LOGGERS }
            .toSet()
    }

    private companion object {
        /** Sink names this codebase injects today: `log` (ForeignAssetTransferService,
         *  DigiDollarTransferService, LegacySweepService). A name here is still only a sink in a file
         *  that declares it with a String-taking function type; add one if a sink is ever named
         *  outside the pattern above. */
        val KNOWN_LOGGERS = setOf("log", "logger")
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
