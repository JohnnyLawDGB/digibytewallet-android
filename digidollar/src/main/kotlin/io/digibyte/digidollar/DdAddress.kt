package io.digibyte.digidollar

import java.math.BigInteger

/**
 * DigiDollar address codec: Base58Check over
 * `2-byte network version || 32-byte FINAL tweaked taproot output key`
 * (38 bytes encoded, with the 4-byte double-SHA256 checksum).
 *
 * The payload key is the OUTPUT key — already BIP341-tweaked. A decoded
 * key is used verbatim in the `5120…` scriptPubKey; re-tweaking it pays
 * an unspendable output (fund loss). Mirrors upstream's C decoder
 * (BRDigiDollarAddressDecode); the golden vector is a real testnet26
 * address returned by a v9.26.3 node.
 */
object DdAddress {

    /** DD address network prefixes: `DD…` mainnet, `TD…` testnet, `RD…` regtest. */
    enum class Network(internal val version: ByteArray) {
        MAINNET(byteArrayOf(0x52, 0x85.toByte())),
        TESTNET(byteArrayOf(0xb1.toByte(), 0x29)),
        REGTEST(byteArrayOf(0xa3.toByte(), 0xa4.toByte())),
    }

    /** A validated DD address: its network and the FINAL tweaked output key
     *  (hex) — ready for `5120` + key, never to be tweaked again. */
    data class Decoded(val network: Network, val outputKeyHex: String)

    private const val KEY_BYTES = 32
    private const val CHECKSUM_BYTES = 4
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Encode a 32-byte tweaked output key as a DD address for [network]. */
    fun encode(outputKey: ByteArray, network: Network): String {
        require(outputKey.size == KEY_BYTES) { "output key must be 32 bytes" }
        val payload = network.version + outputKey
        val checksum = Transaction.sha256d(payload).copyOfRange(0, CHECKSUM_BYTES)
        return base58Encode(payload + checksum)
    }

    /** Decode and validate a DD address. Returns null unless the checksum,
     *  payload length, and network version are all valid. */
    fun decode(address: String): Decoded? {
        val raw = base58Decode(address) ?: return null
        if (raw.size != Network.MAINNET.version.size + KEY_BYTES + CHECKSUM_BYTES) return null
        val payload = raw.copyOfRange(0, raw.size - CHECKSUM_BYTES)
        val checksum = raw.copyOfRange(raw.size - CHECKSUM_BYTES, raw.size)
        if (!Transaction.sha256d(payload).copyOfRange(0, CHECKSUM_BYTES).contentEquals(checksum)) {
            return null
        }
        val network = Network.entries.firstOrNull {
            it.version.contentEquals(payload.copyOfRange(0, it.version.size))
        } ?: return null
        return Decoded(network, payload.copyOfRange(network.version.size, payload.size).toHex())
    }

    private fun base58Encode(data: ByteArray): String {
        val leadingZeros = data.takeWhile { it.toInt() == 0 }.count()
        var value = BigInteger(1, data)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (value > BigInteger.ZERO) {
            val (q, r) = value.divideAndRemainder(base)
            sb.append(ALPHABET[r.toInt()])
            value = q
        }
        repeat(leadingZeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    private fun base58Decode(address: String): ByteArray? {
        if (address.isEmpty()) return null
        var value = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (c in address) {
            val digit = ALPHABET.indexOf(c)
            if (digit < 0) return null
            value = value * base + BigInteger.valueOf(digit.toLong())
        }
        val leadingOnes = address.takeWhile { it == ALPHABET[0] }.count()
        val body = value.toByteArray().dropWhile { it.toInt() == 0 }.toByteArray()
        return ByteArray(leadingOnes) + body
    }
}
