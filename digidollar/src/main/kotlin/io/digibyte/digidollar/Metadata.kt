package io.digibyte.digidollar

/**
 * DigiDollar OP_RETURN metadata, mirroring the on-wire layout of DigiByte
 * Core v9.26.4 (observed in Core-built regtest transactions — see the
 * fixture provenance note in the test resources).
 *
 * Layout: OP_RETURN, push2 "DD" (0x4444), push1 type code, then typed
 * fields. Integers are CScriptNum minimal signed little-endian (zero is an
 * empty push); all pushes are direct-length opcodes (0–75 bytes).
 *
 * Build-only: this wallet constructs Mint and Redemption transactions in
 * Kotlin; Transfer construction and ALL incoming-metadata parsing belong to
 * the C core (upstream's golden-vector-proven decoder). The test source set
 * keeps parse cross-checks ([CrossCheckParse]) against the Core fixtures.
 */
data class MintMetadata(
    val ddCents: Long,
    val unlockHeight: Int,
    val lockTier: Int,
    val ownerKeyHex: String,
) {
    init {
        require(ddCents > 0) { "DigiDollar amount must be positive, got $ddCents" }
        require(unlockHeight > 0) { "unlock height must be positive, got $unlockHeight" }
        require(lockTier in LockTiers.ALL.indices) { "unknown Lock tier index: $lockTier" }
    }

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
}

internal val MAGIC = byteArrayOf(0x44, 0x44) // "DD"

/** CScriptNum: minimal signed little-endian, zero = empty. */
internal object ScriptNum {

    /** Decode a CScriptNum, enforcing Core's rules: at most 8 bytes, minimal
     *  encoding (no redundant trailing 0x00/0x80), top bit of the last byte
     *  is the sign. */
    fun decode(bytes: ByteArray): Long {
        require(bytes.size <= 8) { "script number too large: ${bytes.size} bytes" }
        if (bytes.isEmpty()) return 0
        val last = bytes.last().toInt() and 0xff
        if (last and 0x7f == 0) {
            require(bytes.size > 1 && bytes[bytes.size - 2].toInt() and 0x80 != 0) {
                "non-minimal script number encoding"
            }
        }
        var magnitude = 0L
        for (i in bytes.indices.reversed()) {
            val b = bytes[i].toInt() and 0xff
            magnitude = (magnitude shl 8) or (if (i == bytes.size - 1) (b and 0x7f).toLong() else b.toLong())
        }
        return if (last and 0x80 != 0) -magnitude else magnitude
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
