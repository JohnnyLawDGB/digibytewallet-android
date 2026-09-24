package io.digibyte.core.asset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level WIRING gate for two rules whose behaviour is tested elsewhere
 * (AssetProvenanceTxidBindingTest, AssetCoinSelectorSecurityTest, parent_txid_binding_kat):
 *
 *  - a parent transaction is accepted only when its bytes hash to the requested id, and in
 *    production that id comes from the native parser;
 *  - the asset send checks that its inputs name distinct outpoints before it signs, and the
 *    bridge checks it again before it signs.
 *
 * What no behaviour test can see: which function a production default calls (the native bridge
 * has no library on the JVM), that nothing overrides it at the one place the client is built,
 * and the order of calls inside a JNI file that cannot be compiled on the host.
 */
class ParentIdAndDistinctInputsSourceGateTest {

    private val repo = File("..").canonicalFile
    private val coreMain = File(repo, "core/src/main/java/io/digibyte/core")

    /** Kotlin source with comments removed, so a mention in KDoc is never mistaken for a call. */
    private fun kotlin(file: File): String {
        assertTrue("${file.path} is missing — this gate is watching a file that moved", file.exists())
        return file.readLines()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
    }

    /** C source with block and line comments removed. */
    private fun c(file: File): String {
        assertTrue("${file.path} is missing — this gate is watching a file that moved", file.exists())
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
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
        assertTrue("cannot find `$first`", a >= 0)
        assertTrue("cannot find `$then` — the gate is blind, not clean", b >= 0)
        assertTrue("`$then` is reached before `$first`", a < b)
    }

    @Test fun `the client's default id is the native parser's and every raw answer is compared`() {
        val client = kotlin(File(coreMain, "asset/network/MultiEndpointAssetClient.kt"))
        assertTrue(
            "the default transactionIdOf is not NativeBridge.rawTransactionId",
            Regex("""transactionIdOf:\s*\(ByteArray\)\s*->\s*String\?\s*=\s*\{\s*NativeBridge\.rawTransactionId\(it\)\s*\}""")
                .containsMatchIn(client),
        )
        val fetch = client.section("override suspend fun getRawTransaction(", "private suspend fun <T> tryEach(")
        assertTrue("getRawTransaction no longer compares the answer's id", fetch.contains("transactionIdOf(raw)"))
        assertTrue(
            "the comparison is not inside the per-endpoint block",
            fetch.section("tryEach(\"getRawTransaction\")", "\n    }").contains("transactionIdOf(raw)"),
        )
    }

    @Test fun `the one place the client is built does not replace the id`() {
        val module = kotlin(File(repo, "app/src/main/java/io/digibyte/di/AppModule.kt"))
        val provider = module.section("fun provideAssetNetworkClient(", "@Provides")
        assertTrue(provider.contains("MultiEndpointAssetClient("))
        assertFalse("AppModule overrides the parent id check", provider.contains("transactionIdOf"))
    }

    @Test fun `the bridge declares the accessor and implements it with the checked header`() {
        val bridge = kotlin(File(coreMain, "bridge/NativeBridge.kt"))
        assertTrue(bridge.contains("external fun rawTransactionId(rawTx: ByteArray): String?"))

        val jni = c(File(repo, "native/src/main/jni/bridge/jni_asset_send.c"))
        assertTrue(jni.contains("#include \"asset_tx_checks.h\""))
        val accessor = jni.section("Java_io_digibyte_core_bridge_NativeBridge_rawTransactionId(", "\n}\n")
        assertTrue("rawTransactionId does not use raw_tx_id", accessor.contains("raw_tx_id("))
    }

    @Test fun `the asset send checks distinct inputs before it signs`() {
        val manager = kotlin(File(coreMain, "asset/AssetManager.kt"))
        val send = manager.section("suspend fun sendAsset(", "private fun ByteArray.toHex()")
        assertTrue("the signed inputs are not AssetTransferPlan.inputs", send.contains("val allInputs = plan.inputs"))
        assertBefore(send, "AssetCoinSelector.outpointsDistinct(allInputs)", "NativeBridge.buildAndSignAssetTransferTx(")
        for (arg in listOf("inputTxidsHex", "inputVouts", "inputAmounts", "inputScriptPubKeysHex")) {
            assertTrue("$arg is not built from allInputs", Regex("""$arg\s*=\s*allInputs\.""").containsMatchIn(send))
        }

        val jni = c(File(repo, "native/src/main/jni/bridge/jni_asset_send.c"))
        val build = jni.section("Java_io_digibyte_core_bridge_NativeBridge_buildAndSignAssetTransferTx(", "\n}\n")
        assertBefore(build, "tx_inputs_distinct(tx)", "seed_sign_transaction(")
    }
}
