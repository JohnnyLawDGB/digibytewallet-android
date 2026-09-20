package io.digibyte.ui.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for one rule: nothing in `app` builds a spend from the wallet's
 * plain-coin set except through `core`, where asset detection runs to completion first.
 *
 * The order is behaviour and is tested as behaviour in `core` (`SpendPreflightOrderTest`,
 * `AssetPreSpendPassTest`), and `core` has its own gate over its callers
 * (`SpendPreflightWiringTest`). This gate covers the part neither can reach — no `app` unit test
 * builds a ViewModel — and pins that the screens go through those entry points.
 */
class SpendPreflightAppWiringTest {

    private val appMain = File("src/main/java/io/digibyte")

    /** Source with comments removed, so a mention in KDoc is never mistaken for a call. */
    private fun File.code(): String = readLines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private fun source(rel: String): String = File(appMain, rel).let {
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

    /** Every `app` source file that contains one of [needles], as `"<path>: <needle>"`. */
    private fun filesContaining(needles: List<String>): List<String> {
        assertTrue("$appMain is missing — the gate is blind, not clean", appMain.isDirectory)
        return appMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                val code = f.code()
                needles.filter { code.contains(it) }.map { "${f.relativeTo(appMain)}: $it" }
            }
            .toList()
    }

    /** The bridge calls that select coins from the plain-coin set, or sign what was selected.
     *  `publishTransaction` is not among them: re-publishing a signed transaction builds nothing. */
    private val buildsASpend = listOf(
        "NativeBridge.createTransaction(",
        "NativeBridge.signTransaction(",
        "NativeBridge.sendDigiDollar(",
        "NativeBridge.getSpendableDigiByteUtxos(",
        "NativeBridge.buildAndSignAssetTransferTx(",
    )

    @Test fun `no screen or service builds a spend through the bridge directly`() {
        val offenders = filesContaining(buildsASpend)
        assertTrue(
            "a spend is built past core's entry points:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test fun `the DigiDollar send goes through the builder`() {
        val send = source("ui/wallet/SendViewModel.kt").section("fun sendDigiDollar(", "companion object")
        assertTrue(
            "sendDigiDollar() does not hand the approved send to the builder",
            send.contains("transactionBuilder.sendDigiDollar(approved.address, approved.cents)"),
        )
        assertFalse("sendDigiDollar() swallows a cancellation", send.contains("runCatching"))
    }

    @Test fun `the DGB send goes through the builder`() {
        val send = source("ui/wallet/SendViewModel.kt").section("fun send(", "fun resetState(")
        assertTrue("send() does not go through the builder", send.contains("transactionBuilder.sendTransaction("))
    }

    @Test fun `the asset send goes through the asset layer`() {
        assertTrue(source("ui/asset/AssetViewModel.kt").contains("assetManager.sendAsset("))
    }

    /** The pass every spend runs first is the asset layer's, installed in `core`. Nothing in `app`
     *  installs another in its place. */
    @Test fun `nothing in app installs a pass of its own`() {
        val installers = filesContaining(listOf("SpendPreflight.install(", "SpendPreflight.install {"))
        assertTrue(
            "the pre-spend pass is replaced from app:\n" + installers.joinToString("\n"),
            installers.isEmpty(),
        )
    }

    /** The builder and the asset layer are constructed with their production defaults: the
     *  installed pass, the real bridge. A seam argument here would replace one of them. */
    @Test fun `the graph constructs both with their production defaults`() {
        val module = source("di/AppModule.kt")
        val builder = module.section("fun provideTransactionBuilder(", "@Provides")
        assertTrue("the gate is blind, not clean", builder.contains("TransactionBuilder("))
        for (seam in listOf("beforeSpend", "native =", "SpendNative")) {
            assertFalse("provideTransactionBuilder overrides `$seam`", builder.contains(seam))
        }
        val assets = module.section("fun provideAssetManager(", "@Provides")
        assertTrue("the gate is blind, not clean", assets.contains("AssetManager("))
        assertFalse("provideAssetManager overrides the pass", assets.contains("beforeSpend"))
        assertFalse("provideAssetManager overrides the registration", assets.contains("registerAssetOutpoint"))
    }
}
