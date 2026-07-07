package io.digibyte.digidollar

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.json.JSONObject
import org.junit.Test

/**
 * RedemptionSigning turns an UnsignedRedemption into a fully-witnessed
 * transaction: vin0 gets the script-path stack [signature, leaf script,
 * control block], each DD burn gets its key-path signature, and the fee
 * input's stack is supplied by the wallet signer.
 *
 * Proven by injecting Core's actual fixture signatures through the signing
 * hook: the digests RedemptionSigning computes must be the exact messages
 * Core signed, and the assembled bytes must equal the complete signed
 * fixture — witness placement, stack order, and all.
 */
class RedemptionSigningTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().readText()
        return JSONObject(text).getJSONObject("result")
    }

    private val redeem = fixture("redeem-tx.json")
    private val redeemMint = fixture("redeem-mint-tx.json")
    private val transfer = fixture("transfer-tx.json")

    private val mintMeta = CrossCheckParse.mint(
        redeemMint.getJSONArray("vout").getJSONObject(2)
            .getJSONObject("scriptPubKey").getString("hex"),
    )

    private fun sats(source: JSONObject, n: Int): Long =
        source.getJSONArray("vout").getJSONObject(n)
            .getBigDecimal("value").movePointRight(8).longValueExact()

    private fun spk(source: JSONObject, n: Int): String =
        source.getJSONArray("vout").getJSONObject(n)
            .getJSONObject("scriptPubKey").getString("hex")

    private fun buildFixtureRedemption() = RedemptionBuilder.buildUnsigned(
        collateralUtxo = RedemptionBuilder.CollateralUtxo(
            txidHex = redeemMint.getString("txid"),
            vout = 0,
            valueSats = sats(redeemMint, 0),
            lockHeight = mintMeta.unlockHeight,
            ddCents = mintMeta.ddCents,
        ),
        ddUtxos = listOf(
            RedemptionBuilder.DdTokenUtxo(transfer.getString("txid"), 0, 3_000, spk(transfer, 0)),
            RedemptionBuilder.DdTokenUtxo(transfer.getString("txid"), 1, 7_000, spk(transfer, 1)),
        ),
        feeUtxo = RedemptionBuilder.FeeUtxo(
            txidHex = transfer.getString("txid"),
            vout = 2,
            valueSats = sats(transfer, 2),
            scriptPubKeyHex = spk(transfer, 2),
        ),
        feeSats = 15_435_000,
        ownerKeyHex = mintMeta.ownerKeyHex,
        collateralReturnScriptPubKeyHex = spk(redeem, 0),
        dgbChangeScriptPubKeyHex = spk(redeem, 1),
        ecOps = BouncyCastleEcOps,
    )

    private fun fixtureWitness(inputIndex: Int): List<ByteArray> {
        val stack = redeem.getJSONArray("vin").getJSONObject(inputIndex)
            .getJSONArray("txinwitness")
        return (0 until stack.length()).map { j -> stack.getString(j).hexToByteArray() }
    }

    // The signing hook is keyed by the digest RedemptionSigning computes: if
    // any digest differs from the message Core actually signed, the lookup
    // fails and the test fails — then full byte-parity seals stack order.
    @Test
    fun `injecting Core's fixture signatures reproduces the signed fixture byte-for-byte`() {
        val unsigned = buildFixtureRedemption()

        val expectedDigests = buildMap {
            put(
                Sighash.taproot(
                    unsigned.tx,
                    unsigned.prevouts,
                    inputIndex = 0,
                    leafHash = Taproot.tapLeafHash(unsigned.leafScriptHex),
                ).toHex() to false,
                fixtureWitness(0)[0],
            )
            for (i in 1..2) {
                put(
                    Sighash.taproot(unsigned.tx, unsigned.prevouts, i, leafHash = null)
                        .toHex() to true,
                    fixtureWitness(i)[0],
                )
            }
        }

        val seenIndexes = mutableListOf<Int>()
        val signedTx = RedemptionSigning.witnessedTx(
            unsigned,
            schnorrSign = { inputIndex, digest, tapTweak ->
                seenIndexes.add(inputIndex)
                checkNotNull(expectedDigests[digest.toHex() to tapTweak]) {
                    "unexpected digest/tapTweak — not a message Core signed"
                }
            },
            feeWitness = fixtureWitness(3),
        )
        assertEquals(listOf(0, 1, 2), seenIndexes)

        assertEquals(redeem.getString("hex"), signedTx.serialize())
        assertEquals(redeem.getString("txid"), signedTx.txid())
    }

    // Without the fee witness (production order: wallet signer runs after),
    // everything else must already be in place and the fee stack empty.
    @Test
    fun `fee input stack stays empty until the wallet signer provides it`() {
        val unsigned = buildFixtureRedemption()

        val signedTx = RedemptionSigning.witnessedTx(
            unsigned,
            schnorrSign = { _, _, _ -> ByteArray(64) },
        )

        assertEquals(4, signedTx.witnesses.size)
        assertEquals(3, signedTx.witnesses[0].size) // [sig, leaf, control]
        assertEquals(1, signedTx.witnesses[1].size)
        assertEquals(1, signedTx.witnesses[2].size)
        assertEquals(0, signedTx.witnesses[3].size)
    }

    @Test
    fun `rejects signatures that are not 64 bytes`() {
        val unsigned = buildFixtureRedemption()
        assertFailsWith<IllegalArgumentException> {
            RedemptionSigning.witnessedTx(unsigned, schnorrSign = { _, _, _ -> ByteArray(63) })
        }
    }
}
