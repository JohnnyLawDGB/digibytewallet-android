package app.aroundtheblock.wallet.core.asset

import app.aroundtheblock.wallet.core.model.AssetOperation

/**
 * Was a DigiAsset OP_RETURN read EXACTLY as [DigiAssetDecoder] reported it?
 *
 * The decoder is lenient on purpose (detection must not crash on odd data): it stops reading
 * instructions at the first one it cannot finish and returns what it has, it does not parse an
 * issuance's rules block, and its amount arithmetic does not check for overflow. DigiAsset Core is
 * not lenient: a payload it cannot read to the end is not an asset transaction at all
 * (`DigiByteTransaction::decodeAssetTX` catches the out-of-range read and returns false). So an
 * allocation computed from a leniently decoded header is not what Core delivers.
 *
 * This re-reads the payload the way Core reads it — header, metadata hash, issuance amount, then
 * instructions until only the issuance footer (8 bits) or nothing is left — with checked
 * arithmetic, and answers true only when that reading ends exactly on the footer and agrees with
 * [header] field for field. Anything else, including a rule-bearing issuance (opcodes 3 and 4,
 * whose rules block is not parsed here) and an issuance before version 3 (whose amounts the
 * decoder rescales), is false: the caller then decides nothing on the device.
 */
object AssetPayloadCheck {

    fun readsExactly(script: ByteArray, header: DecodedAssetHeader): Boolean = try {
        read(script, header)
    } catch (e: RuntimeException) {   // a read past the end, or arithmetic overflow
        false
    }

    private fun read(script: ByteArray, header: DecodedAssetHeader): Boolean {
        val payload = payloadOf(script) ?: return false
        val r = BitReader(payload)
        if (r.readBits(16) != 0x4441L) return false
        val version = r.readBits(8).toInt()
        val opcode = r.readBits(8).toInt()
        if (version != header.version || opcode != header.opcode) return false

        val issuance = header.operation == AssetOperation.ISSUANCE
        if (issuance && (header.hasRules || version < 3)) return false
        if (version < 3 && opcode < 3) r.skip(160)
        if (opcode == 1 || opcode == 3 || opcode == 4) r.skip(256)
        if (issuance && fixedPrecision(r) != header.totalQuantity) return false

        val footer = if (issuance) 8 else 0
        val read = ArrayList<TransferInstruction>()
        while (r.bitsRemaining() > footer) {
            val skip = r.readBits(1) == 1L
            val range = r.readBits(1) == 1L
            val percent = r.readBits(1) == 1L
            val output = r.readBits(if (range) 13 else 5).toInt()
            val amount = if (percent) r.readBits(8) else fixedPrecision(r)
            read += TransferInstruction(skip, range, percent, output, amount, !range && output == 31)
        }
        if (r.bitsRemaining() != footer) return false
        return read == header.transferInstructions
    }

    /** DigiAsset fixed precision (`BitIO::getFixedPrecision`), with overflow thrown. */
    private fun fixedPrecision(r: BitReader): Long {
        var length = r.readBits(3).toInt() + 1
        if (length >= 7) {
            // The third header bit belongs to a 54-bit mantissa.
            val high = (length - 7).toLong()
            return (high shl 53) or r.readBits(53)
        }
        val (mantissa, exponent) = when {
            length == 1 -> r.readBits(5) to 0
            length < 5 -> r.readBits(length * 8 - 7) to r.readBits(4).toInt()
            else -> r.readBits(length * 8 - 6) to r.readBits(3).toInt()
        }
        var value = mantissa
        repeat(exponent) { value = Math.multiplyExact(value, 10L) }
        return value
    }

    /** The bytes of the single data push after OP_RETURN, which must be the whole script. */
    private fun payloadOf(script: ByteArray): ByteArray? {
        if (script.size < 2 || script[0] != 0x6A.toByte()) return null
        val push = script[1].toInt() and 0xFF
        return when {
            push in 1..75 -> if (script.size == 2 + push) script.copyOfRange(2, script.size) else null
            push == 0x4C -> {
                if (script.size < 3) return null
                val len = script[2].toInt() and 0xFF
                if (script.size == 3 + len) script.copyOfRange(3, script.size) else null
            }
            else -> null
        }
    }
}
