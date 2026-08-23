package io.digibyte.core.asset

/**
 * What the asset send screen should be showing.
 *
 * Extracted from the screen so the decision is a unit test rather than something only
 * observable by sending a real asset on a real device — which is how the bug below was
 * found, after it had shipped.
 *
 * Reported live 2026-08-23 on a send that SUCCEEDED: *"the progress screen is still
 * spinning… even when that spinning wheel stops, it doesn't land on a success screen, it
 * just looks like it wants to redo the same transaction again."*
 *
 * Two faults produced that, and they pull in opposite directions:
 *
 * 1. The screen rendered `asset?.let { form } ?: CircularProgressIndicator`, commented
 *    "Asset not loaded yet". But after sending an asset away the wallet no longer HOLDS
 *    it, so `asset` is null **permanently** — the spinner was not waiting for anything and
 *    never would be. "Loading" and "gone" were the same state, and the honest one was
 *    unreachable.
 * 2. On success the form stayed populated and re-armed underneath the result banner. On a
 *    screen that moves value that is not merely untidy: a populated form with a live Send
 *    button, shown immediately after a send, is how someone sends twice.
 *
 * The ordering below is the fix: **a completed send wins over asset state.** Whether the
 * wallet still holds the asset is irrelevant once the transaction is away — and it usually
 * does not, precisely because the send worked.
 */
enum class AssetSendScreenState {
    /** Genuinely still loading the asset. The only state where a spinner is honest. */
    LOADING,

    /** Holding the asset, nothing sent yet — show the editable form. */
    FORM,

    /** A send completed. Show a terminal result and a way out; never the form, never a
     *  spinner, and never an armed Send button. */
    DONE,
    ;

    companion object {
        fun of(sendSucceeded: Boolean, assetLoaded: Boolean): AssetSendScreenState = when {
            // Checked FIRST, deliberately: after a successful send the asset is normally
            // gone, and asking about it first is what produced the permanent spinner.
            sendSucceeded -> DONE
            assetLoaded -> FORM
            else -> LOADING
        }
    }
}
