package app.aroundtheblock.wallet.core.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K1-RECOVERY, native half: no log line in the sweep / recovery JNI bridge carries an exact amount,
 * an address, an outpoint, a txid or a script. `__android_log_print` ships in release, exactly like
 * android.util.Log, so the Kotlin rule (LogRedactionSourceGateTest) has to hold here too.
 *
 * A source scan, not a KAT: the bridge files include <jni.h> and <android/log.h> and do not build on
 * the host. Each `LOGx(` / `__android_log_print(` / `_peer_log(` call is collected to its balancing
 * paren, and fails if it
 *  - formats a 64-bit integer (`%llu`, `%lld`, `%lu`, `PRIu64`): every amount in this code is a
 *    uint64_t / jlong, while counts and indices are cast to int, so the specifier is the amount; or
 *  - passes an argument named like an amount, address, outpoint, txid or script, unless it goes
 *    through the keyed tag (`BRLogTxTagHex` / `BR_LOG_TX`).
 */
class NativeRecoveryLogGateTest {

    @Test
    fun `K1-RECOVERY no sweep or recovery bridge log line carries an amount, address, outpoint, txid or script`() {
        val violations = mutableListOf<String>()
        SCOPE.forEach { path ->
            nativeLogCalls(findSource(path).readText()).forEach { call ->
                val bad = nativeLeaks(call)
                if (bad.isNotEmpty()) violations.add("${File(path).name}: $bad in $call")
            }
        }
        assertTrue("K1-RECOVERY native log calls carrying chain or amount data:\n" + violations.joinToString("\n"),
            violations.isEmpty())
    }

    /** The rule flags each shape that shipped and passes the coarse facts that replace them. */
    @Test
    fun `K1-RECOVERY native rule flags amounts and passes counts and indices`() {
        listOf(
            """LOGW("x (inputs=%d fee=%llu totalIn=%llu)", (int)n, (unsigned long long)fee, (unsigned long long)totalIn);""",
            """LOGW("foreignDD: %lld cents is outside", (long long)cents);""",
            """LOGW("fee %" PRIu64, impliedFee);""",
            """LOGW("to %s", destAddress);""",
            """LOGW("spending %s:%d", txidHex, (int)vout);""",
            """_peer_log("script %s", scriptHex);""",
        ).forEach { c -> assertTrue("not flagged: $c", nativeLogCalls(c).single().let(::nativeLeaks).isNotEmpty()) }
        listOf(
            """LOGW("rejecting a seed of %d bytes (expected 1..%u)", (int)seedLen, SEED_BUFFER_MAX);""",
            """LOGW("invalid output address at %d", (int)i);""",
            """LOGW("signing failed or refused (ok=%d signOk=%d)", ok, signOk);""",
            """LOGW("implied fee is over %dx the expected", FOREIGN_TX_MAX_FEE_FACTOR);""",
            """LOGI("sent " BR_LOG_TX, BRLogTxTagHex(txid));""",
        ).forEach { c -> assertTrue("wrongly flagged: $c", nativeLogCalls(c).single().let(::nativeLeaks).isEmpty()) }
        // The collector does not mistake the macro definitions for calls.
        assertTrue(nativeLogCalls("#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)").isEmpty())
    }

    private fun nativeLogCalls(content: String): List<String> {
        val start = Regex("""\b(?:LOG[DIWEV]|__android_log_print|_peer_log)\(""")
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
            val call = content.substring(m.range.first, minOf(i + 1, content.length)).replace(Regex("""\s+"""), " ")
            // `#define LOGW(...) __android_log_print(..., __VA_ARGS__)`: the macro, not a call.
            if (!call.contains("__VA_ARGS__") && !call.startsWith("LOGW(...)") && !call.contains("(...)")) out.add(call)
            m = start.find(content, i + 1)
        }
        return out
    }

    private fun nativeLeaks(call: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""%(?:ll|l|j|q)[diux]|PRI[diux]64""").findAll(call).forEach { out.add(it.value) }
        // Arguments after the format string (string literals removed, so a word in the message is not an argument).
        val args = call.replace(Regex(""""(?:[^"\\]|\\.)*""""), "\"\"")
        val tagged = Regex("""BRLogTxTagHex\([^()]*\)|BR_LOG_TX""")
        val name = Regex(
            """(?i)\b\w*(amount|fee|totalIn|totalOut|cents|change|txid|txHash|address|addr|outpoint|vout|script|hex)\w*\b"""
        )
        name.findAll(tagged.replace(args, "")).map { it.value }
            .filterNot { it in CONSTANTS }
            .forEach { out.add(it) }
        return out
    }

    private fun findSource(path: String): File =
        listOf(path, "../$path", "../../$path").map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$path not found")

    private companion object {
        /** The sweep, foreign-asset and foreign-DigiDollar signers, and the recovery gap rule. */
        val SCOPE = listOf(
            "native/src/main/jni/bridge/jni_derive.c",
            "native/src/main/jni/bridge/jni_recovery_gap.c",
            "native/src/main/jni/bridge/jni_stranded_refusal.c",
        )

        /** Named constants, not values: a policy bound is the same on every wallet. */
        val CONSTANTS = setOf("FOREIGN_TX_MAX_FEE_FACTOR")
    }
}
