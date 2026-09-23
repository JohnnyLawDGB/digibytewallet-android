package io.digibyte.core.recovery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate: recovery hands its signers only values a parent transaction states.
 *
 * The behaviour is tested through the services (AmountProvenanceGateTest, ForeignAssetInputProofTest);
 * this pins the shape, so a second path to a signer cannot be added beside the proven one unnoticed.
 */
class SweepSignsProvenInputsSourceGateTest {

    private fun code(path: String): String =
        File(path).readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines().joinToString("\n") { it.substringBefore("//") }

    private val sweep by lazy { code("src/main/java/io/digibyte/core/recovery/LegacySweepService.kt") }
    private val move by lazy { code("src/main/java/io/digibyte/core/recovery/ForeignAssetTransferService.kt") }

    @Test fun `the sweep assembles its signer inputs from parent-proven copies only`() {
        assertTrue(
            "the sweep's candidates must be the proven copies",
            Regex("""val proven\s*=\s*stillSweepable\.mapNotNull\s*\{\s*checked\.proven\(it\)\s*\}""")
                .containsMatchIn(sweep),
        )
        val calls = Regex("""assembleSweepInputs\(([^\n]*)\)""").findAll(sweep)
            .filterNot { it.value.contains("result: ") }
            .map { it.groupValues[1].trim() }.toList()
        assertEquals("one assembly, from the proven copies", listOf("result.copy(utxos = proven)"), calls)
    }

    /** GUARD: already true before the parent check existed; kept so it stays true. */
    @Test fun `GUARD the sweep signs only the assembled inputs, through one native call`() {
        assertEquals(1, Regex("""\bsignSweep\(seed,\s*profile,\s*inputs,""").findAll(sweep).count())
        assertEquals(1, Regex("""NativeBridge\.buildAndSignLegacySweep\(""").findAll(sweep).count())
        assertTrue(Regex("""amounts\s*=\s*inputs\.amounts\.toLongArray\(\)""").containsMatchIn(sweep))
        assertTrue(Regex("""scriptPubKeysHex\s*=\s*inputs\.scripts\.toTypedArray\(\)""").containsMatchIn(sweep))
    }

    @Test fun `the asset move and the split spend parent-proven copies only`() {
        assertTrue(
            Regex("""val provenPlain\s*=\s*partition\.sweepable\.mapNotNull\s*\{\s*checked\.proven\(it\)\s*\}""")
                .containsMatchIn(move),
        )
        val spends = Regex("""toSpend\(([^,]+),\s*byAddress\)""").findAll(move).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("it", "provenAsset", "it"), spends)
        assertEquals(
            2,
            Regex("""provenPlain\.mapNotNull\s*\{\s*toSpend\(it,\s*byAddress\)\s*\}""").findAll(move).count(),
        )
        assertTrue(Regex("""val provenAsset\s*=\s*checked\.proven\(utxo\)""").containsMatchIn(move))
    }
}
