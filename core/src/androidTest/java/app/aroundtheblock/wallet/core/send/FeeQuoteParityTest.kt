package app.aroundtheblock.wallet.core.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds Kotlin's [FeeRequest] and [FeeQuote] to `BRFeePolicy.h`'s 4.1.0 rule.
 *
 * The header is the source of truth: iOS imports it, and the Kotlin is kept as a mirror only
 * because `FeeQuoteTest` runs on the host JVM, where NativeBridge cannot load. A fee rule that
 * drifts between the two means the same typed fee builds a different transaction on each
 * platform, or one confirmation says "raised" where the other shows the lower figure. So every
 * decision is compared here: the request on fee-field text (the amount parser's edges included),
 * the rate on totals and sizes across the whole Long range, the custom-total rate as
 * TransactionBuilder.prepareTransaction derives it, and "raised" on both sides of equal.
 *
 * Run: ./gradlew :core:connectedMainnetDebugAndroidTest
 *      -Pandroid.testInstrumentationRunnerArguments.class=app.aroundtheblock.wallet.core.send.FeeQuoteParityTest
 */
@RunWith(AndroidJUnit4::class)
class FeeQuoteParityTest {

    private companion object {
        const val REQUEST_NONE = 0L
        const val REQUEST_DEFAULT = 1L
        const val REQUEST_CUSTOM = 2L

        /**
         * Fee-field text: FeeQuoteTest's, and the amount parser's edges (BigDecimal's grammar, the
         * comma rule, eight decimals, the magnitude bound). ASCII only: `BRAmountParse.h` refuses
         * non-ASCII digits and spaces on purpose (its banner says so), where Kotlin's trim() and
         * BigDecimal take some; that difference is the parser's, documented there, not the fee rule's.
         */
        val TEXTS = listOf(
            "0", "0.00000000", "", "abc", "-1", "-0", "0.0005", "0.00000001", "0.000000001", "1", "1.", ".5",
            "0,0005", " 0.0005", "0.0005 ", "\t0.0005", "00.0005", "1e-4", "1E2", "+0.0005", "21000000000",
            "21000000000.00000001", "92233720368.54775807", "92233720368.54775808", "999999999999999999999",
            "0.12345678", "0.123456789", "1,000", "1,5", "1,000.5", "1,2,3", "1 000", "0x10", "0.0005d",
            "0.0005\u0000", "0..5", "..", ".",
        )

        // 8, 36, 2^62, 4_611_686_018_427_387_950 and 9_223_372_036_854_775_000 are the KAT's mutant-killing
        // rows (fee_policy_quote_kat rate_exact_then_saturated): a remainder landing exactly on the size,
        // the first and last clamps, and q = Long.MAX_VALUE / 1000 exactly.
        val TOTALS = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 8L, 36L, 99L, 100L, 999L, 1_000L, 20_800L, 23_200L, 50_000L,
            1_000_000L, 9_223_372_036_854_775L, 9_223_372_036_854_776L, Long.MAX_VALUE / 1000, 4_611_686_018_427_387_904L,
            4_611_686_018_427_387_950L, 9_223_372_036_854_775_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE)

        val SIZES = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 2L, 7L, 100L, 141L, 208L, 500L, 750L, 999L, 1_000L, 1_001L,
            100_000L, Long.MAX_VALUE / 1000, Long.MAX_VALUE - 1, Long.MAX_VALUE)
    }

    @Test
    fun defaultRateIsTheCores() {
        assertEquals("FeeQuote.DEFAULT_FEE_PER_KB drifted from the core's DEFAULT_FEE_PER_KB",
            NativeBridge.feeDefaultPerKb(), FeeQuote.DEFAULT_FEE_PER_KB)
    }

    @Test
    fun requestsAgree() {
        for (custom in listOf(false, true)) {
            for (text in TEXTS) {
                val c = NativeBridge.feeRequestFor(custom, text.toByteArray(Charsets.UTF_8))
                assertNotNull("the bridge could not read \"$text\"", c)
                val expected = when (val k = FeeRequest.of(custom, text)) {
                    null -> longArrayOf(REQUEST_NONE, 0L)
                    FeeRequest.Default -> longArrayOf(REQUEST_DEFAULT, 0L)
                    is FeeRequest.CustomTotal -> longArrayOf(REQUEST_CUSTOM, k.totalSats)
                }
                assertEquals("custom=$custom \"$text\": the Kotlin request is not the header's",
                    expected.toList(), c!!.toList())
            }
        }
    }

    @Test
    fun ratesAgree() {
        for (total in TOTALS) {
            for (vsize in SIZES) {
                assertEquals("rateForTotal($total, $vsize)", NativeBridge.feeRateForTotal(total, vsize),
                    FeeQuote.rateForTotal(total, vsize))
                // TransactionBuilder.prepareTransaction's custom-total rate (and AssetViewModel's).
                assertEquals("custom-total rate ($total over $vsize)", NativeBridge.feeRateForCustomTotal(total, vsize),
                    FeeQuote.rateForTotal(total, vsize).coerceAtLeast(1L))
            }
        }
    }

    @Test
    fun raisedAgrees() {
        val fees = listOf(Long.MIN_VALUE, -1L, 0L, 999L, 1_000L, 1_001L, 23_200L, 50_000L, Long.MAX_VALUE)
        for (fee in fees) {
            for (typed in fees) {
                assertEquals("raised: typed $typed, fee $fee", NativeBridge.feeQuoteRaised(true, typed, fee),
                    FeeQuote(fee, 208, requestedSats = typed).raised)
            }
            assertEquals("a default-fee quote is never raised (fee $fee)", NativeBridge.feeQuoteRaised(false, 0L, fee),
                FeeQuote(fee, 208).raised)
        }
    }
}
