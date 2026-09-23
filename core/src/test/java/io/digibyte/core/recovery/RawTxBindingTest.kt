package io.digibyte.core.recovery

import io.digibyte.core.reconcile.RawTxEntry
import io.digibyte.core.reconcile.UtxoEntry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RawTxBinding]: an output is read from parent bytes only when those bytes are the transaction the
 * outpoint names, by the id computed from the bytes themselves.
 *
 * Two real transactions pin the id rule:
 *  - the Bitcoin genesis coinbase, a serialization without witness data, whose id is the double
 *    SHA-256 of the bytes as they are;
 *  - the signed native P2WPKH example from BIP 143, whose id is computed over its serialization
 *    WITHOUT the witness. Hashing its bytes as they are gives a different value, and an id function
 *    that did that would read every segwit parent as another transaction.
 */
class RawTxBindingTest {

    private val genesisHex =
        "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d" +
            "0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f" +
            "66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0" +
            "fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c38" +
            "4df7ba0b8d578a4c702b6bf11d5fac00000000"
    private val genesisTxid = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"
    private val genesisOutput = RawTxBinding.Output(
        0, 5_000_000_000L,
        "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51e" +
            "c112de5c384df7ba0b8d578a4c702b6bf11d5fac",
    )

    private val segwitHex =
        "01000000000102fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000049483045" +
            "0221008b9d1dc26ba6a9cb62127b02742fa9d754cd3bebf337f7a55d114c8e5cdd30be022040529b194ba3f9281a" +
            "99f2b1c0a19c0489bc22ede944ccf4ecbab4cc618ef3ed01eeffffffef51e1b804cc89d182d279655c3aa89e815b" +
            "1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f" +
            "85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac" +
            "000247304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a0220573a954c45" +
            "18331561406f90300e8f3358f51928d43c212a8caed02de67eebee0121025476c2e83188368da1ff3e292e7acafc" +
            "db3566bb0ad253f62fc70f07aeee635711000000"
    private val segwitTxid = "e8151a2af31c368a35053ddd4bdb285a8595c769a3ad83e0fa02314a602d4609"
    private val segwitOutputs = listOf(
        RawTxBinding.Output(0, 112_340_000L, "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac"),
        RawTxBinding.Output(1, 223_450_000L, "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac"),
    )

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun sha256d(b: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        return sha.digest(sha.digest(b))
    }

    private fun display(hash: ByteArray) = hash.reversedArray().joinToString("") { "%02x".format(it) }

    /**
     * Test-only model of what the native parser reports as a transaction's id: the double SHA-256 of
     * the serialization with the segwit marker, flag and witness removed. Enough of a reader for the
     * one well-formed vector above, nothing more.
     */
    private fun witnessExcludedId(raw: ByteArray): String {
        if (!(raw[4] == 0.toByte() && raw[5] == 1.toByte())) return display(sha256d(raw))
        var i = 6
        fun varint(): Long {
            val v = raw[i].toInt() and 0xff
            i += 1
            require(v < 0xfd)
            return v.toLong()
        }
        repeat(varint().toInt()) { i += 36; val len = varint().toInt(); i += len + 4 }
        repeat(varint().toInt()) { i += 8; val len = varint().toInt(); i += len }
        val stripped = raw.copyOfRange(0, 4) + raw.copyOfRange(6, i) + raw.copyOfRange(raw.size - 4, raw.size)
        return display(sha256d(stripped))
    }

    private fun binding(
        txidOf: (ByteArray) -> String?,
        outputs: List<RawTxBinding.Output>,
        inputs: List<String> = emptyList(),
    ) = RawTxBinding(txidOf = txidOf, outputsOf = { outputs }, inputsOf = { inputs })

    private fun profile(utxos: List<UtxoEntry>, rawTxs: Map<String, RawTxEntry>) =
        RecoveryScanService.ProfileResult(
            profile = DerivationProfile.BUILT_INS.first { it.label == "BIP44 DGB" },
            addresses = emptyList(),
            derivedAddresses = emptyList(),
            utxos = utxos,
            rawTxs = rawTxs,
        )

