package io.digibyte.core.asset.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table from the spec (§1), one test per row plus the rows that must NOT loosen.
 *
 * Why fail-closed: DigiAsset Core clears every output of a transfer that breaks a rule, so a
 * wrong "no rules" here destroys the user's whole input holding while the wallet shows success.
 * A wrong "unknown" merely blocks a send until the walk or the proxy answers.
 */
class AssetTransferRuleGateTest {

    @Test fun `opcode 3 is rule-bound whatever else is known`() {
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(3, true, null))
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(3, false, false))
    }

    @Test fun `opcode 4 is rule-bound whatever else is known`() {
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(4, true, false))
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(4, null, null))
    }

    @Test fun `a rules object from the proxy is rule-bound even when the chain says opcode 1`() {
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(1, true, true))
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(null, null, true))
    }

    @Test fun `a locked rule-free issuance is NONE with or without a proxy answer`() {
        for (op in listOf(1, 2, 5)) {
            assertEquals("opcode $op / api false", TransferRuleState.NONE, AssetTransferRuleGate.stateOf(op, true, false))
            assertEquals("opcode $op / api null", TransferRuleState.NONE, AssetTransferRuleGate.stateOf(op, true, null))
        }
    }

    @Test fun `an unlocked rule-free issuance is UNKNOWN because a reissuance can add rules`() {
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(1, false, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(5, null, null))
    }

    @Test fun `no opcode is UNKNOWN unless the proxy says rule-bound`() {
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(null, true, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(null, null, null))
    }

    @Test fun `an opcode outside the issuance set is UNKNOWN`() {
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(0x15, true, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(0, true, false))
    }

    @Test fun `the proxy can never loosen`() {
        // api=false must not turn an unlocked or unknown asset into NONE
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(null, true, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(1, false, false))
    }

    @Test fun `screen state follows the gate and only NONE allows send`() {
        assertEquals(RuleCheckState.NONE, RuleCheckState.of(TransferRuleState.NONE))
        assertEquals(RuleCheckState.RULE_BOUND, RuleCheckState.of(TransferRuleState.RULE_BOUND))
        assertEquals(RuleCheckState.UNVERIFIED, RuleCheckState.of(TransferRuleState.UNKNOWN))
        assertTrue(RuleCheckState.NONE.allowsSend)
        assertFalse(RuleCheckState.RULE_BOUND.allowsSend)
        assertFalse(RuleCheckState.UNVERIFIED.allowsSend)
        assertFalse(RuleCheckState.CHECKING.allowsSend)
    }
}
