package io.digibyte.service

internal enum class CfRestoreResetReason {
    MISSING_LEDGER,
    MISSING_TRANSACTION_CHECKPOINT,
    INVALID_LEDGER,
    INVALID_SAVED_BLOCKS,
    HEADER_WINDOW_MISSING,
}

internal fun cfRestoreResetReason(
    ledger: ByteArray?,
    savedBlocks: ByteArray?,
    wasSynced: Boolean,
    hasTransactionCheckpoint: Boolean,
): CfRestoreResetReason? {
    if (wasSynced && !hasTransactionCheckpoint) {
        return CfRestoreResetReason.MISSING_TRANSACTION_CHECKPOINT
    }
    if (ledger == null) {
        return if (wasSynced && savedBlocks != null) CfRestoreResetReason.MISSING_LEDGER else null
    }

    val state = parseCfLedgerState(ledger) ?: return CfRestoreResetReason.INVALID_LEDGER
    if (savedBlocks == null) return CfRestoreResetReason.HEADER_WINDOW_MISSING

    val blockFloor = parseSavedBlocksFloorHeight(savedBlocks)
        ?: return CfRestoreResetReason.INVALID_SAVED_BLOCKS
    val lowestNeeded = maxOf(state.scannedThrough + 1L, state.abandonedBelow)
    return if (lowestNeeded < blockFloor) CfRestoreResetReason.HEADER_WINDOW_MISSING else null
}

internal fun compactFilterBirthHeight(
    wasSynced: Boolean,
    savedTip: Long,
    walletBirth: Long,
    persistedBirth: Long?,
): Long {
    val resumeDefault = if (savedTip > 0L) maxOf(0L, savedTip - 100L) else walletBirth
    return if (wasSynced) resumeDefault else persistedBirth ?: resumeDefault
}

private data class CfLedgerState(
    val scannedThrough: Long,
    val abandonedBelow: Long,
)

private fun parseCfLedgerState(data: ByteArray): CfLedgerState? {
    if (data.size < 24 || data.u32le(0) != 0x43464c31L) return null
    val version = data.u32le(4)
    if (version !in 1L..3L) return null

    val start = data.u32le(8)
    val scannedThrough = data.u32le(12)
    val requestedThrough = data.u32le(16)
    val abandonedBelow = if (version >= 2L) {
        if (data.size < 28) return null
        data.u32le(20)
    } else {
        0L
    }
    if (scannedThrough + 1L < start || requestedThrough < scannedThrough) return null

    val headerSize = if (version >= 2L) 28 else 24
    val outstandingCount = data.u32le(if (version >= 2L) 24 else 20)
    val outstandingSize = if (version >= 3L) 6L else 5L
    if (outstandingCount !in 0L..4_096L) return null
    val gaveUpCountOffset = headerSize.toLong() + outstandingCount * outstandingSize
    if (gaveUpCountOffset > data.size - 4L) return null
    val gaveUpCount = data.u32le(gaveUpCountOffset.toInt())
    val gaveUpSize = if (version >= 3L) 6L else 4L
    if (gaveUpCount !in 0L..4_096L) return null
    val requiredSize = gaveUpCountOffset + 4L + gaveUpCount * gaveUpSize
    if (requiredSize > data.size.toLong()) return null

    return CfLedgerState(scannedThrough, abandonedBelow)
}

private fun parseSavedBlocksFloorHeight(data: ByteArray): Long? {
    if (data.size < 4) return null
    val count = data.u32le(0)
    if (count !in 1L..100_000L) return null

    var offset = 4
    var floor = Long.MAX_VALUE
    repeat(count.toInt()) {
        if (offset + 8 > data.size) return null
        val blockLength = data.u32le(offset)
        val height = data.u32le(offset + 4)
        if (blockLength > Int.MAX_VALUE || blockLength > data.size - offset - 8) return null
        floor = minOf(floor, height)
        offset += 8 + blockLength.toInt()
    }
    return floor.takeUnless { it == Long.MAX_VALUE }
}

private fun ByteArray.u32le(offset: Int): Long {
    if (offset < 0 || offset + 4 > size) return -1L
    return (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)
}
