package io.digibyte.core.asset

import io.digibyte.core.model.AssetData
import io.digibyte.core.model.AssetOperation

/**
 * Decodes DigiAsset v2/v3 OP_RETURN data from raw script bytes.
 *
 * DigiAsset transaction structure (after OP_RETURN + push-data header):
 *   bytes 0-1: 0x44 0x41 ("DA" magic prefix)
 *   byte 2:    version (1, 2, or 3)
 *   byte 3:    opcode
 *       0x01 - Issuance, no rules
 *       0x02 - Issuance, multisig metadata (v1/v2 only, deprecated)
 *       0x03 - Issuance, rewritable rules
 *       0x04 - Issuance, immutable rules
 *       0x05 - Issuance, no metadata or rules
 *       0x15 - Transfer
 *       0x25 - Burn (output index 31 = burn)
 *
 * Fields after the header are encoded using BitIO fixed-precision encoding,
 * a bit-level packed format where values occupy variable widths (1-7 bytes).
 */
class DigiAssetDecoder {

    companion object {
        /** Two-byte magic prefix identifying DigiAsset OP_RETURN data. */
        private const val DA_MAGIC = 0x4441

        /** OP_RETURN opcode value. */
        private const val OP_RETURN: Byte = 0x6a.toByte()

        /** Maximum single-byte push data length. */
        private const val MAX_SINGLE_PUSH = 75

        /** OP_PUSHDATA1 opcode. */
        private const val OP_PUSHDATA1: Int = 0x4c

        /** Minimum number of bytes in DA payload (header only: DA + version + opcode). */
        private const val MIN_DA_PAYLOAD = 4

        /** Dust amount for DigiAsset outputs in satoshis. */
        const val DA_ASSET_DUST_AMOUNT = 700L
    }

    /**
     * Attempt to decode a DigiAsset from a raw OP_RETURN script.
     *
     * @param script Raw scriptPubKey bytes (starting with OP_RETURN).
     * @return [DecodedAssetHeader] if the script contains a valid DA payload, null otherwise.
     */
    fun decode(script: ByteArray): DecodedAssetHeader? {
        if (script.isEmpty()) return null

        val payload = extractPayload(script) ?: return null
        if (payload.size < MIN_DA_PAYLOAD) return null

        // Check DA magic prefix
        val magic = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (magic != DA_MAGIC) return null

        return try {
            parseHeader(payload)
        } catch (_: Exception) {
            // Conservative: return null rather than crash on unexpected data
            null
        }
    }

    /**
     * Quick check whether a script contains a DigiAsset OP_RETURN.
     * Cheaper than full decode when you only need presence detection.
     */
    fun containsAsset(script: ByteArray): Boolean {
        if (script.size < 6) return false
        val payload = extractPayload(script) ?: return false
        if (payload.size < 2) return false
        val magic = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        return magic == DA_MAGIC
    }

    // -- internal --------------------------------------------------------

    /**
     * Extract the data payload from an OP_RETURN script, stripping the
     * OP_RETURN opcode and push-data length prefix.
     */
    private fun extractPayload(script: ByteArray): ByteArray? {
        if (script.isEmpty() || script[0] != OP_RETURN) return null
        if (script.size < 2) return null

        val pushByte = script[1].toInt() and 0xFF
        return when {
            pushByte <= MAX_SINGLE_PUSH -> {
                if (script.size < 2 + pushByte) return null
                script.copyOfRange(2, 2 + pushByte)
            }
            pushByte == OP_PUSHDATA1 -> {
                if (script.size < 3) return null
                val len = script[2].toInt() and 0xFF
                if (script.size < 3 + len) return null
                script.copyOfRange(3, 3 + len)
            }
            else -> null // OP_PUSHDATA2/4 not used in practice for DA
        }
    }

