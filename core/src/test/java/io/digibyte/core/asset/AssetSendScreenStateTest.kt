package io.digibyte.core.asset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the asset send screen should show once a send has been made.
 *
 * Reported live 2026-08-23, on a send that SUCCEEDED: "the progress screen is still
 * spinning… even when that spinning wheel stops, it doesn't land on a success screen, it
 * just looks like it wants to redo the same transaction again."
 *
 * Two distinct faults produced that:
 *
 *  1. The screen renders `asset?.let { form } ?: CircularProgressIndicator`, commented
 *     "Asset not loaded yet". After sending an asset away the wallet no longer HOLDS it, so
 *     `asset` is null forever — the spinner is not waiting for anything. "Loading" and
 *     "gone" were the same state.
 *  2. On success the form stayed populated and re-armed beneath the banner, which reads as
 *     an invitation to send again. On a payment screen that is not merely confusing: it is
 *     how someone sends twice.
 */
class AssetSendScreenStateTest {

    @Test fun a_successful_send_shows_a_terminal_result_not_the_form() {
        assertEquals(
            AssetSendScreenState.DONE,
            AssetSendScreenState.of(sendSucceeded = true, assetLoaded = true),
        )
    }

    /** The reported case: the asset is gone BECAUSE the send worked. Still terminal — never
     *  a spinner, which would claim the wallet is still working on something. */
    @Test fun a_successful_send_is_terminal_even_when_the_asset_is_gone() {
        assertEquals(
            AssetSendScreenState.DONE,
            AssetSendScreenState.of(sendSucceeded = true, assetLoaded = false),
        )
    }

    @Test fun a_held_asset_with_no_send_yet_shows_the_form() {
        assertEquals(
            AssetSendScreenState.FORM,
            AssetSendScreenState.of(sendSucceeded = false, assetLoaded = true),
        )
    }

    /** Genuinely still loading — the only case a spinner is honest. */
    @Test fun no_asset_and_no_send_is_the_only_spinner_case() {
        assertEquals(
            AssetSendScreenState.LOADING,
            AssetSendScreenState.of(sendSucceeded = false, assetLoaded = false),
        )
    }
}
