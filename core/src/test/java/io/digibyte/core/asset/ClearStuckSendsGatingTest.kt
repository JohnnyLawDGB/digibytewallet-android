package io.digibyte.core.asset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the exact output-line -> [DeadSendPredicate.OutSats] parsing that
 * [io.digibyte.core.WalletManager.clearStuckSends] performs before calling
 * [DeadSendPredicate.isDead]. WalletManager itself can't be unit-tested here —
 * clearStuckSends calls several `external fun`s on NativeBridge (JNI), which
 * requires a loaded native lib (device/emulator only) — so this pins the pure
 * Kotlin gating decision in isolation: given the "vout|sats|scriptHex" wire
 * format from [io.digibyte.core.bridge.NativeBridge.getTransactionOutputsForHash],
 * a genuinely dead send is dropped and a merely-slow-but-valid one is spared.
 */
class ClearStuckSendsGatingTest {

    /** Mirrors clearStuckSends' exact parse: `it.split("|").getOrNull(1)?.toLongOrNull() ?: 0L`. */
    private fun parseSats(line: String): DeadSendPredicate.OutSats =
        DeadSendPredicate.OutSats(line.split("|").getOrNull(1)?.toLongOrNull() ?: 0L)

    @Test
    fun validSendAboveDustFloor_isNotDead_spared() {
        val outputs = listOf(
            "0|30000|76a914aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa88ac",
            "1|0|6a0c48656c6c6f20776f726c64", // OP_RETURN, sats=0, ignored by isDead
        ).map(::parseSats)
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }

    @Test
    fun validSendWithSubDustOutput_isDead_dropped() {
        val outputs = listOf(
            "0|700|76a914aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa88ac", // sub-dust asset marker
            "1|0|6a0c48656c6c6f20776f726c64",
        ).map(::parseSats)
        assertTrue(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }

    @Test
    fun invalidTx_isDead_evenWithHealthyOutputs() {
        val outputs = listOf("0|30000|76a914aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa88ac").map(::parseSats)
        assertTrue(DeadSendPredicate.isDead(isValid = false, outputs = outputs))
    }

    @Test
    fun malformedLine_missingSatsField_defaultsToZeroSats_neverMisclassifiesAsDead() {
        // A line with no second "|"-field (getOrNull(1) -> null) parses to 0
        // sats -- treated like an OP_RETURN output (ignored), never sub-dust.
        val outputs = listOf("garbled").map(::parseSats)
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }

    @Test
    fun nullNativeBridgeResult_fallsBackToEmptyOutputs_isNotDeadOnValidTx() {
        // Mirrors `NativeBridge.getTransactionOutputsForHash(txid)?.toList() ?: emptyList()`
        // when the native call returns null (e.g. tx unknown to BRWallet).
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = emptyList()))
    }

    @Test
    fun atDustFloorExactly_isNotDead_spared() {
        val outputs = listOf("0|5460|76a914aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa88ac").map(::parseSats)
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }
}
