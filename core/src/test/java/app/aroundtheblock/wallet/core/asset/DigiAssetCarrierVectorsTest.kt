package app.aroundtheblock.wallet.core.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DA-014-REF parity: [DigiAssetCarrierRule] answers every vector in the core submodule's
 * `vectors/da-carrier/vectors.txt` as written. The native rule (BRDigiAsset.c `BRTxAssetCarrier`)
 * is held to the same file by the host KAT `asset_carrier_reference_kat`, so a change to the rule
 * on one side only fails on the host, in every CI run. The vectors are DigiAsset Core's rule
 * written down by hand, not generated from either implementation.
 */
class DigiAssetCarrierVectorsTest {

    private val vectors: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "native/src/main/jni/digibytewallet-core/vectors/da-carrier/vectors.txt")
            if (f.isFile) return@lazy f
            dir = dir.parentFile
        }
        error("vectors/da-carrier/vectors.txt not found above ${System.getProperty("user.dir")}: is the core submodule checked out?")
    }

    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun everyVerdictIsTheSharedRule() {
        val decoder = DigiAssetDecoder()
        var read = 0
        val wrong = mutableListOf<String>()
        vectors.forEachLine { line ->
            val f = line.trim().split(' ')
            if (f.size < 3 || f[0] != "v") return@forEachLine
            read++
            val got = when (val v = DigiAssetCarrierRule.verdict(f.drop(3).map(::hex), decoder)) {
                DigiAssetCarrierRule.Verdict.None -> "NONE"
                is DigiAssetCarrierRule.Verdict.Carrier -> "CARRIER:${v.index}"
                DigiAssetCarrierRule.Verdict.Undecided -> "UNDECIDED"
            }
            if (got != f[2]) wrong += "${f[1]}: Kotlin $got, vectors ${f[2]}"
        }
        assertTrue("only $read vectors were read", read >= 39)
        assertEquals("DigiAssetCarrierRule disagrees with the shared vectors: $wrong", 0, wrong.size)
    }
}
