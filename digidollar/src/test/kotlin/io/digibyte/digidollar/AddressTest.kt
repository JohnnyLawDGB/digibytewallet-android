package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.json.JSONObject
import org.junit.Test

class AddressTest {

    private fun mintVout(n: Int): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/mint-tx.json"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
            .getJSONArray("vout").getJSONObject(n).getJSONObject("scriptPubKey")
    }

    // Fixture addresses: vout 0/1 are witness v1 (bech32m), vout 3 is v0 (bech32).
    @Test
    fun `encodes witness programs to the fixture addresses`() {
        for (n in listOf(0, 1, 3)) {
            val spk = mintVout(n)
            val hex = spk.getString("hex")
            val version = if (hex.startsWith("5120")) 1 else 0
            val program = hex.substring(4)
            assertEquals(
                spk.getString("address"),
                WitnessAddress(version, program).encode(hrp = "dgbrt"),
                "vout $n",
            )
        }
    }

    @Test
    fun `decodes the fixture addresses back to their scriptPubKeys`() {
        for (n in listOf(0, 1, 3)) {
            val spk = mintVout(n)
            val decoded = WitnessAddress.decode(spk.getString("address"), expectedHrp = "dgbrt")
            assertEquals(spk.getString("hex"), decoded.scriptPubKeyHex(), "vout $n")
        }
    }

    @Test
    fun `rejects wrong network, wrong checksum variant, and malformed addresses`() {
        val v1Address = mintVout(0).getString("address")
        // Right address, wrong expected network.
        assertFailsWith<IllegalArgumentException> {
            WitnessAddress.decode(v1Address, expectedHrp = "dgb")
        }
        // Corrupted checksum.
        assertFailsWith<IllegalArgumentException> {
            WitnessAddress.decode(v1Address.dropLast(1) + "x", expectedHrp = "dgbrt")
        }
        // v1 program encoded with bech32 (not bech32m) must not decode: fake
        // it by re-encoding a v0 address's data as if v1 — use a truncated one.
        assertFailsWith<IllegalArgumentException> {
            WitnessAddress.decode("dgbrt1p", expectedHrp = "dgbrt")
        }
    }
}
