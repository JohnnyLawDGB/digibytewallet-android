package io.digibyte.core.security.adamantine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdamantineDecisionBoundaryTest {
    private fun safeRequest(fields: Map<String, String> = emptyMap()): AdamantineWalletActionRequest =
        AdamantineWalletActionRequest(
            walletId = "wallet-local-test",
            deviceId = "device-local-test",
            appId = "io.digibyte",
            sessionId = "session-local-test",
            action = "send_transaction",
            intent = "wallet_send",
            fields = fields
        )

    @Test
    fun `disabled boundary fails closed`() {
        val decision = DisabledAdamantineWalletDecisionBoundary.evaluate(safeRequest())

        assertEquals(AdamantineDecisionStatus.DENY, decision.status)
        assertFalse(decision.allowed)
        assertEquals(REASON_ADAMANTINEOS_ADAPTER_DISABLED, decision.reasonId)
    }

    @Test
    fun `safe wallet action request passes local invariant validation`() {
        val deny = AdamantineWalletAdapterInvariants.validate(
            safeRequest(
                fields = mapOf(
                    "to_address" to "dgb1qexample",
                    "amount_satoshis" to "100000",
                    "fee_per_kb" to "1000"
                )
            )
        )

        assertNull(deny)
    }

    @Test
    fun `blank required request fields fail closed`() {
        val deny = AdamantineWalletAdapterInvariants.validate(
            safeRequest().copy(walletId = "")
        )

        assertEquals(AdamantineDecisionStatus.DENY, deny?.status)
        assertEquals(REASON_ADAMANTINEOS_REQUEST_INVALID, deny?.reasonId)
    }

    @Test
    fun `forbidden seed or key field names fail closed before adapter execution`() {
        val forbidden = listOf(
            "seed",
            "mnemonic_phrase",
            "private_key",
            "xprv",
            "pin",
            "signed_tx"
        )

        for (field in forbidden) {
            val deny = AdamantineWalletAdapterInvariants.validate(
                safeRequest(mapOf(field to "redacted"))
            )

            assertEquals("field=$field", AdamantineDecisionStatus.DENY, deny?.status)
            assertEquals(
                "field=$field",
                REASON_ADAMANTINEOS_FORBIDDEN_WALLET_MATERIAL,
                deny?.reasonId
            )
        }
    }

    @Test
    fun `strict AdamantineOS allow response maps to wallet allow`() {
        val decision = AdamantineExecutionResponseMapper.fromExecutionResponseV2(
            mapOf(
                "v" to "execution_response_v2",
                "status" to "allow",
                "reason_id" to REASON_OK_ALLOW,
                "context_hash" to "a".repeat(64),
                "decision" to mapOf("allowed" to true)
            )
        )

        assertEquals(AdamantineDecisionStatus.ALLOW, decision.status)
        assertTrue(decision.allowed)
        assertEquals(REASON_OK_ALLOW, decision.reasonId)
    }

    @Test
    fun `malformed allow response fails closed`() {
        val decision = AdamantineExecutionResponseMapper.fromExecutionResponseV2(
            mapOf(
                "v" to "execution_response_v2",
                "status" to "allow",
                "reason_id" to "DENY_POLICY",
                "context_hash" to "b".repeat(64),
                "decision" to mapOf("allowed" to true)
            )
        )

        assertEquals(AdamantineDecisionStatus.DENY, decision.status)
        assertEquals(REASON_ADAMANTINEOS_RESPONSE_INVALID, decision.reasonId)
    }

    @Test
    fun `deny response maps to wallet deny`() {
        val decision = AdamantineExecutionResponseMapper.fromExecutionResponseV2(
            mapOf(
                "v" to "execution_response_v2",
                "status" to "deny",
                "reason_id" to "DENY_WALLET_POLICY_GATE",
                "context_hash" to "c".repeat(64),
                "decision" to mapOf("allowed" to false)
            )
        )

        assertEquals(AdamantineDecisionStatus.DENY, decision.status)
        assertFalse(decision.allowed)
        assertEquals("DENY_WALLET_POLICY_GATE", decision.reasonId)
    }

    @Test
    fun `human review final policy state maps to require human confirmation`() {
        val decision = AdamantineExecutionResponseMapper.fromExecutionResponseV2(
            mapOf(
                "v" to "execution_response_v2",
                "status" to "deny",
                "reason_id" to "DENY_HUMAN_GATE",
                "context_hash" to "d".repeat(64),
                "decision" to mapOf("allowed" to false),
                "artifacts" to mapOf(
                    "final_policy" to mapOf(
                        "state" to FINAL_POLICY_HUMAN_REVIEW_REQUIRED
                    )
                )
            )
        )

        assertEquals(AdamantineDecisionStatus.REQUIRE_HUMAN_CONFIRMATION, decision.status)
        assertFalse(decision.allowed)
        assertEquals(FINAL_POLICY_HUMAN_REVIEW_REQUIRED, decision.finalPolicyState)
    }

    @Test
    fun `unknown response shape fails closed`() {
        val decision = AdamantineExecutionResponseMapper.fromExecutionResponseV2(emptyMap())

        assertEquals(AdamantineDecisionStatus.DENY, decision.status)
        assertEquals(REASON_ADAMANTINEOS_RESPONSE_INVALID, decision.reasonId)
    }
}
