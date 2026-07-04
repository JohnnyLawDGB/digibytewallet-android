package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Sign-Task 4 integration: spending a P2TR (Taproot) UTXO through the real JNI
 * path must produce a valid witness-v1 key-path witness.
 *
 * This drives the exact flow the app uses:
 *   createWallet → getReceiveAddress(0,3) [P2TR] → registerRawTransaction (synthesize
 *   a confirmed UTXO paying our own taproot address) → createTransaction (build an
 *   unsigned spend of that UTXO) → signTransaction (BRWalletSignTransaction +
 *   BRTransactionSign) → assert the result is signed with a single 64-byte witness.
 *
 * RED (before the Task-4 wiring): BRTransactionSign has no witness-v1 branch, so the
 * P2TR input is left unsigned, BRTransactionIsSigned() is false, and signTransaction
 * returns null. GREEN: signTransaction returns a fully-signed tx whose sole input
 * carries a 1-element, 64-byte witness stack (isRawTransactionSigned == true). The
 * cryptographic validity of that witness (schnorr-verify under X(Q)) is proven
 * exhaustively by the host KAT native/src/test/host/bip341_signtx_kat.
 *
 * Also asserts NO REGRESSION: a BIP84/P2WPKH-only spend still signs.
 *
 * Fixed vector: the canonical all-zeros-entropy BIP39 mnemonic; the P2TR receive
 * address is the KAT-pinned m/86'/20'/0'/0/0 (see TaprootReceiveAddressTest).
 */
