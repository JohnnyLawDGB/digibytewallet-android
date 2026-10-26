package app.aroundtheblock.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * No send-path log line carries an amount or a fee (4.1.2 row 30, review R7).
 *
 * Row 30 took the transaction row out of the "send attempt" line, but the review line logged seconds
 * earlier still carried the exact amount, fee and size, and the DigiDollar move and the native signer
 * logged their amounts and fees too: with a timestamp, that picks the transaction out on chain just as
 * well. LogRedactionSourceGateTest follows txid values only, so it could not see these.
 */
class SendLogAmountGateTest {

    private val root = File("..").canonicalFile
    private val kotlinSites = listOf(
        "app/src/main/java/app/aroundtheblock/wallet/ui/wallet/SendViewModel.kt",
        "core/src/main/java/app/aroundtheblock/wallet/core/recovery/DigiDollarTransferService.kt",
    )
    private val nativeSites = listOf("native/src/main/jni/bridge/jni_transaction.c")

    /** An amount, a fee, a size or an address; a fee RATE (`feePerKb`) and a count (`.size`) are neither. */
    private val amountWord = Regex("""(?i)(sats?\b|sat\)|cents|amount|vsize|feeSat|changeAmount|address)""")

    /** The interpolations inside each log call: `${...}` and `$name`. */
    private fun interpolations(call: String): List<String> =
        Regex("""\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_.]*)""").findAll(call).map { it.groupValues[1] + it.groupValues[2] }.toList()

    @Test fun `no Kotlin send-path log line interpolates an amount or fee`() {
        val hits = kotlinSites.flatMap { path ->
            val text = File(root, path).readText()
            Regex("""(Log\.[diwev]|log)\(\s*(TAG|'[diwe]'|"[^"]*")\s*,([^;]*?)\)\s*(\n|$)""", RegexOption.MULTILINE).findAll(text)
                .filter { m -> interpolations(m.groupValues[3]).any { !it.endsWith(".size") && amountWord.containsMatchIn(it) } }
                .map { "$path: ${m(it)}" }.toList()
        }
        assertEquals("a send-path log line carries an amount or fee", emptyList<String>(), hits)
    }

    @Test fun `no native send-path log line prints a fee or size`() {
        val hits = nativeSites.flatMap { path ->
            File(root, path).readLines().filter { it.contains("LOG") && Regex("""(?i)(fee|bytes)\s*%""").containsMatchIn(it) }
                .map { "$path: ${it.trim()}" }
        }
        assertEquals("a native send-path log line prints a fee or size", emptyList<String>(), hits)
    }

    private fun m(r: MatchResult) = r.value.trim().take(120)
}