    /**
     * Parse the DA payload into a [DecodedAssetHeader].
     * Payload is the raw bytes starting from 0x44 0x41.
     */
    private fun parseHeader(payload: ByteArray): DecodedAssetHeader? {
        val reader = BitReader(payload)

        // Skip DA magic (already verified)
        reader.skip(16)

        val version = reader.readBits(8).toInt()
        if (version == 0) return null

        val opcode = reader.readBits(8).toInt()

        val operation = when {
            opcode in 1..4 || opcode == 5 -> AssetOperation.ISSUANCE
            opcode == 0x15 -> AssetOperation.TRANSFER
            opcode == 0x25 -> AssetOperation.BURN
            else -> return null // unknown opcode
        }

        // For v1/v2 opcodes 1-2, skip the useless 160-bit (20-byte) SHA1 hash
        if (version < 3 && opcode < 3) {
            if (reader.bitsRemaining() < 160) return null
            reader.skip(160)
        }

        // Extract metadata SHA256 hash if present (opcodes 1, 3, 4)
        var metadataHash: ByteArray? = null
        if (opcode == 1 || opcode == 3 || opcode == 4) {
            if (reader.bitsRemaining() < 256) return null
            metadataHash = reader.readBytes(32)
        }

        // Compute IPFS CID v1 from SHA256 hash if metadata is present (non-zero)
        val metadataCid = if (metadataHash != null && !metadataHash.all { it == 0.toByte() }) {
            sha256ToCidV1(metadataHash)
        } else {
            null
        }

        // Parse issuance-specific fields
        var totalQuantity: Long? = null
        var divisibility = 0
        var locked = false
        var aggregation = Aggregation.AGGREGATABLE

        if (operation == AssetOperation.ISSUANCE) {
            // Amount of assets to create
            totalQuantity = try {
                reader.readFixedPrecision()
            } catch (_: Exception) {
                null
            }

            // Issuance flags are the LAST 8 bits of the payload.
            // We need to peek at them without consuming the transfer instructions.
            val flagsByte = payload.last().toInt() and 0xFF
            divisibility = flagsByte ushr 5
            locked = (flagsByte and 0x10) != 0
            aggregation = when ((flagsByte and 0x0C) ushr 2) {
                0 -> Aggregation.AGGREGATABLE
                1 -> Aggregation.HYBRID
                2 -> Aggregation.DISPERSED
                else -> return null // invalid aggregation type 3
            }

            // Fix amount for version 1: quantity was scaled by 10^divisibility
            if (version == 1 && totalQuantity != null && divisibility > 0) {
                val pow10 = pow10Table[divisibility]
                totalQuantity = totalQuantity / pow10
            }
        }

        // Parse transfer instructions
        val transferInstructions = try {
            parseTransferInstructions(reader, operation)
        } catch (_: Exception) {
            emptyList()
        }

        return DecodedAssetHeader(
            version = version,
            opcode = opcode,
            operation = operation,
            metadataHash = metadataHash,
            metadataCid = metadataCid,
            totalQuantity = totalQuantity,
            divisibility = divisibility,
            locked = locked,
            aggregation = aggregation,
            transferInstructions = transferInstructions
        )
    }

    /**
     * Parse the transfer instruction section from the BitIO stream.
     *
     * Each instruction is:
     *   1 bit:  skip (advance input pointer)
     *   1 bit:  range (if set, output field is 13 bits, applies amount to outputs 0..output)
     *   1 bit:  percent (if set, amount is a percentage byte instead of fixed precision)
     *   5 bits: output index (or 13 bits if range is set)
     *   variable: amount (1 byte if percent, else fixed-precision encoded)
     *
     * For issuance, the last 8 bits are issuance flags (not a transfer instruction).
     */
    private fun parseTransferInstructions(
        reader: BitReader,
        operation: AssetOperation
    ): List<TransferInstruction> {
        val footerBits = if (operation == AssetOperation.ISSUANCE) 8 else 0
        val instructions = mutableListOf<TransferInstruction>()

        while (reader.bitsRemaining() > footerBits) {
            if (reader.bitsRemaining() < 8 + footerBits) break // not enough bits for even minimal instruction

            val skip = reader.readBits(1) == 1L
            val range = reader.readBits(1) == 1L
            val percent = reader.readBits(1) == 1L
            val output = if (range) reader.readBits(13).toInt() else reader.readBits(5).toInt()

            val amount: Long = if (percent) {
                reader.readBits(8)
            } else {
                try {
                    reader.readFixedPrecision()
                } catch (_: Exception) {
                    break
                }
            }

            val burn = !range && output == 31
            instructions.add(
                TransferInstruction(
                    skip = skip,
                    range = range,
                    percent = percent,
                    outputIndex = output,
                    amount = amount,
                    isBurn = burn
                )
            )
        }

        return instructions
    }

