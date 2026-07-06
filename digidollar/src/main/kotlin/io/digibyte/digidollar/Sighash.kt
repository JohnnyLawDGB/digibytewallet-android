package io.digibyte.digidollar

import java.security.MessageDigest

/**
 * Signature-hash computation for the two input kinds DigiDollar
 * transactions contain: BIP341 taproot (SIGHASH_DEFAULT, key-path or
 * script-path) and BIP143 P2WPKH (SIGHASH_ALL). Digests are proven by
 * verifying Core's own fixture signatures against them.
 */
object Sighash {

    private const val SIGHASH_ALL = 0x01

    /**
     * BIP341 SIGHASH_DEFAULT digest for [inputIndex]. [prevouts] must list
     * the spent output (value + scriptPubKey) of every input, in input
     * order. Pass [leafHash] for a script-path spend; null for key-path.
     */
    fun taproot(
        tx: Transaction,
        prevouts: List<TxOutput>,
        inputIndex: Int,
        leafHash: ByteArray?,
    ): ByteArray {
        require(prevouts.size == tx.inputs.size) { "one prevout per input required" }
        require(inputIndex in tx.inputs.indices)

        val shaPrevouts = sha256(
            ByteBuilder().also { b ->
                for (input in tx.inputs) {
                    b.bytes(input.txidHex.hexToByteArray().reversedArray())
                    b.int32le(input.vout)
                }
            }.build(),
        )
        val shaAmounts = sha256(
            ByteBuilder().also { b -> for (p in prevouts) b.int64le(p.valueSats) }.build(),
        )
        val shaScriptPubKeys = sha256(
            ByteBuilder().also { b ->
                for (p in prevouts) {
                    val spk = p.scriptPubKeyHex.hexToByteArray()
                    // Mirrors core 1e32565: a missing prevout script is an
                    // error, but a ZERO amount is legitimate — DigiDollar
                    // token inputs are genuine zero-satoshi P2TR.
                    require(spk.isNotEmpty()) { "missing prevout scriptPubKey" }
                    b.varInt(spk.size.toLong())
                    b.bytes(spk)
                }
            }.build(),
        )
        val shaSequences = sha256(
            ByteBuilder().also { b -> for (input in tx.inputs) b.int32le(input.sequence.toInt()) }
                .build(),
        )
        val shaOutputs = sha256(
            ByteBuilder().also { b ->
                for (output in tx.outputs) {
                    b.int64le(output.valueSats)
                    val spk = output.scriptPubKeyHex.hexToByteArray()
                    b.varInt(spk.size.toLong())
                    b.bytes(spk)
                }
            }.build(),
        )

        val extFlag = if (leafHash != null) 1 else 0
        val msg = ByteBuilder()
            .byte(0x00) // sighash epoch
            .byte(0x00) // hash_type: SIGHASH_DEFAULT
            .int32le(tx.version)
            .int32le(tx.locktime.toInt())
            .bytes(shaPrevouts)
            .bytes(shaAmounts)
            .bytes(shaScriptPubKeys)
            .bytes(shaSequences)
            .bytes(shaOutputs)
            .byte(extFlag * 2) // spend_type (no annex)
            .int32le(inputIndex)
        if (leafHash != null) {
            require(leafHash.size == 32)
            msg.bytes(leafHash)
            msg.byte(0x00) // key_version
            msg.int32le(-1) // codesep_pos 0xffffffff
        }
        return Taproot.taggedHash("TapSighash", msg.build())
    }

    /**
     * BIP143 SIGHASH_ALL digest for a P2WPKH input. [pubKeyHash160Hex] is
     * the 20-byte hash160 from the prevout's `0014...` scriptPubKey.
     */
    fun bip143(
        tx: Transaction,
        inputIndex: Int,
        prevoutValueSats: Long,
        pubKeyHash160Hex: String,
    ): ByteArray {
        require(inputIndex in tx.inputs.indices)
        val hash160 = pubKeyHash160Hex.hexToByteArray()
        require(hash160.size == 20) { "hash160 must be 20 bytes" }

        val hashPrevouts = Transaction.sha256d(
            ByteBuilder().also { b ->
                for (input in tx.inputs) {
                    b.bytes(input.txidHex.hexToByteArray().reversedArray())
                    b.int32le(input.vout)
                }
            }.build(),
        )
        val hashSequence = Transaction.sha256d(
            ByteBuilder().also { b -> for (input in tx.inputs) b.int32le(input.sequence.toInt()) }
                .build(),
        )
        val hashOutputs = Transaction.sha256d(
            ByteBuilder().also { b ->
                for (output in tx.outputs) {
                    b.int64le(output.valueSats)
                    val spk = output.scriptPubKeyHex.hexToByteArray()
                    b.varInt(spk.size.toLong())
                    b.bytes(spk)
                }
            }.build(),
        )

        val input = tx.inputs[inputIndex]
        // scriptCode: canonical P2PKH of the key hash, varint-prefixed.
        val scriptCode = byteArrayOf(0x19, 0x76, 0xa9.toByte(), 0x14) + hash160 +
            byteArrayOf(0x88.toByte(), 0xac.toByte())

        val preimage = ByteBuilder()
            .int32le(tx.version)
            .bytes(hashPrevouts)
            .bytes(hashSequence)
            .bytes(input.txidHex.hexToByteArray().reversedArray())
            .int32le(input.vout)
            .bytes(scriptCode)
            .int64le(prevoutValueSats)
            .int32le(input.sequence.toInt())
            .bytes(hashOutputs)
            .int32le(tx.locktime.toInt())
            .int32le(SIGHASH_ALL)
            .build()
        return Transaction.sha256d(preimage)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
