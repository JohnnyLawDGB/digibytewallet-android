package io.digibyte.core.asset

/**
 * Encodes DigiAsset TRANSFER OP_RETURN payloads — the inverse of
 * [DigiAssetDecoder] for the transfer path. Bit-level layout follows the
 * Short Fixed-precision Float Coding (SFFC) format the decoder expects.
 *
 * Scope: TRANSFER opcode only. ISSUANCE and BURN are separate opcodes with
 * different payload shapes; add those if/when the wallet supports them.
 *
 * Round-tripped against every `TRANSFER_*` fixture in `DigiAssetDecoderTest`:
 * the encoder produces exactly the same hex bytes the decoder's gold-path
 * fixtures contain, which is the strongest correctness signal we can get
 * without on-chain test vectors.
 */
object DigiAssetEncoder {

    /** `DA` magic (bytes 0..1 of any DigiAsset payload). */
    private const val DA_MAGIC_0: Byte = 0x44   // 'D'
    private const val DA_MAGIC_1: Byte = 0x41   // 'A'

    /** OP_RETURN opcode in DGB/Bitcoin script. */
    private const val OP_RETURN: Byte = 0x6A

    /** TRANSFER opcode within the DA payload (`0x15`). */
    const val OPCODE_TRANSFER: Byte = 0x15

    /** DA protocol spec max OP_RETURN payload (excluding OP_RETURN byte and
     *  push-length prefix). 80 bytes matches Bitcoin/DGB consensus.  */
    const val MAX_OP_RETURN_PAYLOAD: Int = 80

    /**
     * One logical transfer instruction — matches the decoder's
     * `TransferInstruction` shape so round-trips are bit-exact.
     *
     * @param skip    If true, advance the input pointer AFTER emitting this
     *                instruction. Exactly the last instruction pulling from
     *                an input should carry skip=true (except the final input,
     *                where skip is a no-op).
     * @param range   If true, the [outputIndex] is 13 bits (0..8191) and the
     *                amount is split evenly across outputs `0..outputIndex`.
     * @param percent If true, the [amount] is encoded as a single byte 0..100
     *                interpreted as a percentage of the input quantity.
     * @param outputIndex Destination output index. 0..31 for non-range,
     *                    0..8191 for range=true. The sentinel 31 with
     *                    range=false is a burn (asset goes nowhere).
     * @param amount  When percent=false, absolute asset-quantity units.
     *                When percent=true, a 0..100 percentage.
     */
    data class TransferInstruction(
        val skip: Boolean,
        val range: Boolean,
        val percent: Boolean,
        val outputIndex: Int,
        val amount: Long,
    ) {
        init {
            require(outputIndex >= 0) { "outputIndex must be non-negative" }
            if (range) {
                require(outputIndex <= 8191) { "range outputIndex max 8191" }
            } else {
                require(outputIndex <= 31) { "non-range outputIndex max 31" }
            }
            if (percent) {
                require(amount in 0..100) { "percent amount must be 0..100" }
            } else {
                require(amount >= 0) { "amount must be non-negative" }
            }
        }

        /** Convenience: output 31 with range=false is a burn per DA spec. */
        val isBurn: Boolean get() = !range && outputIndex == 31
    }

    /**
     * Build a full OP_RETURN scriptPubKey for a DA transfer.
     *
     *   out = [OP_RETURN | pushLen | magic | version | opcode | instructions...]
     *
     * @throws IllegalArgumentException if the resulting payload exceeds
     *         [MAX_OP_RETURN_PAYLOAD] (80 bytes).
     */
    fun encodeTransferScript(version: Int, instructions: List<TransferInstruction>): ByteArray {
        val payload = encodeTransferPayload(version, instructions)
        require(payload.size <= MAX_OP_RETURN_PAYLOAD) {
            "OP_RETURN payload ${payload.size}b exceeds max $MAX_OP_RETURN_PAYLOAD b"
        }
        // Standard Bitcoin PUSHDATA encoding: for 1..75 byte payloads the
        // length is a single byte equal to the length; no OP_PUSHDATA1 needed.
        val script = ByteArray(payload.size + 2)
        script[0] = OP_RETURN
        script[1] = payload.size.toByte()
        System.arraycopy(payload, 0, script, 2, payload.size)
        return script
    }

    /**
     * Build the DA payload only — `[magic | version | opcode | instructions]`
     * — without the OP_RETURN script wrapper. Useful for callers that embed
     * the payload in a non-standard script or want to measure payload size
     * before committing to an OP_RETURN.
     */
    fun encodeTransferPayload(version: Int, instructions: List<TransferInstruction>): ByteArray {
        require(version in 2..3) { "version must be 2 or 3, got $version" }
        require(instructions.isNotEmpty()) { "at least one transfer instruction required" }

        val w = BitWriter(initialCapacityBytes = 16)
        w.writeByte(DA_MAGIC_0)
        w.writeByte(DA_MAGIC_1)
        w.writeByte(version.toByte())
        w.writeByte(OPCODE_TRANSFER)
        for (inst in instructions) writeInstruction(w, inst)
        return w.toByteArray()
    }

    /**
     * Convenience: single-recipient, single-instruction transfer moving the
     * full [quantity] from the first asset input to output index
     * [recipientOutputIndex].
     */
    fun encodeSimpleTransfer(
        version: Int,
        recipientOutputIndex: Int,
        quantity: Long,
    ): ByteArray = encodeTransferScript(
        version,
        listOf(
            TransferInstruction(
                skip = false,
                range = false,
                percent = false,
                outputIndex = recipientOutputIndex,
                amount = quantity,
            )
        )
    )

    // -----------------------------------------------------------------

    private fun writeInstruction(w: BitWriter, inst: TransferInstruction) {
        // Flag bits (same order as the decoder reads).
        w.writeBits(if (inst.skip) 1L else 0L, 1)
        w.writeBits(if (inst.range) 1L else 0L, 1)
        w.writeBits(if (inst.percent) 1L else 0L, 1)

        // Output index: 5 bits normally, 13 bits when range=true.
        if (inst.range) {
            w.writeBits(inst.outputIndex.toLong(), 13)
        } else {
            w.writeBits(inst.outputIndex.toLong(), 5)
        }

        // Amount: exact byte when percent, SFFC otherwise.
        if (inst.percent) {
            w.writeBits(inst.amount, 8)
        } else {
            w.writeFixedPrecision(inst.amount)
        }
    }
}
