package app.aroundtheblock.wallet.core.asset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ASSET-SIGN-GATE wiring: the DigiAsset sign path re-checks natively, before and after signing, that it
 * pays only the approved outputs and fee. asset_sign_gate_kat tests the native check; what it cannot see
 * is what Kotlin hands it. The fee native bounds the plan by must be the fee the confirmation showed
 * (maxFeeSats), not the plan's own fee (which would check the plan against itself), the recipient's
 * amount must be the DigiAsset marker, and no caller may send without an approved fee.
 */
class AssetSignGateApprovalWiringTest {

    private val repo = File("..").canonicalFile
    private val manager = File(repo, "core/src/main/java/app/aroundtheblock/wallet/core/asset/AssetManager.kt")
    private val bridge = File(repo, "core/src/main/java/app/aroundtheblock/wallet/core/bridge/NativeBridge.kt")

    /** Kotlin source with comments removed, so a mention in KDoc is never mistaken for code. */
    private fun kotlin(file: File): String {
        assertTrue("${file.path} is missing — this gate is watching a file that moved", file.exists())
        return file.readLines()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
    }

    private fun String.section(from: String, until: String): String {
        val start = indexOf(from)
        assertTrue("cannot find `$from` — the gate is blind, not clean", start >= 0)
        val end = indexOf(until, start + from.length)
        assertTrue("cannot find `$until` after `$from` — the gate is blind, not clean", end > start)
        return substring(start, end)
    }

    private fun String.flat() = replace(Regex("""\s+"""), " ")

    @Test fun `native is handed the approved fee and the marker amount, not only the plan's fee`() {
        val call = kotlin(manager).section("NativeBridge.buildAndSignAssetTransferTx(", "?: return").flat()
        assertTrue("the approved fee (maxFeeSats) is not the native ceiling", call.contains("approvedMaxFeeSats = maxFeeSats"))
        assertTrue("the plan's fee is not handed as the plan's fee", call.contains("planFeeSats = plan.paidFeeSats"))
        assertTrue("the recipient's amount is not the DigiAsset marker", Regex("""approvedRecipientSats = [\w.]*DA_MARKER_SATS""").containsMatchIn(call))
        assertFalse("the plan's own fee is handed as the approved fee", call.contains("approvedMaxFeeSats = plan.paidFeeSats"))
    }

    @Test fun `a DigiAsset send cannot be made without an approved fee`() {
        val sig = kotlin(manager).section("suspend fun sendAsset(", "): TxResult").flat()
        assertTrue("sendAsset has no maxFeeSats parameter", sig.contains("maxFeeSats: Long"))
        assertFalse("maxFeeSats has a default, so a caller can send with no approved fee", Regex("""maxFeeSats: Long\s*=""").containsMatchIn(sig))
    }

    @Test fun `the bridge declares all four approval values`() {
        val decl = kotlin(bridge).section("external fun buildAndSignAssetTransferTx(", "): String?").flat()
        for (p in listOf("approvedToAddress: String", "approvedRecipientSats: Long", "planFeeSats: Long", "approvedMaxFeeSats: Long")) {
            assertTrue("buildAndSignAssetTransferTx does not take $p", decl.contains(p))
        }
    }
}