@RunWith(AndroidJUnit4::class)
class TaprootSignTransactionTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about"

    private val expectedTaproot =
        "dgb1pcevt23hht82rkdrjdpwzstmqyj4ngyy42r9cu73rl4n9h5vu6hgsx5tm5q"

    private val paidSat = 250_000_000L
    private val confirmedHeight = 20_000_000L
    private val blockTs = 1_700_000_000L

    @Test
    fun p2trUtxo_signsWithSingle64ByteWitness() {
        assertTrue("wallet should be created", NativeBridge.createWallet(mnemonic))

        val taproot = NativeBridge.getReceiveAddress(0, 3)
        assertEquals("sanity: receive addr is the KAT P2TR", expectedTaproot, taproot)

        val scriptPubKey = NativeBridge.addressToScriptPubKey(taproot!!)
        assertNotNull("P2TR scriptPubKey should resolve", scriptPubKey)

        // Synthesize a confirmed UTXO paying our own P2TR address.
        val fundingTx = buildLegacyTxPaying(scriptPubKey!!, paidSat)
        assertTrue(
            "registerRawTransaction should accept the P2TR-paying tx",
            NativeBridge.registerRawTransaction(fundingTx, confirmedHeight, blockTs),
        )
        assertEquals("wallet should credit the P2TR UTXO", paidSat, NativeBridge.getBalance())

        // Build + sign a spend of that P2TR UTXO (send to our own P2WPKH address).
        val dest = NativeBridge.getReceiveAddress(0, 2)
        assertNotNull("P2WPKH dest address should resolve", dest)

        val unsigned = NativeBridge.createTransaction(dest!!, paidSat / 2, 100_000L)
        assertNotNull("createTransaction should build an unsigned spend of the P2TR UTXO", unsigned)

        // LOAD-BEARING: signing the P2TR input.
        // RED: returns null (P2TR input unsigned → BRTransactionIsSigned false).
        // GREEN: fully-signed tx.
        val signed = NativeBridge.signTransaction(unsigned!!)
        assertNotNull("signTransaction must sign the P2TR input (Task-4 witness-v1 branch)", signed)

        val signedHex = signed!!.toHex()
        assertTrue(
            "signed P2TR tx must report signed",
            NativeBridge.isRawTransactionSigned(signedHex),
        )

        // The spend has exactly one input (the sole P2TR UTXO); its witness must be a
        // stack of exactly one 64-byte element (key-path, SIGHASH_DEFAULT — no flag byte).
        val witnesses = parseWitnessStacks(signed)
        assertEquals("spend should have a single input", 1, witnesses.size)
        val stack = witnesses[0]
        assertEquals("P2TR key-path witness must be a 1-element stack", 1, stack.size)
        assertEquals("the sole witness element must be a 64-byte Schnorr sig", 64, stack[0].size)
    }

    @Test
    fun bip84Utxo_stillSigns_noRegression() {
        assertTrue("wallet should be created", NativeBridge.createWallet(mnemonic))

        val p2wpkh = NativeBridge.getReceiveAddress(0, 2)
        assertNotNull(p2wpkh)
        val scriptPubKey = NativeBridge.addressToScriptPubKey(p2wpkh!!)
        assertNotNull(scriptPubKey)

        val fundingTx = buildLegacyTxPaying(scriptPubKey!!, paidSat)
        assertTrue(NativeBridge.registerRawTransaction(fundingTx, confirmedHeight, blockTs))
        assertEquals(paidSat, NativeBridge.getBalance())

        val dest = NativeBridge.getReceiveAddress(1, 2)
        val unsigned = NativeBridge.createTransaction(dest!!, paidSat / 2, 100_000L)
        assertNotNull("createTransaction (BIP84) should build", unsigned)

        val signed = NativeBridge.signTransaction(unsigned!!)
        assertNotNull("BIP84 spend must still sign (no regression)", signed)
        assertTrue(NativeBridge.isRawTransactionSigned(signed!!.toHex()))

        // P2WPKH witness = 2 items (sig+pubkey), NOT the taproot 1-item shape.
        val stack = parseWitnessStacks(signed)[0]
        assertEquals("P2WPKH witness must be a 2-element stack", 2, stack.size)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    /**
     * Parse a BIP144 segwit-serialized transaction and return each input's witness
     * stack (a list of byte-array items). Assumes a witness-flagged tx (marker 0x00,
     * flag 0x01) — the shape produced when signing a segwit/taproot input.
     */
    private fun parseWitnessStacks(tx: ByteArray): List<List<ByteArray>> {
        var off = 0
        fun u8(): Int = tx[off++].toInt() and 0xff
        fun varint(): Long {
            val n = u8()
            return when {
                n < 0xfd -> n.toLong()
                n == 0xfd -> { val v = (u8().toLong()) or (u8().toLong() shl 8); v }
                n == 0xfe -> {
                    var v = 0L; for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i)); v
                }
                else -> { var v = 0L; for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i)); v }
            }
        }

        off += 4 // version
        val marker = u8()
        val flag = u8()
        require(marker == 0x00 && flag == 0x01) { "not a witness-serialized tx (marker=$marker flag=$flag)" }

        val vin = varint().toInt()
        for (i in 0 until vin) {
            off += 32 + 4        // prevout hash + index
            val sl = varint().toInt()
            off += sl            // scriptSig
            off += 4             // sequence
        }
        val vout = varint().toInt()
        for (i in 0 until vout) {
            off += 8             // value
            val sl = varint().toInt()
            off += sl            // scriptPubKey
        }

        val stacks = ArrayList<List<ByteArray>>()
        for (i in 0 until vin) {
            val items = varint().toInt()
            val stack = ArrayList<ByteArray>()
            for (j in 0 until items) {
                val len = varint().toInt()
                stack.add(tx.copyOfRange(off, off + len))
                off += len
            }
            stacks.add(stack)
        }
        return stacks
    }

    // Same synthetic-UTXO builder as TaprootReloadBalanceTest: a legacy (non-witness)
    // tx with one input carrying a P2PKH-style scriptSig (so BRTransactionIsSigned() is
    // true) and one output paying [scriptPubKey] [amountSat].
    private fun buildLegacyTxPaying(scriptPubKey: ByteArray, amountSat: Long): ByteArray {
        val out = ArrayList<Byte>()
        fun u32le(v: Long) { for (i in 0..3) out.add(((v shr (8 * i)) and 0xff).toByte()) }
        fun u64le(v: Long) { for (i in 0..7) out.add(((v shr (8 * i)) and 0xff).toByte()) }

        u32le(1L)
        out.add(0x01)
        for (i in 0 until 32) out.add(0x11.toByte())
        u32le(0L)

        val scriptSig = ArrayList<Byte>()
        scriptSig.add(0x48.toByte())
        for (i in 0 until 72) scriptSig.add(0x30.toByte())
        scriptSig.add(0x21.toByte())
        scriptSig.add(0x02.toByte())
        for (i in 0 until 32) scriptSig.add(0xAB.toByte())
        require(scriptSig.size < 0xfd) { "scriptSig too long for 1-byte varint" }
        out.add(scriptSig.size.toByte())
        out.addAll(scriptSig)
        u32le(0xffffffffL)

        out.add(0x01)
        u64le(amountSat)
        require(scriptPubKey.size < 0xfd) { "scriptPubKey too long for 1-byte varint" }
        out.add(scriptPubKey.size.toByte())
        for (b in scriptPubKey) out.add(b)

        u32le(0L)
        return out.toByteArray()
    }
}
