package io.digibyte.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds Kotlin's [PublishOutcome] to the C mapping in `BRPublishOutcome.h`, and pins its
 * hardcoded errno constants to the platform's real values.
 *
 * ## The bug this exists to prevent — and what it is NOT
 *
 * [PublishOutcome] hardcodes `ENOTCONN = 107` and `ETIMEDOUT = 110`. Those are **Linux**
 * values, and Android is Linux, so **there is no live Android bug here**. This test passes
 * today and is expected to.
 *
 * The hazard is the iOS port. Darwin's ENOTCONN is 57 and ETIMEDOUT is 60. A Swift port
 * that copied these literals would match no case for a timeout, fall through to the default,
 * and make `UNCONFIRMED_DELIVERY` unreachable — silently destroying the one signal the whole
 * policy exists to produce. `PublishOutcome`'s own doc explains why that matters: peers do
 * not announce rejections, so **silence is the only evidence a transaction was refused**.
 * That is the asset transfer which reported six relays and existed in no mempool.
 *
 * The fix was not a second table of Darwin numbers. `BRPublishOutcome.h` switches on
 * `<errno.h>` SYMBOLS, so it is correct wherever the core compiles. Swift imports it and
 * inherits that for free.
 *
 * [kotlinConstantsMatchThePlatform] is therefore the most valuable test in this file: it
 * converts "those literals happen to be right on Android" from a comment into an assertion,
 * so nobody tidies, reuses, or copies them without something failing.
 */
@RunWith(AndroidJUnit4::class)
class PublishOutcomeParityTest {

    private companion object {
        const val KIND_MASK = 0x03
        const val BIT_SHOULD_RETRY = 0x04
        const val BIT_IS_TERMINAL = 0x08

        const val IDX_EINVAL = 0
        const val IDX_ENOTCONN = 1
        const val IDX_ETIMEDOUT = 2

        /** C `BRPublishKind` ordinal -> the Kotlin [PublishOutcome.Kind] it must equal. */
        val KIND_BY_ORDINAL = mapOf(
            0 to PublishOutcome.Kind.ACCEPTED,
            1 to PublishOutcome.Kind.REJECTED,
            2 to PublishOutcome.Kind.NOT_DELIVERED,
            3 to PublishOutcome.Kind.UNCONFIRMED_DELIVERY,
        )
    }

    private fun cOutcome(error: Int): PublishOutcome {
        val packed = NativeBridge.publishOutcomeOf(error)
        val kind = KIND_BY_ORDINAL[packed and KIND_MASK]
            ?: error("C returned an unknown kind ordinal ${packed and KIND_MASK}")
        return PublishOutcome(
            kind = kind,
            shouldRetry = (packed and BIT_SHOULD_RETRY) != 0,
            isTerminal = (packed and BIT_IS_TERMINAL) != 0,
        )
    }

    /**
     * THE important one. Kotlin's literals must equal what the platform actually defines.
     *
     * Passes on Android because Android is Linux. Its job is to fail loudly if someone
     * changes these constants, or reuses this file's values as though they were universal.
     */
    @Test
    fun kotlinConstantsMatchThePlatform() {
        assertEquals(
            "PublishOutcome.EINVAL drifted from the platform's EINVAL",
            NativeBridge.publishErrnoValue(IDX_EINVAL),
            PublishOutcome.EINVAL,
        )
        assertEquals(
            "PublishOutcome.ENOTCONN drifted from the platform's ENOTCONN. These are LINUX " +
                "values (107) and correct on Android; Darwin is 57. Do not copy them to iOS — " +
                "BRPublishOutcome.h switches on <errno.h> symbols for exactly this reason.",
            NativeBridge.publishErrnoValue(IDX_ENOTCONN),
            PublishOutcome.ENOTCONN,
        )
        assertEquals(
            "PublishOutcome.ETIMEDOUT drifted from the platform's ETIMEDOUT. Linux 110, " +
                "Darwin 60. A hardcoded copy makes UNCONFIRMED_DELIVERY unreachable on iOS.",
            NativeBridge.publishErrnoValue(IDX_ETIMEDOUT),
            PublishOutcome.ETIMEDOUT,
        )
    }

    /** Out-of-range index must be total, not undefined. */
    @Test
    fun errnoAccessorIsTotal() {
        listOf(3, 99, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach {
            assertEquals("index $it should report 0", 0, NativeBridge.publishErrnoValue(it))
        }
    }

    /** Identical decisions for every code the mapping names. */
    @Test
    fun outcomesAgreeForEveryKnownCode() {
        listOf(0, PublishOutcome.EINVAL, PublishOutcome.ENOTCONN, PublishOutcome.ETIMEDOUT)
            .forEach { code ->
                assertEquals("kind disagrees for errno $code", PublishOutcome.of(code).kind, cOutcome(code).kind)
                assertEquals(
                    "shouldRetry disagrees for errno $code",
                    PublishOutcome.of(code).shouldRetry,
                    cOutcome(code).shouldRetry,
                )
                assertEquals(
                    "isTerminal disagrees for errno $code",
                    PublishOutcome.of(code).isTerminal,
                    cOutcome(code).isTerminal,
                )
            }
    }

    /** And for codes neither side names — the fail-safe default must match too. */
    @Test
    fun outcomesAgreeForUnrecognisedCodes() {
        listOf(1, 5, 42, 57, 60, 999, -1, 12345).forEach { code ->
            assertEquals(
                "kind disagrees for unrecognised errno $code",
                PublishOutcome.of(code).kind,
                cOutcome(code).kind,
            )
        }
    }

    /**
     * The asymmetry, asserted against C independently of Kotlin.
     *
     * Wrongly retrying costs a little radio; wrongly destroying a send loses a transaction
     * that was still propagating. Only a core-declared malformed transaction may be terminal.
     */
    @Test
    fun onlyRejectedIsEverTerminalInC() {
        val codes = listOf(0, 1, 5, 42, 57, 60, 107, 110, 999, -1, 12345,
                           PublishOutcome.EINVAL, PublishOutcome.ENOTCONN, PublishOutcome.ETIMEDOUT)
        codes.forEach { code ->
            val c = cOutcome(code)
            if (c.isTerminal) {
                assertEquals(
                    "errno $code was terminal in C but is not REJECTED — nothing else may " +
                        "release a send's inputs",
                    PublishOutcome.Kind.REJECTED,
                    c.kind,
                )
            }
            if (c.kind != PublishOutcome.Kind.ACCEPTED && c.kind != PublishOutcome.Kind.REJECTED) {
                assertTrue("errno $code should stay retryable", c.shouldRetry)
            }
        }
    }

    /** Only ACCEPTED may be shown to the user as sent. */
    @Test
    fun onlyAcceptedIsUserVisiblySent() {
        assertTrue(cOutcome(0).userVisiblySent)
        listOf(PublishOutcome.EINVAL, PublishOutcome.ENOTCONN, PublishOutcome.ETIMEDOUT, 999)
            .forEach { assertFalse("errno $it must not read as sent", cOutcome(it).userVisiblySent) }
    }
}
