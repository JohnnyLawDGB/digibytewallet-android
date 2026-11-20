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
        // The reserved names too: no build opens one yet, and the first that does must go through
        // WalletStores, or every wallet would share legacy's.
        val bases = WalletStores.WALLET_AND_NETWORK_PREFS + WalletStores.WALLET_PREFS + WalletStores.RESERVED_WALLET_PREFS
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

    /**
     * The band spelling ("_legacy" for the real legacy wallet) is asked for only through
     * [WalletStores.bandFileName] and [WalletStores.bandPrefsName], which refuse every base but the
     * reserved four. [StoreScope.BAND] is public, so a direct `storageSuffix(scope, StoreScope.BAND)`
     * would spell a "_legacy" name for any store, past that refusal.
     */
    @Test fun `the band suffix is asked for only by WalletStores`() {
        val band = Regex("""\bBAND\b(?!_)""")
        val mayName = listOf("wallets/WalletStores.kt", "wallets/WalletScope.kt")
        fun naming(f: File) = statements(f).filter { band.containsMatchIn(it) }
        val offenders = sources()
            .filter { f -> mayName.none { f.invariantSeparatorsPath.endsWith(it) } }
            .flatMap { f -> naming(f).map { "${f.name}: $it" } }
        assertTrue("StoreScope.BAND named outside WalletStores:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        // Not blind: the two files that may name it do, and the scan sees them.
        val seen = sources().filter { f -> mayName.any { f.invariantSeparatorsPath.endsWith(it) } }.associate { it.name to naming(it).size }
        assertTrue("the scan does not see BAND where it is defined and used: $seen", seen.size == 2 && seen.values.all { it >= 1 })
    }

    /** The seed record's network field: one key and two values, defined once, in [WalletStores]. */
    @Test fun `the seed record's network key is spelled only in WalletStores`() {
        val spelled = sources()
            .filter { f -> !f.invariantSeparatorsPath.endsWith("wallets/WalletStores.kt") }
            .filter { f -> f.readText().contains("\"wallet_network\"") }
            .map { it.path }
        assertTrue("\"wallet_network\" spelled outside WalletStores:\n" + spelled.joinToString("\n"), spelled.isEmpty())
        assertTrue(WalletStores.NETWORK_KEY == "wallet_network")
        assertTrue(WalletStores.NETWORK_MAINNET == "mainnet" && WalletStores.NETWORK_TESTNET == "testnet")
    }

    @Test fun `the scan sees store opens at all`() {
        val opens = sources().sumOf { f -> statements(f).count { it.contains("getSharedPreferences(") } }
        assertTrue("scanner found only $opens getSharedPreferences( statements — it is blind, not clean", opens >= 30)
    }
}
