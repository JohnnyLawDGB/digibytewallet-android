package io.digibyte.digidollar

/**
 * Segwit address codec: BIP173 bech32 for witness v0, BIP350 bech32m for
 * v1+ (taproot / DigiDollar Owner-key addresses). [programHex] is the
 * witness program (20 bytes for v0 keyhash, 32 for v1 x-only key).
 */
data class WitnessAddress(val version: Int, val programHex: String) {

    init {
        require(version in 0..16) { "invalid witness version $version" }
        val len = programHex.length / 2
        require(len in 2..40) { "invalid witness program length $len" }
        require(version != 0 || len == 20 || len == 32) { "invalid v0 program length $len" }
    }

    /** The scriptPubKey for this address: versionOp ++ push ++ program. */
    fun scriptPubKeyHex(): String {
        val program = programHex.hexToByteArray()
        val versionOp = if (version == 0) 0x00 else 0x50 + version
        return byteArrayOf(versionOp.toByte(), program.size.toByte()).toHex() + programHex
    }

    /** Encode as bech32 (v0) or bech32m (v1+) with the given network hrp. */
    fun encode(hrp: String): String {
        val data = intArrayOf(version) + convertBits(programHex.hexToByteArray(), 8, 5, pad = true)
        val checksum = createChecksum(hrp, data, constantFor(version))
        return hrp + "1" + (data + checksum).map { CHARSET[it] }.joinToString("")
    }

    companion object {

        private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        private const val BECH32_CONST = 1
        private const val BECH32M_CONST = 0x2bc830a3

        /** Decode and validate an address; throws on any defect. */
        fun decode(address: String, expectedHrp: String): WitnessAddress {
            val lower = address.lowercase()
            require(lower == address || address.uppercase() == address) { "mixed-case address" }
            val sep = lower.lastIndexOf('1')
            require(sep >= 1 && sep + 7 <= lower.length) { "malformed address" }
            val hrp = lower.substring(0, sep)
            require(hrp == expectedHrp) { "wrong network: hrp '$hrp', expected '$expectedHrp'" }

            val data = lower.substring(sep + 1).map {
                CHARSET.indexOf(it).also { idx -> require(idx >= 0) { "invalid character '$it'" } }
            }.toIntArray()
            require(data.size >= 7) { "address too short" }

            val version = data[0]
            require(version in 0..16) { "invalid witness version $version" }
            val expectedConst = constantFor(version)
            require(polymod(hrpExpand(hrp) + data) == expectedConst) {
                "bad checksum (or wrong bech32/bech32m variant for v$version)"
            }

            val program = convertBits5to8(data.copyOfRange(1, data.size - 6))
            return WitnessAddress(version, program.toHex())
        }

        private fun constantFor(version: Int) =
            if (version == 0) BECH32_CONST else BECH32M_CONST

        private fun polymod(values: IntArray): Int {
            val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
            var chk = 1
            for (v in values) {
                val top = chk ushr 25
                chk = ((chk and 0x1ffffff) shl 5) xor v
                for (i in 0..4) if ((top ushr i) and 1 == 1) chk = chk xor gen[i]
            }
            return chk
        }

        private fun hrpExpand(hrp: String): IntArray =
            (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

        private fun createChecksum(hrp: String, data: IntArray, constant: Int): IntArray {
            val values = hrpExpand(hrp) + data + IntArray(6)
            val mod = polymod(values) xor constant
            return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        }

        private fun convertBits(input: ByteArray, from: Int, to: Int, pad: Boolean): IntArray {
            var acc = 0
            var bits = 0
            val out = mutableListOf<Int>()
            val maxv = (1 shl to) - 1
            for (b in input) {
                acc = (acc shl from) or (b.toInt() and 0xff)
                bits += from
                while (bits >= to) {
                    bits -= to
                    out.add((acc ushr bits) and maxv)
                }
            }
            if (pad && bits > 0) out.add((acc shl (to - bits)) and maxv)
            return out.toIntArray()
        }

        private fun convertBits5to8(input: IntArray): ByteArray {
            var acc = 0
            var bits = 0
            val out = mutableListOf<Byte>()
            for (v in input) {
                require(v in 0..31)
                acc = (acc shl 5) or v
                bits += 5
                while (bits >= 8) {
                    bits -= 8
                    out.add(((acc ushr bits) and 0xff).toByte())
                }
            }
            require(bits < 5 && (acc shl (8 - bits)) and 0xff == 0) { "invalid padding" }
            return out.toByteArray()
        }
    }
}
