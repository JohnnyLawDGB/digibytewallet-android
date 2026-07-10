package io.digibyte.core.network

import okhttp3.CertificatePinner

/**
 * Single source of truth for `api.digiscope.me` certificate pins.
 *
 * WHY THIS EXISTS: the same pin was hand-copied into three separate clients
 * ([io.digibyte.core.reconcile.DgbNodeClient],
 * [io.digibyte.core.asset.network.DigiScopeAssetClient],
 * [io.digibyte.core.digiscope.DigiScopeClient]). When the cert rotated, a fix
 * updated only one of them — the other two kept pinning the dead leaf, which
 * silently killed the DigiAsset metadata parent-walk ("metadata offline") and,
 * earlier, "Scan for missing funds". Routing every client through this one
 * object makes a rotation a one-line change that can't be half-applied.
 *
 * ROTATION RULE: pin the Let's Encrypt intermediate (YE1) alongside the leaf so
 * a routine ~90-day leaf renewal does NOT break pinning. When the chain does
 * rotate, move the outgoing values into [RETIRED_PINS] and put the new ones in
 * [PINS]. `DigiScopePinsTest` enforces that the two sets never overlap.
 *
 * Extract current pins with:
 *   openssl s_client -connect api.digiscope.me:443 -servername api.digiscope.me \
 *     -showcerts </dev/null 2>/dev/null | \
 *     openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
 *     openssl dgst -sha256 -binary | openssl enc -base64
 *
 * Refreshed 2026-07-10 from the live chain.
 */
object DigiScopePins {
    const val HOST = "api.digiscope.me"

    /** Current live chain: leaf + LE intermediate (YE1). */
    val PINS = listOf(
        "sha256/mxkNfnacx0nKcMPntNt/8/iv7iEoVNyg0WkCOt2FdU0=", // leaf
        "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=", // LE intermediate (YE1)
    )

    /**
     * Pins that were live in the past and must never be re-added. A dead pin
     * left in [PINS] is exactly the failure mode that caused the outages above.
     */
    val RETIRED_PINS = listOf(
        "sha256/VDo86Ks/QFE3kVoOXkmNVWTovKKNMFQsBd4KGvoP8OU=", // retired 2026-07-10 (old leaf)
        "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=", // retired 2026-07-10 (old intermediate)
    )

    /** A ready-to-use [CertificatePinner] pinning [HOST] to [PINS]. */
    fun certificatePinner(): CertificatePinner =
        CertificatePinner.Builder()
            .apply { PINS.forEach { add(HOST, it) } }
            .build()
}
