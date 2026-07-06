package io.digibyte.digidollar

/**
 * Transfer metadata: one amount per zero-value DigiDollar output, in output
 * order (recipients first, DigiDollar change last). Consensus pairs the
 * amounts with the outputs positionally.
 *
 * Test-only: the wallet neither builds nor parses Transfers in Kotlin —
 * upstream's C core does both (`sendDigiDollar` + its golden-vector-proven
 * decoder). This class exists to cross-verify the Core-built fixtures.
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
}

/**
 * Test-only parsers that cross-verify our builders and the Core-built
 * fixtures. Production detection and metadata decoding happen in the C core
 * (upstream's decoder) — nothing in the app parses DigiDollar scripts or
 * nVersions in Kotlin.
 */
object CrossCheckParse {

    /** The DigiDollar tx type of [version], or null if not DigiDollar-marked. */
    fun version(version: Int): DigiDollarTxType? {
        if (version and 0xffff != DigiDollarVersion.MARKER) return null
        val code = (version ushr 24) and 0xff
        return DigiDollarTxType.entries.firstOrNull { it.code == code }
    }

    /** Parse a Mint OP_RETURN scriptPubKey (hex). */
    fun mint(scriptHex: String): MintMetadata {
        val pushes = typedPushes(scriptHex, DigiDollarTxType.MINT, exactSize = 6)
        val ownerKey = pushes[5]
        require(ownerKey.size == 32) { "Owner key must be 32 bytes" }
        return MintMetadata(
            ddCents = ScriptNum.decode(pushes[2]),
            unlockHeight = ScriptNum.decode(pushes[3]).toInt(),
            lockTier = ScriptNum.decode(pushes[4]).toInt(),
            ownerKeyHex = ownerKey.toHex(),
        )
    }

    /** Parse a Transfer OP_RETURN scriptPubKey (hex). */
    fun transfer(scriptHex: String): TransferMetadata {
        val pushes = typedPushes(scriptHex, DigiDollarTxType.TRANSFER, minSize = 3)
        return TransferMetadata(pushes.drop(2).map { ScriptNum.decode(it) })
    }

    /** Parse a Redemption OP_RETURN scriptPubKey (hex). */
    fun redemption(scriptHex: String): RedemptionMetadata {
        val pushes = typedPushes(scriptHex, DigiDollarTxType.REDEMPTION, exactSize = 3)
        return RedemptionMetadata(ScriptNum.decode(pushes[2]))
    }

    private fun typedPushes(
        scriptHex: String,
        type: DigiDollarTxType,
        exactSize: Int? = null,
        minSize: Int? = null,
    ): List<ByteArray> {
        val pushes = ScriptPushData.read(scriptHex)
        require(pushes.isNotEmpty() && pushes[0].contentEquals(MAGIC)) {
            "not a DigiDollar metadata script"
        }
        exactSize?.let { require(pushes.size == it) { "wrong push count for $type" } }
        minSize?.let { require(pushes.size >= it) { "wrong push count for $type" } }
        require(pushes[1].size == 1 && pushes[1][0].toInt() == type.code) {
            "not a $type metadata script"
        }
        return pushes
    }
}
