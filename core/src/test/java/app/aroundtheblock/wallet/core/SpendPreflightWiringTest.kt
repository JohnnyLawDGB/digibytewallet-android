package app.aroundtheblock.wallet.core

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
 * Four bridge calls read the plain-coin set for a spend: `createTransaction` (the DGB send's build at
 * review), `signPreparedTransaction` (its signature after the prompt, which checks the prepared coins
 * against the set), `sendDigiDollar` (its network fee) and `getSpendableDigiByteUtxos` (the network
 * fee of an asset send). The recovery services build from outpoints of another seed that they list
 * and classify themselves, and a re-publish builds nothing; neither reads the set.
 */
class SpendPreflightWiringTest {

    private val coreMain = File("src/main/java/app/aroundtheblock/wallet/core")

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
        "NativeBridge.signPreparedTransaction(",
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
                "TransactionBuilder.kt: NativeBridge.signPreparedTransaction(",
                "TransactionBuilder.kt: NativeBridge.sendDigiDollar(",
                "AssetManager.kt: NativeBridge.getSpendableDigiByteUtxos(",
            ),
            callers,
        )
    }

    @Test fun `the builder reaches the bridge only through its seam`() {
        val file = source("TransactionBuilder.kt")
        val builder = file.section("class TransactionBuilder(", "private companion object")

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
        // The DGB send's review: the pass, then the one helper that builds.
        assertBefore(
            file.section("suspend fun prepareTransaction(", "suspend fun sendPrepared("),
            "SpendPreflight.completed(beforeSpend)", "buildAndQuote(",
        )
        val helper = file.section("private fun buildAndQuote(", "private fun belowDustLine(")
        assertTrue("the review's build is not in buildAndQuote", helper.contains("native.createTransaction("))
        assertEquals("the DGB build is made somewhere other than buildAndQuote", 1, Regex(Regex.escape("native.createTransaction(")).findAll(file).count())
        assertEquals(
            "buildAndQuote is called from somewhere other than review",
            Regex(Regex.escape("buildAndQuote(")).findAll(file.section("suspend fun prepareTransaction(", "suspend fun sendPrepared(")).count(),
            Regex(Regex.escape("buildAndQuote(")).findAll(file).count() - 1,   // less its declaration
        )
        // The DGB send after the prompt: the pass again, then the checked signer.
        assertBefore(
            file.section("suspend fun sendPrepared(", "suspend fun sendDigiDollar("),
            "SpendPreflight.completed(beforeSpend)", "native.signPrepared(",
        )
        assertBefore(
            file.section("suspend fun sendDigiDollar(", "suspend fun awaitDelivery("),
            "SpendPreflight.completed(beforeSpend)", "native.sendDigiDollar(",
        )
    }

    @Test fun `the asset send runs the pass before it reads a coin`() {
        // The send plans through planAssetTransfer (which the confirmation's fee preview shares,
        // B231) and signs what it returns, so the pass is pinned there, ahead of every coin read.
        val mgr = source("asset/AssetManager.kt")
        val plan = mgr.section("suspend fun planAssetTransfer(", "suspend fun previewAssetTransferFee(")
        assertBefore(plan, "SpendPreflight.completed(", "NativeBridge.getSpendableDigiByteUtxos(")
        assertBefore(plan, "SpendPreflight.completed(", "utxoDao.getAssetUtxosByIdNow(")
        val send = mgr.section("suspend fun sendAsset(", "suspend fun pruneRemovedNativeAssetRows(")
        assertBefore(send, "planAssetTransfer(", "NativeBridge.buildAndSignAssetTransferTx(")
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
