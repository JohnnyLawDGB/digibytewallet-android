package io.digibyte.core.asset

import org.junit.Assert.*
import org.junit.Test

class DeadSendPredicateTest {
    @Test
    fun testAllAboveDustFloor() {
        // isValid=true, outputs=[6000, 30000] → false (not dead)
        val outputs = listOf(
            DeadSendPredicate.OutSats(6000),
            DeadSendPredicate.OutSats(30000)
        )
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }

    @Test
    fun testBelowDustFloor() {
        // isValid=true, outputs=[700, …] → true (dead: sub-dust)
        val outputs = listOf(DeadSendPredicate.OutSats(700))
        assertTrue(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }

    @Test
    fun testInvalid() {
        // isValid=false, outputs=[6000] → true (dead: invalid)
        val outputs = listOf(DeadSendPredicate.OutSats(6000))
        assertTrue(DeadSendPredicate.isDead(isValid = false, outputs = outputs))
    }

    @Test
    fun testOpReturnOnly() {
        // isValid=true, outputs=[0] → false (OP_RETURN ignored)
        val outputs = listOf(DeadSendPredicate.OutSats(0))
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }

    @Test
    fun testAtDustFloor() {
        // isValid=true, outputs=[5460] → false (at floor, not below)
        val outputs = listOf(DeadSendPredicate.OutSats(5460))
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }

    @Test
    fun testEmptyOutputs() {
        // isValid=true, outputs=[] → false (no sub-dust)
        val outputs = emptyList<DeadSendPredicate.OutSats>()
        assertFalse(DeadSendPredicate.isDead(isValid = true, outputs = outputs))
    }
}
