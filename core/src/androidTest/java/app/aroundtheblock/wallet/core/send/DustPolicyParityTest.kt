package app.aroundtheblock.wallet.core.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aroundtheblock.wallet.core.asset.send.DA_MARKER_SATS
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds Kotlin's [DustPolicy] to `BRDustPolicy.h`.
 *
 * The header is the source of truth: iOS imports it, and the Kotlin is kept as a mirror only
 * because `DustPolicyTest` runs on the host JVM, where NativeBridge cannot load. A line that
 * drifts between the two means one platform signs a payment every node refuses, or refuses one
 * every node would relay. So every decision is compared here, on scripts that reach each branch
 * of the rule (the standard scripts, unspendable ones, each witness-program bound, the
 * CompactSize step, the DigiDollar exemption's shape) and on amounts from Long.MIN_VALUE to
 * Long.MAX_VALUE.
 *
 * Run: ./gradlew :core:connectedMainnetDebugAndroidTest
 *      -Pandroid.testInstrumentationRunnerArguments.class=app.aroundtheblock.wallet.core.send.DustPolicyParityTest
 */
@RunWith(AndroidJUnit4::class)
class DustPolicyParityTest {

    private companion object {
        const val IS_DUST = 1
        const val EXEMPT = 2
    }

    private fun hex(h: String): ByteArray = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun script(prefix: String, fill: Int, count: Int): ByteArray = hex(prefix) + ByteArray(count) { fill.toByte() }

    private val corpus: Map<String, ByteArray> = linkedMapOf(
        "P2PKH" to hex("76a914" + "22".repeat(20) + "88ac"),
        "P2SH" to hex("a914" + "33".repeat(20) + "87"),
        "P2WPKH" to hex("0014" + "44".repeat(20)),
        "P2WSH" to hex("0020" + "55".repeat(32)),
        "P2TR" to hex("5120" + "66".repeat(32)),
        "OP_RETURN data" to hex("6a024444010202c409024c1d"),
        "lone OP_RETURN" to hex("6a"),
        "OP_RETURN second" to hex("516a"),
        "empty" to ByteArray(0),
        "252 bytes" to ByteArray(252) { 0x51 },
        "253 bytes" to ByteArray(253) { 0x51 },
        "10,000 bytes" to ByteArray(10_000) { 0x51 },
        "10,001 bytes" to ByteArray(10_001) { 0x51 },
        "witness 3 bytes" to script("0001", 0x77, 1),
        "witness 4 bytes" to script("0002", 0x77, 2),
        "witness 42 bytes" to script("0028", 0x77, 40),
        "witness 43 bytes" to script("0029", 0x77, 41),
        "v16/40" to script("6028", 0x77, 40),
        "v16/41" to script("6029", 0x77, 41),
        "push not the rest" to script("0015", 0x44, 20),
        "OP_1 33 bytes" to script("511f", 0x66, 31),
        "OP_1 34 bytes, not a program" to script("5121", 0x66, 32),
        "OP_1 35 bytes" to script("5121", 0x66, 33),
    ) + (listOf(0x01, 0x4f, 0x50, 0x61, 0xff) + (0x51..0x60)).associate { v ->
        "version 0x%02x".format(v) to script("%02x14".format(v), 0x44, 20)
    }

    @Test
    fun relayFeeIsTheHeaders() {
        assertEquals(
            "DustPolicy.DUST_RELAY_FEE_PER_KB drifted from BR_DUST_RELAY_FEE_PER_KB",
            NativeBridge.dustRelayFeePerKb(),
            DustPolicy.DUST_RELAY_FEE_PER_KB,
        )
    }

    @Test
    fun linesAgree() {
        for ((name, s) in corpus) {
            assertEquals("$name: the Kotlin line is not the header's", NativeBridge.dustThreshold(s), DustPolicy.threshold(s))
        }
    }

    /** isDust and isDigiDollarExempt, around each line, at the extremes, in and out of a DigiDollar transaction. */
    @Test
    fun decisionsAgree() {
        for ((name, s) in corpus) {
            val line = DustPolicy.threshold(s)
            for (v in listOf(Long.MIN_VALUE, -1L, 0L, 1L, line - 1, line, line + 1, Long.MAX_VALUE)) {
                for (dd in listOf(false, true)) {
                    val kotlin = (if (DustPolicy.isDust(v, s, dd)) IS_DUST else 0) or
                        (if (DustPolicy.isDigiDollarExempt(v, s, dd)) EXEMPT else 0)
                    assertEquals("$name, $v sat, DigiDollar transaction: $dd", NativeBridge.dustDecide(v, s, dd), kotlin)
                }
            }
        }
    }

    /** DustPolicy.isDigiDollarTransaction is the core's BRDigiDollarTxType, which the header defers to. */
    @Test
    fun digiDollarTransactionIsTheCoresClassifier() {
        val versions = listOf(
            0x01000770L, 0x02000770L, 0x03000770L, 0x02FF0770L, 0x00000770L, 0x04000770L, 2L, 0x02000771L,
            0x03010770L, 0x00010770L, 0xFF000770L, 0x80000770L, 0xFFFFFFFFL, 0L, 1L, 0x0770L,
        ).map { it.toInt() }
        for (v in versions) {
            val type = NativeBridge.dustDigiDollarTxType(v)
            assertTrue("version 0x%08x: no transaction could be made".format(v), type >= 0)
            assertEquals("version 0x%08x".format(v), type != 0, DustPolicy.isDigiDollarTransaction(v))
        }
    }

    /** AssetDustPolicyTest's assumption, against the header: a DigiAsset marker clears the line of any recipient. */
    @Test
    fun assetMarkerClearsEveryStandardLine() {
        for (name in listOf("P2PKH", "P2SH", "P2WPKH", "P2WSH", "P2TR")) {
            val s = corpus.getValue(name)
            val line = NativeBridge.dustThreshold(s)
            assertTrue("$name: DA_MARKER_SATS ($DA_MARKER_SATS) is not above the header's line ($line)", DA_MARKER_SATS > line)
            assertEquals("$name: a marker output is refused as dust", 0, NativeBridge.dustDecide(DA_MARKER_SATS, s, false) and IS_DUST)
        }
    }
}
