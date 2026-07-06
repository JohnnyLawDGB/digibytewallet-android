package io.digibyte.digidollar

/**
 * DigiDollar OP_RETURN metadata, mirroring the on-wire layout of DigiByte
 * Core v9.26.4 (observed in Core-built regtest transactions — see the
 * fixture provenance note in the test resources).
 *
 * Layout: OP_RETURN, push2 "DD" (0x4444), push1 type code, then typed
 * fields. Integers are CScriptNum minimal signed little-endian (zero is an
 * empty push); all pushes are direct-length opcodes (0–75 bytes).
 */
data class MintMetadata(
    val ddCents: Long,
    val unlockHeight: Int,
    val lockTier: Int,
    val ownerKeyHex: String,
) {
    /** Build this Mint metadata as an OP_RETURN scriptPubKey (hex). */
    fun build(): String {
        val ownerKey = ownerKeyHex.hexToByteArray()
        require(ownerKey.size == 32) { "Owner key must be 32 bytes" }
        return ScriptPushData.buildOpReturn(
            listOf(
                MAGIC,
                byteArrayOf(DigiDollarTxType.MINT.code.toByte()),
                ScriptNum.encode(ddCents),
                ScriptNum.encode(unlockHeight.toLong()),
                ScriptNum.encode(lockTier.toLong()),
                ownerKey,
            ),
        )
    }

    companion object {
        /** Parse a Mint OP_RETURN scriptPubKey (hex). */
        fun parse(scriptHex: String): MintMetadata {
            val pushes = ScriptPushData.read(scriptHex)
            require(pushes.size == 6 && pushes[0].contentEquals(MAGIC)) {
                "not a DigiDollar metadata script"
            }
            require(pushes[1].size == 1 && pushes[1][0].toInt() == DigiDollarTxType.MINT.code) {
                "not a Mint metadata script"
            }
            val ownerKey = pushes[5]
            require(ownerKey.size == 32) { "Owner key must be 32 bytes" }
            return MintMetadata(
                ddCents = ScriptNum.decode(pushes[2]),
                unlockHeight = ScriptNum.decode(pushes[3]).toInt(),
                lockTier = ScriptNum.decode(pushes[4]).toInt(),
                ownerKeyHex = ownerKey.toHex(),
            )
        }
    }
}

/**
 * Transfer metadata: one amount per zero-value DigiDollar output, in output
 * order (recipients first, DigiDollar change last). Consensus pairs the
 * amounts with the outputs positionally.
 */
data class TransferMetadata(val amountsCents: List<Long>) {

    /** Build this Transfer metadata as an OP_RETURN scriptPubKey (hex). */
    fun build(): String {
        require(amountsCents.isNotEmpty()) { "at least one DigiDollar amount required" }
        require(amountsCents.all { it > 0 }) { "DigiDollar amounts must be positive" }
        val parts = listOf(MAGIC, byteArrayOf(DigiDollarTxType.TRANSFER.code.toByte())) +
            amountsCents.map { ScriptNum.encode(it) }
        return ScriptPushData.buildOpReturn(parts)
    }

    companion object {
        /** Parse a Transfer OP_RETURN scriptPubKey (hex). */
        fun parse(scriptHex: String): TransferMetadata {
            val pushes = ScriptPushData.read(scriptHex)
            require(pushes.size >= 3 && pushes[0].contentEquals(MAGIC)) {
                "not a DigiDollar metadata script"
            }
            require(pushes[1].size == 1 && pushes[1][0].toInt() == DigiDollarTxType.TRANSFER.code) {
                "not a Transfer metadata script"
            }
            return TransferMetadata(pushes.drop(2).map { ScriptNum.decode(it) })
        }
    }
}

/**
 * Redemption metadata: the DigiDollar change amount. Present only when a
 * Redemption burns more DigiDollar than the Mint's amount; exact burns
 * carry no OP_RETURN at all.
 */
data class RedemptionMetadata(val ddChangeCents: Long) {

    /** Build this Redemption metadata as an OP_RETURN scriptPubKey (hex). */
    fun build(): String {
        require(ddChangeCents > 0) { "DigiDollar change must be positive" }
        return ScriptPushData.buildOpReturn(
            listOf(
                MAGIC,
                byteArrayOf(DigiDollarTxType.REDEMPTION.code.toByte()),
                ScriptNum.encode(ddChangeCents),
            ),
        )
    }

    companion object {
        /** Parse a Redemption OP_RETURN scriptPubKey (hex). */
        fun parse(scriptHex: String): RedemptionMetadata {
            val pushes = ScriptPushData.read(scriptHex)
            require(pushes.size == 3 && pushes[0].contentEquals(MAGIC)) {
                "not a DigiDollar metadata script"
            }
            require(pushes[1].size == 1 && pushes[1][0].toInt() == DigiDollarTxType.REDEMPTION.code) {
                "not a Redemption metadata script"
            }
            return RedemptionMetadata(ScriptNum.decode(pushes[2]))
        }
    }
}

private val MAGIC = byteArrayOf(0x44, 0x44) // "DD"

/** CScriptNum: minimal signed little-endian, zero = empty. */
internal object ScriptNum {

    fun decode(bytes: ByteArray): Long {
        var value = 0L
        for (i in bytes.indices.reversed()) {
            value = (value shl 8) or (bytes[i].toLong() and 0xff)
        }
        return value
    }

    /** Minimal LE encoding; zero is empty; sign-padded when the top bit is set. */
    fun encode(value: Long): ByteArray {
        require(value >= 0) { "negative value" }
        if (value == 0L) return ByteArray(0)
        val out = mutableListOf<Byte>()
        var v = value
        while (v > 0) {
            out.add((v and 0xff).toByte())
            v = v ushr 8
        }
        if (out.last().toInt() and 0x80 != 0) out.add(0x00)
        return out.toByteArray()
    }
}

/** OP_RETURN direct-length push parsing. */
internal object ScriptPushData {

    fun read(scriptHex: String): List<ByteArray> {
        val script = scriptHex.hexToByteArray()
        require(script.isNotEmpty() && script[0].toInt() and 0xff == 0x6a) {
            "not an OP_RETURN script"
        }
        val pushes = mutableListOf<ByteArray>()
        var i = 1
        while (i < script.size) {
            val len = script[i].toInt() and 0xff
            require(len <= 75) { "unsupported push opcode at $i" }
            require(i + 1 + len <= script.size) { "truncated push at $i" }
            pushes.add(script.copyOfRange(i + 1, i + 1 + len))
            i += 1 + len
        }
        return pushes
    }

    /** OP_RETURN followed by direct-length pushes of each part, as hex. */
    fun buildOpReturn(pushes: List<ByteArray>): String {
        val out = mutableListOf<Byte>(0x6a)
        for (push in pushes) {
            require(push.size <= 75) { "push too large for a direct-length opcode" }
            out.add(push.size.toByte())
            out.addAll(push.asList())
        }
        return out.toByteArray().toHex()
    }
}

internal fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i ->
        substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
