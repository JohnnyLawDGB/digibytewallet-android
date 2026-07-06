package io.digibyte.digidollar

import kotlin.test.assertEquals
import org.json.JSONObject
import org.junit.Test

class TaprootTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
    }

    // The redeem fixture's witness carries the Core-accepted normal-redemption
    // leaf for the tier-0 mint (lockHeight 1064, $100, Owner key 9c42...).
    @Test
    fun `normal redemption leaf script matches the Core-accepted witness element`() {
        val redeem = fixture("redeem-tx.json")
        val witness = redeem.getJSONArray("vin").getJSONObject(0)
            .getJSONArray("txinwitness")
        val mintMeta = MintMetadata.parse(
            fixture("redeem-mint-tx.json").getJSONArray("vout").getJSONObject(2)
                .getJSONObject("scriptPubKey").getString("hex"),
        )

        val leaf = Taproot.normalRedemptionLeafScript(
            lockHeight = mintMeta.unlockHeight,
            ddCents = mintMeta.ddCents,
            ownerKeyHex = mintMeta.ownerKeyHex,
        )
        assertEquals(witness.getString(1), leaf)
    }

    // The control block Core accepted: (0xc0 | parity) ++ NUMS internal key
    // ++ the ERR-leaf sibling hash. Byte-equality transitively proves the
    // ERR leaf construction, both tapleaf hashes, and the tweak parity.
    @Test
    fun `collateral control block matches the Core-accepted witness element`() {
        val redeem = fixture("redeem-tx.json")
        val witness = redeem.getJSONArray("vin").getJSONObject(0)
            .getJSONArray("txinwitness")
        val mintMeta = MintMetadata.parse(
            fixture("redeem-mint-tx.json").getJSONArray("vout").getJSONObject(2)
                .getJSONObject("scriptPubKey").getString("hex"),
        )

        val controlBlock = Taproot.collateralControlBlock(
            lockHeight = mintMeta.unlockHeight,
            ddCents = mintMeta.ddCents,
            ownerKeyHex = mintMeta.ownerKeyHex,
            ecOps = BouncyCastleEcOps,
        )
        assertEquals(witness.getString(2), controlBlock)
    }

    // Both fixture mints: Collateral output = NUMS tweaked by the 2-leaf
    // MAST root; DD-token output = Owner key tweaked with no script tree.
    @Test
    fun `collateral and DD-token output keys match both Core-built mints`() {
        for (name in listOf("mint-tx.json", "redeem-mint-tx.json")) {
            val mint = fixture(name)
            val vouts = mint.getJSONArray("vout")
            val meta = MintMetadata.parse(
                vouts.getJSONObject(2).getJSONObject("scriptPubKey").getString("hex"),
            )

            val collateralKey = Taproot.collateralOutputKey(
                lockHeight = meta.unlockHeight,
                ddCents = meta.ddCents,
                ownerKeyHex = meta.ownerKeyHex,
                ecOps = BouncyCastleEcOps,
            )
            assertEquals(
                vouts.getJSONObject(0).getJSONObject("scriptPubKey").getString("hex"),
                "5120$collateralKey",
                "collateral key of $name",
            )

            val ddTokenKey = Taproot.ddTokenOutputKey(meta.ownerKeyHex, BouncyCastleEcOps)
            assertEquals(
                vouts.getJSONObject(1).getJSONObject("scriptPubKey").getString("hex"),
                "5120$ddTokenKey",
                "DD-token key of $name",
            )
        }
    }
}
