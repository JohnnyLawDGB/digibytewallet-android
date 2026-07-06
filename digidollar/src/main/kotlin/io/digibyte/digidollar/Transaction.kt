package io.digibyte.digidollar

import java.security.MessageDigest

/** One transaction input. [txidHex] is display order (as RPC shows it). */
data class TxInput(
    val txidHex: String,
    val vout: Int,
    val sequence: Long,
    val scriptSigHex: String = "",
)

/** One transaction output. */
data class TxOutput(
    val valueSats: Long,
    val scriptPubKeyHex: String,
)

/**
 * A segwit transaction: enough structure to serialize DigiDollar Mint,
 * Transfer, and Redemption shapes byte-exactly (BIP144 marker+flag when
 * any witness is present). [witnesses] holds one stack per input.
 */
data class Transaction(
    val version: Int,
    val locktime: Long,
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    val witnesses: List<List<ByteArray>> = emptyList(),
) {

    /** Full serialization (hex), segwit format when any witness is present. */
    fun serialize(): String = serializeBytes(includeWitness = true).toHex()

    /** Double-SHA256 txid over the witness-stripped serialization, display order. */
    fun txid(): String =
        sha256d(serializeBytes(includeWitness = false)).reversedArray().toHex()

    private fun serializeBytes(includeWitness: Boolean): ByteArray {
        val hasWitness = includeWitness && witnesses.any { it.isNotEmpty() }
        val out = ByteBuilder()

        out.int32le(version)
        if (hasWitness) {
            out.byte(0x00) // marker
            out.byte(0x01) // flag
        }
        out.varInt(inputs.size.toLong())
        for (input in inputs) {
            out.bytes(input.txidHex.hexToByteArray().reversedArray())
            out.int32le(input.vout)
            val scriptSig = input.scriptSigHex.hexToByteArray()
            out.varInt(scriptSig.size.toLong())
            out.bytes(scriptSig)
            out.int32le(input.sequence.toInt())
        }
        out.varInt(outputs.size.toLong())
        for (output in outputs) {
            out.int64le(output.valueSats)
            val spk = output.scriptPubKeyHex.hexToByteArray()
            out.varInt(spk.size.toLong())
            out.bytes(spk)
        }
        if (hasWitness) {
            for (i in inputs.indices) {
                val stack = witnesses.getOrElse(i) { emptyList() }
                out.varInt(stack.size.toLong())
                for (element in stack) {
                    out.varInt(element.size.toLong())
                    out.bytes(element)
                }
            }
        }
        out.int32le(locktime.toInt())
        return out.build()
    }

    companion object {
        internal fun sha256d(data: ByteArray): ByteArray {
            val sha = MessageDigest.getInstance("SHA-256")
            return sha.digest(sha.digest(data))
        }
    }
}

/** Little-endian byte assembly for consensus serialization. */
internal class ByteBuilder {
    private val bytes = mutableListOf<Byte>()

    fun byte(b: Int) = apply { bytes.add(b.toByte()) }

    fun bytes(data: ByteArray) = apply { bytes.addAll(data.asList()) }

    fun int32le(v: Int) = apply {
        for (i in 0..3) bytes.add(((v ushr (8 * i)) and 0xff).toByte())
    }

    fun int64le(v: Long) = apply {
        for (i in 0..7) bytes.add(((v ushr (8 * i)) and 0xff).toByte())
    }

    fun varInt(v: Long) = apply {
        when {
            v < 0xfd -> bytes.add(v.toByte())
            v <= 0xffff -> {
                bytes.add(0xfd.toByte())
                for (i in 0..1) bytes.add(((v ushr (8 * i)) and 0xff).toByte())
            }
            v <= 0xffffffffL -> {
                bytes.add(0xfe.toByte())
                for (i in 0..3) bytes.add(((v ushr (8 * i)) and 0xff).toByte())
            }
            else -> {
                bytes.add(0xff.toByte())
                for (i in 0..7) bytes.add(((v ushr (8 * i)) and 0xff).toByte())
            }
        }
    }

    fun build(): ByteArray = bytes.toByteArray()
}
