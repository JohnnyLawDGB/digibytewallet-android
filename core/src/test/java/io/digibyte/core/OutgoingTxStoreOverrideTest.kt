package io.digibyte.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding 1 — the activity-list override decision.
 *
 * The OutgoingTxStore override force-renders a recorded tx as a negative
 * "Sent". That is correct for genuine external sends (where
 * BRWalletAmountSentByTx under-counts) but WRONG for a self-transfer sweep
 * (recovering legacy funds into our OWN wallet): that tx increases the balance
 * and the C core categorizes it as a receive, so overriding it would misrender
 * the recovery as a large outgoing "Sent" to our own address.
 *
 * Pure decision — no Android Context / JNI needed.
 */
class OutgoingTxStoreOverrideTest {

    private fun record(isSelf: Boolean) =
        OutgoingTxStore.Record(
            sentSats = 424_797_024L,
            feeSats = 1_000L,
            toAddress = "dgb1qownreceiveaddress",
            isSelfTransfer = isSelf,
        )

    @Test
    fun noRecord_doesNotOverride() {
        // No recorded send: leave the C core's amount alone.
        assertFalse(OutgoingTxStore.shouldApplyOutgoingOverride(null))
    }

    @Test
    fun externalSend_appliesOverride() {
        // Genuine external send — the corrected negative amount must still win
        // (this is the partial-undercount fix; it must NOT regress).
        assertTrue(OutgoingTxStore.shouldApplyOutgoingOverride(record(isSelf = false)))
    }

    @Test
    fun selfTransferSweep_suppressesOverride() {
        // Recovery into our own wallet: let the receive categorization stand.
        assertFalse(OutgoingTxStore.shouldApplyOutgoingOverride(record(isSelf = true)))
    }

    @Test
    fun record_defaultsToExternalSend() {
        // Back-compat: records written before the flag existed (and every
        // ordinary send) default to isSelfTransfer=false, so they keep the
        // override — no behavior change for existing stored sends.
        val legacyStyle = OutgoingTxStore.Record(100L, 1L, "someaddr")
        assertFalse(legacyStyle.isSelfTransfer)
        assertTrue(OutgoingTxStore.shouldApplyOutgoingOverride(legacyStyle))
    }
}
