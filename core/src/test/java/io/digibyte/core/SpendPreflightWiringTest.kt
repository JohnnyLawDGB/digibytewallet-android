package io.digibyte.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for one rule: in `core`, every bridge call that reads the wallet's
 * plain-coin set in order to build a spend sits behind the pre-spend pass.
 *
 * The order itself is behaviour and is tested as behaviour ([SpendPreflightOrderTest],
 * `AssetPreSpendPassTest`). This gate covers what those cannot reach: the production defaults go
 * to the native bridge, which has no library on the JVM, so no behaviour test can see which bridge
 * call a default makes, or that a new caller has appeared somewhere else.
 *
 * Three bridge calls read the plain-coin set for a spend: `createTransaction` (the DGB send),
 * `sendDigiDollar` (its network fee) and `getSpendableDigiByteUtxos` (the network fee of an asset
 * send). The recovery services build from outpoints of another seed that they list and classify
 * themselves, and a re-publish builds nothing; neither reads the set.
 */
class SpendPreflightWiringTest {

    private val coreMain = File("src/main/java/io/digibyte/core")

    /** Source with comments removed, so a mention in KDoc is never mistaken for a call. */
    private fun File.code(): String = readLines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private fun source(rel: String): String = File(coreMain, rel).let {
        assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
        it.code()
    }

    /** The text from [from] up to the next occurrence of [until]. */
    private fun String.section(from: String, until: String): String {
        val start = indexOf(from)
        assertTrue("cannot find `$from` — the gate is blind, not clean", start >= 0)
        val end = indexOf(until, start + from.length)
        assertTrue("cannot find `$until` after `$from` — the gate is blind, not clean", end > start)
        return substring(start, end)
    }

    private fun assertBefore(body: String, first: String, then: String) {
        val a = body.indexOf(first)
        val b = body.indexOf(then)
        assertTrue("cannot find `$first` — the pass is not run here", a >= 0)
        assertTrue("cannot find `$then` — the gate is blind, not clean", b >= 0)
        assertTrue("`$then` is reached before `$first`", a < b)
    }

    private val readsThePlainCoinSet = listOf(
        "NativeBridge.createTransaction(",
        "NativeBridge.sendDigiDollar(",
        "NativeBridge.getSpendableDigiByteUtxos(",
    )

    @Test fun `only the two guarded files read the plain-coin set for a spend`() {
        assertTrue("$coreMain is missing — the gate is blind, not clean", coreMain.isDirectory)
        val callers = coreMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                val code = f.code()
                readsThePlainCoinSet.filter { code.contains(it) }.map { "${f.name}: $it" }
            }
            .toSet()

        assertEquals(
            setOf(
                "TransactionBuilder.kt: NativeBridge.createTransaction(",
                "TransactionBuilder.kt: NativeBridge.sendDigiDollar(",
                "AssetManager.kt: NativeBridge.getSpendableDigiByteUtxos(",
            ),
            callers,
        )
    }

    @Test fun `the builder reaches the bridge only through its seam`() {
        val file = source("TransactionBuilder.kt")
        val builder = file.section("class TransactionBuilder(", "private fun estimateFee(")

        assertFalse("TransactionBuilder calls the bridge directly, past its seam", builder.contains("NativeBridge."))
        assertFalse("TransactionBuilder broadcasts directly, past its seam", builder.contains("Broadcaster."))
        assertTrue(
            "the builder's pass no longer defaults to the installed one",
            builder.contains("beforeSpend: suspend () -> Unit = { SpendPreflight.run() }"),
        )
        assertTrue(
            "the builder's native seam no longer defaults to the bridge",
            builder.contains("native: SpendNative = SpendNative.Bridge"),
        )
    }

    @Test fun `both sends run the pass before the first native build step`() {
        val file = source("TransactionBuilder.kt")
        assertBefore(
            file.section("suspend fun sendTransaction(", "suspend fun sendDigiDollar("),
            "SpendPreflight.completed(beforeSpend)", "native.createTransaction(",
        )
        assertBefore(
            file.section("suspend fun sendDigiDollar(", "private fun estimateFee("),
            "SpendPreflight.completed(beforeSpend)", "native.sendDigiDollar(",
        )
    }

    @Test fun `the asset send runs the pass before it reads a coin`() {
        val send = source("asset/AssetManager.kt").section("suspend fun sendAsset(", "suspend fun pruneRemovedNativeAssetRows(")
        assertBefore(send, "SpendPreflight.completed(", "NativeBridge.getSpendableDigiByteUtxos(")
        assertBefore(send, "SpendPreflight.completed(", "utxoDao.getAssetUtxosByIdNow(")
        assertBefore(send, "SpendPreflight.completed(", "NativeBridge.buildAndSignAssetTransferTx(")
    }

    /** The pass reads every transaction the first time it runs, and a caller may be on the main
     *  thread (the asset send is), so it moves itself off the caller's thread. */
    @Test fun `the pass leaves the caller's thread`() {
        val manager = source("asset/AssetManager.kt")
        assertTrue(
            "holdAssetOutputsBeforeSpend no longer moves to the IO dispatcher",
            manager.contains("suspend fun holdAssetOutputsBeforeSpend(): Int = withContext(Dispatchers.IO) {"),
        )
    }

    /** The pass every spend runs first is the asset layer's: one place in `core` installs it, and
     *  nothing outside `core` can. */
    @Test fun `only the asset layer installs a pass`() {
        assertTrue("$coreMain is missing — the gate is blind, not clean", coreMain.isDirectory)
        val install = Regex("""SpendPreflight\s*\.\s*install\b""")
        val installers = coreMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { install.containsMatchIn(it.code()) }
            .map { it.name }
            .toSet()

        assertEquals(setOf("AssetManager.kt"), installers)
        assertTrue(
            "SpendPreflight.install is callable from outside core",
            source("TransactionBuilder.kt").contains("internal fun install(pass: suspend () -> Unit)"),
        )
    }

    @Test fun `the asset layer installs its own pass`() {
        val manager = source("asset/AssetManager.kt")
        assertTrue(
            "AssetManager no longer installs the pre-spend pass when it is constructed",
            manager.contains("SpendPreflight.install { holdAssetOutputsBeforeSpend() }"),
        )
    }
}
