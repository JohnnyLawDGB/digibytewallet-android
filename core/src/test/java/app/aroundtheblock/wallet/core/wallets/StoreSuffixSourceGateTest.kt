package app.aroundtheblock.wallet.core.wallets

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate for "one copy of each thing": [storageSuffix] is the only place a store suffix is
 * built. A per-wallet store opened by a bare literal (or a constant plus a hand-made suffix)
 * would keep resolving to the legacy name for every wallet, and nothing at runtime would say so.
 */
class StoreSuffixSourceGateTest {

    private val roots = listOf(File("src/main/java"), File("../app/src/main/java")).filter { it.isDirectory }

    /** The only files that may name a per-wallet store base directly. */
    private val exempt = listOf("wallets/WalletStores.kt", "/WalletDataEraser.kt", "/HubTokenStore.kt")

    private fun sources(): List<File> =
        roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** Same shape as WalletWipeSourceGateTest: code only, one logical statement per entry. */
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
                line.startsWith("||") || line.startsWith("?:") || line.startsWith("+")
            val prevIsOpen = prev != null && Regex("(=|\\(|,|&&|\\|\\||\\+|\\breturn)$").containsMatchIn(prev)
            if (prev != null && (continuesPrev || prevIsOpen)) {
                joined[joined.lastIndex] = "$prev $line"
            } else {
                joined += line
            }
        }
        return joined
    }

    @Test fun `networkSuffix is gone`() {
        assertTrue("cannot see the core sources", roots.isNotEmpty())
        val offenders = sources().filter { it.readText().contains("networkSuffix(") }.map { it.path }.sorted()
        assertTrue(
            "${offenders.size} files still call networkSuffix(…):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test fun `no per-wallet store is named by a bare literal`() {
        val bases = WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS
        val literal = bases.flatMap { base ->
            val b = Regex.escape(base)
            listOf(
                Regex("""getSharedPreferences\(\s*"$b""""),
                Regex(""""$b"\s*\+"""),
                Regex(""""$b\$"""),
            )
        }
        val constantPlus = Regex("""getSharedPreferences\(\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Z][A-Z0-9_]*\s*\+""")
        val offenders = sources()
            .filter { f -> exempt.none { f.invariantSeparatorsPath.endsWith(it) } }
            .flatMap { f ->
                statements(f)
                    .filter { s -> literal.any { it.containsMatchIn(s) } || constantPlus.containsMatchIn(s) }
                    .map { "${f.name}: $it" }
            }
        assertTrue(
            "per-wallet stores named outside WalletStores.prefsName:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test fun `the scan sees store opens at all`() {
        val opens = sources().sumOf { f -> statements(f).count { it.contains("getSharedPreferences(") } }
        assertTrue("scanner found only $opens getSharedPreferences( statements — it is blind, not clean", opens >= 30)
    }
}
