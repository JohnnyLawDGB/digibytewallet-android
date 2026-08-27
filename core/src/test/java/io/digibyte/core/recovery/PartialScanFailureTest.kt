package io.digibyte.core.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scan that could not check every derivation path must never report "no funds found".
 *
 * ## The bug
 *
 * [RecoveryScanService.State.Done.allBackendUnreachable] uses `all`, so it is true only when
 * EVERY profile failed. When some profiles reconcile and others do not — one oversized request
 * rejected, a timeout on one path — the flag stays false, the findings list is empty, and the
 * screen says **"No recoverable funds found."**
 *
 * That is a confident answer to a question the wallet did not finish asking. It is the same
 * failure shape as the address-format gap: not wrong data, but silence presented as certainty,
 * about someone's money. A user who sees "no funds" stops looking.
 *
 * Observed for real: a change that pushed one profile's request over the server's limit produced
 * an HTTP 400 for that profile alone. The other five reconciled, found nothing legitimately, and
 * the wallet reported no funds — while the backend, asked directly, held half a billion sats on
 * the very path that had failed.
 *
 * ## The rule
 *
 * "Some paths could not be checked" is its own outcome, distinct from both "no funds" and
 * "everything is down", and the user has to be told which one they got.
 */
class PartialScanFailureTest {

    private fun profile(label: String) = DerivationProfile(
        label = label,
        description = label,
        hmacKey = DerivationProfile.HMAC_STANDARD,
        prefixPath = intArrayOf(84, 20, 0),
        addressFormat = 1,
        isNative = false,
    )

    private fun result(label: String, reachable: Boolean, sats: Long = 0L) =
        RecoveryScanService.ProfileResult(
            profile = profile(label),
            addresses = listOf("D7Vx$label"),
            derivedAddresses = emptyList(),
            utxos = emptyList(),
            rawTxs = emptyMap(),
            reachableBackend = reachable,
        )

    // ---- the case that hid a real bug -------------------------------------------------------

    @Test fun `one failed profile among several is a partial failure`() {
        val done = RecoveryScanService.State.Done(
            listOf(
                result("BIP84", reachable = false),   // the one that mattered
                result("BIP44", reachable = true),
                result("legacy", reachable = true),
            )
        )

        assertFalse("not everything was down", done.allBackendUnreachable)
        assertTrue("but the scan is incomplete and must say so", done.anyBackendUnreachable)
    }

    @Test fun `a clean empty scan is not a partial failure`() {
        val done = RecoveryScanService.State.Done(
            listOf(result("BIP84", reachable = true), result("BIP44", reachable = true))
        )

        assertFalse(done.allBackendUnreachable)
        assertFalse("nothing failed — 'no funds' is an honest answer here", done.anyBackendUnreachable)
    }

    @Test fun `a total outage is both`() {
        val done = RecoveryScanService.State.Done(
            listOf(result("BIP84", reachable = false), result("BIP44", reachable = false))
        )

        assertTrue(done.allBackendUnreachable)
        assertTrue(done.anyBackendUnreachable)
    }

    /** Profiles with no addresses were never asked, so they cannot have failed. */
    @Test fun `a profile with no addresses does not count as unreachable`() {
        val empty = RecoveryScanService.ProfileResult(
            profile = profile("empty"),
            addresses = emptyList(),
            derivedAddresses = emptyList(),
            utxos = emptyList(),
            rawTxs = emptyMap(),
            reachableBackend = false,
        )
        val done = RecoveryScanService.State.Done(listOf(empty, result("BIP44", reachable = true)))

        assertFalse(done.anyBackendUnreachable)
        assertFalse(done.allBackendUnreachable)
    }

    /**
     * Finding funds does NOT make an incomplete scan complete. A user shown a balance from two
     * paths, with a third silently unchecked, will reasonably believe that is everything.
     */
    @Test fun `partial failure still matters when some funds were found`() {
        val done = RecoveryScanService.State.Done(
            listOf(result("BIP84", reachable = false), result("BIP44", reachable = true))
        )
        assertTrue(done.anyBackendUnreachable)
    }
}
