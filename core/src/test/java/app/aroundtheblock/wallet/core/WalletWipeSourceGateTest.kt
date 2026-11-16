package app.aroundtheblock.wallet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate for the persistent side of a wallet wipe: a wipe may only report what it
 * checked, so every write result in [WalletDataEraser] has to reach the caller.
 *
 * A source scan rather than a behaviour test because the way this regresses is a new store
 * added with a bare `edit().clear().commit()` line — it compiles, it clears, and its result
 * goes nowhere. The behaviour is covered by [WalletWipeTest].
 */
class WalletWipeSourceGateTest {

    private val eraser = File("src/main/java/app/aroundtheblock/wallet/core/WalletDataEraser.kt")

    /** Every file whose writes an erase reports: the full wipe's and the one-wallet removal's. */
    private val scanned = listOf(
        eraser,
        File("src/main/java/app/aroundtheblock/wallet/core/wallets/WalletStoreEraser.kt"),
    )

    /**
     * Code only, one logical statement per entry: comments are dropped, and a line that
     * continues the previous one (leading `.`/`&&`/`||`/`?:`, or a previous line left open by
     * `=`, `(`, `,`, `&&`, `||` or `return`) is joined to it.
     */
    private fun statements(file: File): List<String> {
        val code = file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
        val joined = mutableListOf<String>()
        for (line in code) {
            val prev = joined.lastOrNull()
            val continuesPrev = line.startsWith(".") || line.startsWith("&&") ||
                line.startsWith("||") || line.startsWith("?:")
            val prevIsOpen = prev != null && Regex("(=|\\(|,|&&|\\|\\||\\breturn)$").containsMatchIn(prev)
            if (prev != null && (continuesPrev || prevIsOpen)) {
                joined[joined.lastIndex] = "$prev $line"
            } else {
                joined += line
            }
        }
        return joined
    }

    /** True when the statement's last call is `commit()` and nothing receives its value. */
    private fun discardsCommit(statement: String): Boolean {
        if (!statement.endsWith(".commit()")) return false
        val receivesValue = Regex("^(return|val|var)\\b").containsMatchIn(statement) ||
            statement.contains(" = ") || statement.contains("&&") || statement.contains("||")
        return !receivesValue
    }

    @Test fun `the scan sees the eraser's writes at all`() {
        for (file in scanned) {
            assertTrue("cannot find ${file.path}", file.isFile)
            val commits = statements(file).count { it.contains(".commit()") }
            assertTrue("scanner found no commit() in ${file.name} — it is blind, not clean", commits >= 1)
        }
    }

    @Test fun `the eraser never discards a commit result`() {
        for (file in scanned) {
            val discarded = statements(file).filter(::discardsCommit)
            assertEquals(
                "write results that reach nobody in ${file.name}:\n" + discarded.joinToString("\n"),
                0, discarded.size,
            )
        }
    }

    @Test fun `the eraser never uses an unchecked asynchronous write`() {
        for (file in scanned) {
            val async = statements(file).filter { it.contains(".apply()") }
            assertTrue("apply() has no result to check in ${file.name}:\n" + async.joinToString("\n"), async.isEmpty())
        }
    }

    /**
     * GUARD (nothing to go red on before the functions existed). The all-networks deletes are for
     * a full wallet reset only: routine recovery keeps the scan ledger, and a recovery path that
     * reached for the wider delete would drop it on both networks. Only the eraser may call them.
     */
    @Test fun `only the full reset uses the all-networks deletes`() {
        val roots = listOf(File("src/main/java"), File("../app/src/main/java")).filter { it.isDirectory }
        assertTrue("cannot see the core sources", roots.any { File(it, "app/aroundtheblock/wallet/core/WalletDataEraser.kt").isFile })
        val call = Regex("""\b(CfScanLedgerStore|FilterHeaderStore|SavedBlockStore)\s*\.\s*deleteAllNetworks\s*\(|\bCfAbandonmentStore\s*\.\s*clearAllNetworks\s*\(""")
        val callers = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file -> statements(file).any { call.containsMatchIn(it) } }
            .map { it.name }
        assertEquals("the all-networks deletes must be reachable from the full reset only", listOf("WalletDataEraser.kt"), callers)
    }

    @Test fun `every erase step of the interface reports a result`() {
        val steps = statements(eraser).filter { Regex("^fun erase\\w+\\(\\)").containsMatchIn(it) }
        assertTrue("scanner found no erase steps — it is blind, not clean", steps.size >= 7)
        val silent = steps.filterNot { it.endsWith(": Boolean") }
        assertTrue("erase steps that report nothing: $silent", silent.isEmpty())
    }
}