    private fun utxo(txid: String, vout: Int, sats: Long, script: String?) =
        UtxoEntry(txid, vout, sats, "Daddr", 1L, script)

    private fun entry(hex: String) = RawTxEntry(hex, 1L, 1L)

    // ---- the id function ------------------------------------------------------------------------

    private val witnessFree: (ByteArray) -> String? = { RawTxBinding.witnessFreeTransactionId(it) }

    @Test fun `the witness-free id of a serialization without witness data is its double SHA-256`() {
        assertEquals(genesisTxid, witnessFree(bytes(genesisHex)))
    }

    @Test fun `the witness-free id function never gives a segwit serialization another id`() {
        // Its bytes as they are hash to something else; answering that would contradict an
        // honest parent. Null leaves the input unproven instead.
        assertTrue(display(sha256d(bytes(segwitHex))) != segwitTxid)
        assertNull(witnessFree(bytes(segwitHex)))
    }

    /**
     * Pins what production wires today. When the native parser's id is wired in its place, this
     * is the one test to change: that id must reproduce [segwitTxid] for [segwitHex].
     */
    @Test fun `production takes the id from the wallet's own parser`() {
        val src = java.io.File("src/main/java/io/digibyte/core/recovery/RawTxBinding.kt").readText()
        val binding = Regex("""val transactionId: \(ByteArray\) -> String\? = (.+)""").find(src)
        assertEquals(
            "the production id function must be the parser's witness-excluded id",
            "{ NativeBridge.rawTransactionId(it) }",
            binding?.groupValues?.get(1)?.trim(),
        )
    }

    @Test fun `the witness-excluded model reproduces both published ids`() {
        assertEquals(genesisTxid, witnessExcludedId(bytes(genesisHex)))
        assertEquals(segwitTxid, witnessExcludedId(bytes(segwitHex)))
    }

    // ---- check ------------------------------------------------------------------------------------

    @Test fun `a genesis-shaped parent proves its output`() {
        val u = utxo(genesisTxid, 0, 5_000_000_000L, genesisOutput.scriptPubKeyHex)
        val v = binding(witnessFree, listOf(genesisOutput))
            .check(profile(listOf(u), mapOf(genesisTxid to entry(genesisHex))))
        val proven = (v as RawTxBinding.Verdict.Checked).proven(u)
        assertEquals(5_000_000_000L, proven!!.amountSatoshi)
        assertTrue(v.unproven.isEmpty())
    }

    @Test fun `a segwit parent is proven by its witness-excluded id`() {
        val u = utxo(segwitTxid, 1, 223_450_000L, segwitOutputs[1].scriptPubKeyHex)
        val v = binding(::witnessExcludedId, segwitOutputs)
            .check(profile(listOf(u), mapOf(segwitTxid to entry(segwitHex))))
        assertEquals(223_450_000L, (v as RawTxBinding.Verdict.Checked).proven(u)!!.amountSatoshi)
    }

    @Test fun `an id function that hashed the witness would read a segwit parent as another transaction`() {
        val u = utxo(segwitTxid, 1, 223_450_000L, segwitOutputs[1].scriptPubKeyHex)
        val v = binding({ display(sha256d(it)) }, segwitOutputs)
            .check(profile(listOf(u), mapOf(segwitTxid to entry(segwitHex))))
        assertTrue(v is RawTxBinding.Verdict.Contradicted)
    }

    @Test fun `with the witness-free id function a segwit parent is unproven, never contradicted`() {
        val u = utxo(segwitTxid, 1, 223_450_000L, segwitOutputs[1].scriptPubKeyHex)
        val v = binding(witnessFree, segwitOutputs)
            .check(profile(listOf(u), mapOf(segwitTxid to entry(segwitHex))))
        v as RawTxBinding.Verdict.Checked
        assertNull(v.proven(u))
        assertEquals(listOf("$segwitTxid:1"), v.unproven)
    }

