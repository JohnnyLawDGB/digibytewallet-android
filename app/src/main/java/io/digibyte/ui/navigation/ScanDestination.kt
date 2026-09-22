package io.digibyte.ui.navigation

import io.digibyte.core.model.DigiByteUri

/**
 * Turns a scanned `digibyte:` URI into the route the scanner navigates to.
 *
 * Pure, so the JVM can prove it — the scanner used to inline this and the inlined version
 * carried the address and dropped the amount: the parser read it, the send view model could
 * apply it, and the route between them had no slot for it. A user scanning the digiscope
 * tip-wallet deposit QR (address plus a dust-coded amount) got an empty amount field and,
 * when they typed the amount by hand, risked a different dust code. Reported 2026-09-07.
 *
 * @param encode the navigation-safe encoder (`Uri.encode` in production, identity in tests).
 */
object ScanDestination {
    fun forDigiByteUri(uri: DigiByteUri, returnTo: String, encode: (String) -> String): String {
        val address = encode(uri.address)
        val assetId = uri.assetId
        return when {
            // An asset transfer request names what to send, so it goes to that asset's send
            // screen rather than the DGB flow — which would otherwise silently drop the asset
            // and prompt for a coin payment.
            //
            // It names WHOLE UNITS of the asset (DigiByteUri.assetAmount), and they travel as that
            // integer, under a name that says so: the send screen writes its quantity field from
            // them at the asset's own divisibility. They are never handed on as text for a later
            // step to read at some scale. A request object without usable units names none.
            assetId != null -> {
                val units = uri.assetAmount?.takeIf { it > 0L }
                val route = "asset_send/${encode(assetId)}?address=$address"
                if (units != null) "$route&units=$units" else route
            }
            // A caller that asked for the scan gets the address back; a DGB amount means
            // nothing to an asset send screen.
            returnTo.isNotBlank() -> "$returnTo?address=$address"
            else -> {
                val amount = uri.amount?.takeIf { it > 0 }
                if (amount != null) "send?address=$address&amount=$amount" else "send?address=$address"
            }
        }
    }
}