    /**
     * Convert a SHA256 hash to IPFS CIDv1 (base32, raw codec).
     *
     * CIDv1 format: version(1) + codec(raw=0x55) + multihash(0x12 = sha256, 0x20 = 32 bytes, hash)
     * Then base32-lower-encode with 'b' prefix.
     */
    private fun sha256ToCidV1(hash: ByteArray): String {
        // CID v1: 0x01 (version) + 0x55 (raw codec) + 0x12 (sha2-256) + 0x20 (32 bytes) + hash
        val cidBytes = ByteArray(4 + 32)
        cidBytes[0] = 0x01
        cidBytes[1] = 0x55
        cidBytes[2] = 0x12
        cidBytes[3] = 0x20
        hash.copyInto(cidBytes, 4)
        return "b" + base32Encode(cidBytes)
    }

    /**
     * RFC 4648 base32 (lowercase, no padding) encoding.
     */
    private fun base32Encode(data: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(alphabet[(buffer ushr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    private val pow10Table = longArrayOf(
        1L, 10L, 100L, 1_000L, 10_000L,
        100_000L, 1_000_000L, 10_000_000L, 100_000_000L
    )
}

/**
 * Decoded header information from a DigiAsset OP_RETURN.
 */
data class DecodedAssetHeader(
    /** DigiAsset protocol version (1, 2, or 3). */
    val version: Int,
    /** Raw opcode byte. */
    val opcode: Int,
    /** High-level operation type. */
    val operation: AssetOperation,
    /** SHA256 hash of the metadata JSON (32 bytes), null if no metadata. */
    val metadataHash: ByteArray?,
    /** IPFS CID v1 (base32) derived from the metadata hash. */
    val metadataCid: String?,
    /** Total quantity of tokens (issuance only). */
    val totalQuantity: Long?,
    /** Decimal places (0-7), from issuance flags. */
    val divisibility: Int,
    /** Whether the asset is locked (no re-issuance). */
    val locked: Boolean,
    /** Aggregation type from issuance flags. */
    val aggregation: Aggregation,
    /** Decoded transfer instructions. */
    val transferInstructions: List<TransferInstruction>
) {
    /**
     * Convert to [AssetData] model for the wallet layer.
     * Uses the first non-burn transfer instruction's amount, or totalQuantity for issuance.
     */
    fun toAssetData(assetId: String = "unknown"): AssetData {
        val qty = when (operation) {
            AssetOperation.ISSUANCE -> totalQuantity ?: 0L
            else -> transferInstructions.firstOrNull { !it.isBurn }?.amount ?: 0L
        }
        return AssetData(
            assetId = assetId,
            quantity = qty,
            operation = operation,
            metadataCid = metadataCid,
            locked = locked,
            divisibility = divisibility
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedAssetHeader) return false
        return version == other.version &&
                opcode == other.opcode &&
                operation == other.operation &&
                metadataHash.contentEquals(other.metadataHash) &&
                metadataCid == other.metadataCid &&
                totalQuantity == other.totalQuantity &&
                divisibility == other.divisibility &&
                locked == other.locked &&
                aggregation == other.aggregation
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + opcode
        result = 31 * result + operation.hashCode()
        result = 31 * result + (metadataHash?.contentHashCode() ?: 0)
        result = 31 * result + (metadataCid?.hashCode() ?: 0)
        return result
    }
}

/** Aggregation mode for a DigiAsset. */
enum class Aggregation {
    /** Tokens are fungible and can be combined freely. */
    AGGREGATABLE,
    /** Tokens from different issuances can be combined but track origin. */
    HYBRID,
    /** Each token is unique (NFT-like). */
    DISPERSED
}

/** A single transfer instruction from the DigiAsset OP_RETURN. */
data class TransferInstruction(
    /** Whether to advance to the next input after this instruction. */
    val skip: Boolean,
    /** Whether this is a range instruction (applies to outputs 0..outputIndex). */
    val range: Boolean,
    /** Whether the amount is a percentage (0-100) instead of an absolute value. */
    val percent: Boolean,
    /** Target output index (0-31 for non-range, 0-8191 for range). */
    val outputIndex: Int,
    /** Amount of tokens (absolute or percentage depending on [percent]). */
    val amount: Long,
    /** True if this instruction burns tokens (output index 31, non-range). */
    val isBurn: Boolean
)
