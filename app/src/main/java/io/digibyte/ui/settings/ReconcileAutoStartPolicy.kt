package io.digibyte.ui.settings

/**
 * Should arriving on the reconcile screen START the scan by itself?
 *
 * WHY THIS EXISTS. The history-gap banner's button reads "Scan for missing transactions", but it
 * used only to `navigate("settings_reconcile")` — the scan itself lived behind a SECOND button on
 * the destination screen. Observed on a Note 8, 2026-08-07: a wallet that had written off
 * 1,342,744 blocks was showing 0.00 DGB with no history, the user tapped that button, and nothing
 * happened. Not a failure — a no-op. `DgbNodeClient` logs every failure mode (HTTP code, throw,
 * empty body) and was completely silent, which is how we know the client was never reached at all.
 *
 * That combination is the worst kind of bug: the wallet's only recovery affordance appeared to
 * work, did nothing, and left every visible signal saying "you tried and it failed". A control
 * must do what its label says.
 *
 * Kept as a pure function rather than inline in the composable so the decision is testable
 * without a UI harness — the same shape as [io.digibyte.ui.wallet.shouldWakePeers].
 *
 * @param requested        the caller asked for a scan (banner tap). Settings' own entry point
 *                         passes false: there the user has opened a screen, not asked for work.
 * @param alreadyStarted   this arrival already auto-started once. Guards against a recomposition
 *                         firing a second scan.
 * @param scanInProgress   a scan is already running (State.Scanning). Never stack a second one.
 */
internal fun shouldAutoStartReconcile(
    requested: Boolean,
    alreadyStarted: Boolean,
    scanInProgress: Boolean,
): Boolean = requested && !alreadyStarted && !scanInProgress