    @Test fun `bytes of another transaction contradict`() {
        val u = utxo(segwitTxid, 0, 5_000_000_000L, genesisOutput.scriptPubKeyHex)
        val v = binding(witnessFree, listOf(genesisOutput))
            .check(profile(listOf(u), mapOf(segwitTxid to entry(genesisHex))))
        assertTrue(v is RawTxBinding.Verdict.Contradicted)
    }

    @Test fun `a different amount, a missing index or a different script contradict`() {
        val ok = utxo(genesisTxid, 0, 5_000_000_000L, genesisOutput.scriptPubKeyHex)
        val parents = mapOf(genesisTxid to entry(genesisHex))
        val b = binding(witnessFree, listOf(genesisOutput))
        for (bad in listOf(
            ok.copy(amountSatoshi = 4_999_999_999L),
            ok.copy(vout = 1),
            ok.copy(scriptPubKeyHex = "76a914${"00".repeat(20)}88ac"),
        )) {
            assertTrue("$bad", b.check(profile(listOf(bad), parents)) is RawTxBinding.Verdict.Contradicted)
        }
    }

    @Test fun `one contradiction refuses the profile even beside proven inputs`() {
        val ok = utxo(genesisTxid, 0, 5_000_000_000L, genesisOutput.scriptPubKeyHex)
        val bad = utxo(segwitTxid, 0, 1L, "76a914${"00".repeat(20)}88ac")
        val v = binding(witnessFree, listOf(genesisOutput))
            .check(profile(listOf(ok, bad), mapOf(genesisTxid to entry(genesisHex), segwitTxid to entry(genesisHex))))
        assertEquals("$segwitTxid:0", (v as RawTxBinding.Verdict.Contradicted).outpoint)
    }

    @Test fun `no parent, or bytes that do not decode or parse, leave the input unproven`() {
        val u = utxo(genesisTxid, 0, 5_000_000_000L, genesisOutput.scriptPubKeyHex)
        val b = binding(witnessFree, listOf(genesisOutput))
        for (parents in listOf(
            emptyMap(),
            mapOf(genesisTxid to entry("xyz")),
            mapOf(genesisTxid to entry("")),
            mapOf(genesisTxid to entry("0100")),
        )) {
            val v = b.check(profile(listOf(u), parents)) as RawTxBinding.Verdict.Checked
            assertNull(v.proven(u))
            assertEquals(listOf("$genesisTxid:0"), v.unproven)
        }
    }

    @Test fun `a proven input carries the parent's script when the lookup gave none`() {
        val u = utxo(genesisTxid, 0, 5_000_000_000L, null)
        val v = binding(witnessFree, listOf(genesisOutput))
            .check(profile(listOf(u), mapOf(genesisTxid to entry(genesisHex)))) as RawTxBinding.Verdict.Checked
        assertEquals(genesisOutput.scriptPubKeyHex, v.proven(u)!!.scriptPubKeyHex)
    }

    // ---- feePaid ----------------------------------------------------------------------------------

    @Test fun `the fee is what the spent outpoints hold less what the transaction pays`() {
        val b = binding({ null }, listOf(RawTxBinding.Output(0, 850_000L, "00")), listOf("aa:0", "bb:1"))
        assertEquals(50_000L, b.feePaid(byteArrayOf(1), mapOf("aa:0" to 600_000L, "bb:1" to 300_000L)))
    }

    @Test fun `no fee is claimed for a transaction that does not spend exactly the given outpoints`() {
        val pays = listOf(RawTxBinding.Output(0, 850_000L, "00"))
        val spent = mapOf("aa:0" to 600_000L, "bb:1" to 300_000L)
        assertNull(binding({ null }, pays, listOf("aa:0")).feePaid(byteArrayOf(1), spent))
        assertNull(binding({ null }, pays, listOf("aa:0", "bb:1", "cc:2")).feePaid(byteArrayOf(1), spent))
        assertNull(binding({ null }, pays, listOf("aa:0", "aa:0")).feePaid(byteArrayOf(1), spent))
        assertNull(
            binding({ null }, listOf(RawTxBinding.Output(0, 950_000L, "00")), listOf("aa:0", "bb:1"))
                .feePaid(byteArrayOf(1), spent),
        )
    }
}
