package io.digibyte.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds Kotlin's [CfRecoveryPolicy] to the C table in `BRCFRecoveryPolicy.h`.
 *
 * ## Why a parity test instead of one implementation
 *
 * The C header is the SOURCE OF TRUTH — iOS imports it directly, so Swift adds no third
 * copy. The Kotlin object survives as a mirror for one reason: it is covered by a plain
 * host-JVM unit test ([io.digibyte.core.sync.CfRecoveryPolicyTest]), and NativeBridge's
 * static initializer throws `UnsatisfiedLinkError` on a host JVM. Making production Kotlin
 * delegate through JNI would move that suite onto a device and lose the fast gate, for no
 * behavioural gain on a pure decision table.
 *
 * So the duplicate is deliberate, and THIS test is the thing that makes it safe: a drift
 * between the two tables becomes a failing test rather than a silent difference in where
 * two platforms resume a scan off the same seed.
 *
 * ## What is actually at stake
 *
 * The table decides whether a compact-filter recovery may delete the CF scan ledger. The
 * ledger is what lets a restart resume near tip instead of at the birth floor; deleting it
 * during a routine stall is what turned a recovery into ~6 hours of re-scanning 1.4M blocks
 * on a Note 8. A one-line drift here reintroduces that.
 *
 * If you add a `CfRecoveryPolicy.Reason`, this test fails until the C enum gains the same
 * value at the same ordinal. That coupling is intentional — see [reasonOrdinalsMatchC].
 */
@RunWith(AndroidJUnit4::class)
class CfRecoveryPolicyParityTest {

    private companion object {
        const val BIT_DROP_FILTER_CHAIN = 1
        const val BIT_DROP_SCAN_LEDGER = 2

        /**
         * Kotlin reason -> the C `BRCFRecoveryReason` value it must correspond to.
         *
         * Written out rather than derived from `ordinal`, because `ordinal` is exactly what
         * could silently drift: reordering the Kotlin enum is a source-compatible change that
         * would repoint every case at the wrong C entry, and a test built on `ordinal` would
         * happily agree with itself. Spelling the mapping out means a reorder fails here.
         */
        val EXPECTED_C_VALUE = mapOf(
            CfRecoveryPolicy.Reason.FILTER_CHAIN_WEDGED to 0,
            CfRecoveryPolicy.Reason.REANCHORED to 1,
            CfRecoveryPolicy.Reason.FILTER_CHAIN_CORRUPT to 2,
            CfRecoveryPolicy.Reason.SCAN_LEDGER_CORRUPT to 3,
            CfRecoveryPolicy.Reason.WALLET_RESET to 4,
        )
    }

    private fun cDecision(cValue: Int): CfRecoveryPolicy.Decision {
        val mask = NativeBridge.cfRecoveryDecide(cValue)
        return CfRecoveryPolicy.Decision(
            dropFilterChain = (mask and BIT_DROP_FILTER_CHAIN) != 0,
            dropScanLedger = (mask and BIT_DROP_SCAN_LEDGER) != 0,
        )
    }

    /** Every Kotlin reason has a declared C counterpart. Guards against a reason added to
     *  one side only — the map above would otherwise just not mention it. */
    @Test
    fun everyKotlinReasonIsMapped() {
        val unmapped = CfRecoveryPolicy.Reason.values().filterNot { EXPECTED_C_VALUE.containsKey(it) }
        assertTrue(
            "Reason(s) with no declared C counterpart: $unmapped. Add the value to " +
                "BRCFRecoveryPolicy.h AND to EXPECTED_C_VALUE.",
            unmapped.isEmpty(),
        )
    }

    /** The C enum values are positional. If someone reorders the Kotlin enum, this catches it
     *  before the decisions silently shift by one. */
    @Test
    fun reasonOrdinalsMatchC() {
        EXPECTED_C_VALUE.forEach { (reason, cValue) ->
            assertEquals(
                "Kotlin ${reason.name}.ordinal drifted from its C value. The bridge passes " +
                    "the C value directly, so a reorder repoints this reason at another " +
                    "reason's decision.",
                cValue,
                reason.ordinal,
            )
        }
    }

    /** The core assertion: identical decisions, reason by reason. */
    @Test
    fun decisionsAgreeForEveryReason() {
        EXPECTED_C_VALUE.forEach { (reason, cValue) ->
            val kotlin = CfRecoveryPolicy.decide(reason)
            val c = cDecision(cValue)
            assertEquals(
                "dropFilterChain disagrees for ${reason.name}",
                kotlin.dropFilterChain,
                c.dropFilterChain,
            )
            assertEquals(
                "dropScanLedger disagrees for ${reason.name} — this is the ~6h rescan bug " +
                    "if the C side drops a ledger the Kotlin side keeps",
                kotlin.dropScanLedger,
                c.dropScanLedger,
            )
        }
    }

    /**
     * The C default case has no Kotlin counterpart — `when` over an enum is exhaustive there,
     * so an unknown reason is unrepresentable. In C the enum is an int, and a future version,
     * a corrupted read or a bad cast can produce one.
     *
     * The wrong answer is "drop everything", which is precisely the pre-fix watchdog shape.
     * Asserted here because no Kotlin test can reach it.
     */
    @Test
    fun unknownReasonKeepsTheScanLedger() {
        listOf(5, 99, 9999, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { bogus ->
            val c = cDecision(bogus)
            assertTrue(
                "C reason $bogus dropped the scan ledger. An unrecognised reason must never " +
                    "destroy the expensive artifact.",
                !c.dropScanLedger,
            )
            assertTrue(
                "C reason $bogus did not drop the filter chain; recovery still means " +
                    "re-fetching it.",
                c.dropFilterChain,
            )
        }
    }

    /**
     * The set invariant from CfRecoveryPolicyTest, re-asserted against C. Kotlin proves its
     * own table; this proves the native one independently, so both must be wrong in the same
     * way to pass.
     */
    @Test
    fun exactlyThreeCReasonsMayDropTheScanLedger() {
        val destroyers = EXPECTED_C_VALUE
            .filterValues { cDecision(it).dropScanLedger }
            .keys
        assertEquals(
            "The set of reasons allowed to destroy the scan ledger changed in C.",
            setOf(
                CfRecoveryPolicy.Reason.SCAN_LEDGER_CORRUPT,
                CfRecoveryPolicy.Reason.FILTER_CHAIN_CORRUPT,
                CfRecoveryPolicy.Reason.WALLET_RESET,
            ),
            destroyers,
        )
    }
}
