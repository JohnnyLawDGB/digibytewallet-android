package io.digibyte.core.digidollar

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.digidollar.LockTiers
import io.digibyte.digidollar.MintBuilder
import io.digibyte.digidollar.RedemptionBuilder
import io.digibyte.digidollar.Sighash
import io.digibyte.digidollar.Taproot
import io.digibyte.digidollar.TxOutput
import java.math.BigInteger
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trip KATs for the DigiDollar signing path (issue #11 wiring):
 * Kotlin-built Mint/Redemption → [DigiDollarTxSigner] → fully-signed bytes.
 *
 * The wallet signer's ECDSA output is verified INDEPENDENTLY: the witness
 * signature must verify (BouncyCastle low-level secp256k1 math) against the
 * BIP143 digest the fixture-proven Kotlin [Sighash] computes — if the C
 * signer ever commits to different bytes than the Kotlin builder produced,
 * these tests fail. Schnorr inputs are verified inside the signer itself
 * (ddSchnorrVerify against each prevout's key); the tests add structural
 * witness checks and the negative wrong-key-path case.
 */
@RunWith(AndroidJUnit4::class)
class DigiDollarTxSignerTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private lateinit var walletSpkHex: String
    private lateinit var walletHash160Hex: String

    @Before
    fun createWallet() {
        assertTrue(NativeBridge.createWalletFromBytes(mnemonic.toByteArray()))
        val address = checkNotNull(NativeBridge.getReceiveAddress(0, 2))
        val spk = checkNotNull(NativeBridge.addressToScriptPubKey(address))
        walletSpkHex = spk.toHex()
        assertTrue("P2WPKH prevout expected", walletSpkHex.startsWith("0014"))
        walletHash160Hex = walletSpkHex.substring(4)
    }

    @After
    fun lock() {
        NativeBridge.lockSession()
    }

    // ---- Mint ----

    @Test
    fun mint_round_trip_signs_the_funding_input_over_the_kotlin_digest() {
        val fundingValue = 10_000_000_000_000L // 100k DGB — covers any tier
        val unsigned = MintBuilder.buildUnsigned(
            fundingUtxo = MintBuilder.FundingUtxo("aa".repeat(32), 1, fundingValue),
            ddCents = 10_000,
            tier = LockTiers.byIndex(0),
            oraclePriceMicroUsd = 13_420,
            tipHeight = 651,
            feeSats = MintBuilder.DEFAULT_FEE_SATS,
            ownerKeyHex = checkNotNull(NativeBridge.ddDeriveOwnerKey(20, 0, 0)).toHex(),
            changePubKeyHash160Hex = walletHash160Hex,
            ecOps = NativeEcOps,
        )

        val signed = DigiDollarTxSigner.signMint(
            unsigned,
            fundingPrevout = TxOutput(fundingValue, walletSpkHex),
        )

        // Witness stack must be P2WPKH: [DER sig + SIGHASH_ALL byte, 33B pubkey].
        val witnesses = parseWitnesses(signed, inputCount = 1)
        val stack = witnesses[0]
        assertEquals(2, stack.size)
        assertEquals(33, stack[1].size)
        assertEquals(0x01, stack[0].last().toInt())

        val digest = Sighash.bip143(
            unsigned.tx,
            inputIndex = 0,
            prevoutValueSats = fundingValue,
            pubKeyHash160Hex = walletHash160Hex,
        )
        assertTrue(
            "C wallet signature must verify against the Kotlin BIP143 digest",
            ecdsaVerify(stack[1], digest, stack[0].dropLast(1).toByteArray()),
        )
    }

    // ---- Redemption ----

    @Test
    fun redemption_round_trip_signs_all_input_kinds_over_the_kotlin_digests() {
        val ownerPath = DigiDollarTxSigner.OwnerKeyPath(20, 0, 0)
        val changePath = DigiDollarTxSigner.OwnerKeyPath(20, 0, 1)
        val ownerKeyHex = checkNotNull(NativeBridge.ddDeriveOwnerKey(20, 0, 0)).toHex()
        val changeKeyHex = checkNotNull(NativeBridge.ddDeriveOwnerKey(20, 0, 1)).toHex()
        val feeValue = 3_000_000_000L

        val unsigned = buildRedemption(ownerKeyHex, changeKeyHex, feeValue)

        val signed = DigiDollarTxSigner.signRedemption(
            unsigned,
            collateralOwnerPath = ownerPath,
            burnPaths = listOf(ownerPath, changePath),
        )

        val witnesses = parseWitnesses(signed, inputCount = 4)
        // vin0 script-path stack: [64B sig, leaf script, control block].
        assertEquals(3, witnesses[0].size)
        assertEquals(64, witnesses[0][0].size)
        assertEquals(unsigned.leafScriptHex, witnesses[0][1].toHex())
        assertEquals(unsigned.controlBlockHex, witnesses[0][2].toHex())
        // Key-path burns: single 64-byte signature each.
        assertEquals(listOf(1, 1), witnesses.slice(1..2).map { it.size })
        assertEquals(listOf(64, 64), witnesses.slice(1..2).map { it[0].size })

        // Fee input: C wallet signature over the Kotlin BIP143 digest.
        val feeStack = witnesses[3]
        assertEquals(2, feeStack.size)
        val digest = Sighash.bip143(
            unsigned.tx,
            inputIndex = 3,
            prevoutValueSats = feeValue,
            pubKeyHash160Hex = walletHash160Hex,
        )
        assertTrue(
            "fee-input signature must verify against the Kotlin BIP143 digest",
            ecdsaVerify(feeStack[1], digest, feeStack[0].dropLast(1).toByteArray()),
        )
    }

    @Test
    fun redemption_refuses_to_sign_a_burn_with_the_wrong_key_path() {
        val ownerKeyHex = checkNotNull(NativeBridge.ddDeriveOwnerKey(20, 0, 0)).toHex()
        val changeKeyHex = checkNotNull(NativeBridge.ddDeriveOwnerKey(20, 0, 1)).toHex()
        val ownerPath = DigiDollarTxSigner.OwnerKeyPath(20, 0, 0)

        val unsigned = buildRedemption(ownerKeyHex, changeKeyHex, 3_000_000_000L)

        try {
            // Second burn sits at index 1's key — signing it with index 0
            // must be caught by the prevout-key self-verification.
            DigiDollarTxSigner.signRedemption(
                unsigned,
                collateralOwnerPath = ownerPath,
                burnPaths = listOf(ownerPath, ownerPath),
            )
            fail("expected the prevout-key self-check to reject the signature")
        } catch (expected: IllegalStateException) {
            assertTrue(
                expected.message.orEmpty().contains("does not verify"),
            )
        }
    }

    private fun buildRedemption(
        ownerKeyHex: String,
        changeKeyHex: String,
        feeValue: Long,
    ): RedemptionBuilder.UnsignedRedemption = RedemptionBuilder.buildUnsigned(
        collateralUtxo = RedemptionBuilder.CollateralUtxo(
            txidHex = "bb".repeat(32),
            vout = 0,
            valueSats = 2_634_128_166_915,
            lockHeight = 500_000,
            ddCents = 10_000,
        ),
        ddUtxos = listOf(
            RedemptionBuilder.DdTokenUtxo(
                "cc".repeat(32),
                0,
                3_000,
                "5120" + Taproot.ddTokenOutputKey(ownerKeyHex, NativeEcOps),
            ),
            RedemptionBuilder.DdTokenUtxo(
                "cc".repeat(32),
                1,
                7_000,
                "5120" + Taproot.ddTokenOutputKey(changeKeyHex, NativeEcOps),
            ),
        ),
        feeUtxo = RedemptionBuilder.FeeUtxo("dd".repeat(32), 2, feeValue, walletSpkHex),
        feeSats = 15_000_000,
        ownerKeyHex = ownerKeyHex,
        collateralReturnScriptPubKeyHex = walletSpkHex,
        dgbChangeScriptPubKeyHex = walletSpkHex,
        ecOps = NativeEcOps,
    )

    // ---- helpers ----

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Minimal segwit reader: returns the witness stacks of a signed tx. */
    private fun parseWitnesses(tx: ByteArray, inputCount: Int): List<List<ByteArray>> {
        var off = 4
        assertEquals("BIP144 marker+flag", listOf<Byte>(0, 1), listOf(tx[off], tx[off + 1]))
        off += 2
        val (nIn, o1) = varInt(tx, off)
        off = o1
        assertEquals(inputCount.toLong(), nIn)
        repeat(inputCount) {
            off += 36 // txid + vout
            val (scriptLen, o) = varInt(tx, off)
            assertEquals("scriptSig must stay empty under segwit", 0L, scriptLen)
            off = o + scriptLen.toInt() + 4 // + sequence
        }
        val (nOut, o2) = varInt(tx, off)
        off = o2
        repeat(nOut.toInt()) {
            off += 8
            val (len, o) = varInt(tx, off)
            off = o + len.toInt()
        }
        val stacks = (0 until inputCount).map {
            val (count, oc) = varInt(tx, off)
            off = oc
            (0 until count.toInt()).map {
                val (len, ol) = varInt(tx, off)
                off = ol + len.toInt()
                tx.copyOfRange(ol, off)
            }
        }
        assertEquals("locktime must end the tx", tx.size, off + 4)
        return stacks
    }

    private fun varInt(data: ByteArray, off: Int): Pair<Long, Int> {
        val first = data[off].toInt() and 0xff
        return when {
            first < 0xfd -> first.toLong() to off + 1
            first == 0xfd -> {
                val v = (data[off + 1].toInt() and 0xff) or ((data[off + 2].toInt() and 0xff) shl 8)
                v.toLong() to off + 3
            }
            else -> throw AssertionError("unexpectedly large varint in a test tx")
        }
    }

    /** Textbook ECDSA verification over secp256k1 — BC point math only. */
    private fun ecdsaVerify(pubKey: ByteArray, digest: ByteArray, derSig: ByteArray): Boolean {
        val params = CustomNamedCurves.getByName("secp256k1")
        val n = params.n
        val (r, s) = derDecode(derSig)
        if (r.signum() <= 0 || r >= n || s.signum() <= 0 || s >= n) return false
        val e = BigInteger(1, digest)
        val w = s.modInverse(n)
        val q = params.curve.decodePoint(pubKey)
        val point = params.g.multiply(e.multiply(w).mod(n))
            .add(q.multiply(r.multiply(w).mod(n))).normalize()
        if (point.isInfinity) return false
        return point.xCoord.toBigInteger().mod(n) == r
    }

    /** DER SEQUENCE { INTEGER r, INTEGER s } → (r, s). */
    private fun derDecode(sig: ByteArray): Pair<BigInteger, BigInteger> {
        check(sig[0].toInt() == 0x30) { "not a DER sequence" }
        var off = 2
        check(sig[off].toInt() == 0x02)
        val rLen = sig[off + 1].toInt()
        val r = BigInteger(1, sig.copyOfRange(off + 2, off + 2 + rLen))
        off += 2 + rLen
        check(sig[off].toInt() == 0x02)
        val sLen = sig[off + 1].toInt()
        val s = BigInteger(1, sig.copyOfRange(off + 2, off + 2 + sLen))
        return r to s
    }
}
