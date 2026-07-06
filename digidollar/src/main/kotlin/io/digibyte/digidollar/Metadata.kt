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
}

internal fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i ->
        substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
