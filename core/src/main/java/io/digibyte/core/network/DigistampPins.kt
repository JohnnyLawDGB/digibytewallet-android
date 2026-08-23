package io.digibyte.core.network

import okhttp3.CertificatePinner

/**
 * Certificate pins for `assets.digistamp.co`.
 *
 * Separate from [DigiScopePins] because they are different hosts on different infrastructure,
 * but held to the same discipline for the same reason: the pin for a host lives in exactly one
 * place. A pin hand-copied into several clients and then updated in only one is what silently
 * killed DigiAsset metadata twice.
 *
 * ROTATION RULE: pin the leaf AND the Let's Encrypt intermediate, so a routine ~90-day leaf
 * renewal does not break pinning. When the chain rotates, move the outgoing values into
 * [RETIRED_PINS] and put the new ones in [PINS].
 *
 * NOTE this host is operated by a third party, so a chain change can arrive without warning.
 * That is a standing coordination dependency, not a one-time setup step.
 *
 * Extract current pins with:
 *   openssl s_client -connect assets.digistamp.co:443 -servername assets.digistamp.co \
 *     -showcerts </dev/null 2>/dev/null | \
 *     openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
 *     openssl dgst -sha256 -binary | openssl enc -base64
 *
 * Captured 2026-08-23 from the live chain.
 */
object DigistampPins {
    const val HOST = "assets.digistamp.co"

    /** Current live chain: leaf + Let's Encrypt intermediate (YE2). */
    val PINS = listOf(
        "sha256/APmRUNe9kpbMkhWTnrdA/3y7EIwNGI9MmDKb/DId08I=", // leaf, CN=assets.digistamp.co
        "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=", // Let's Encrypt intermediate YE2
    )

    /** Pins that were live in the past and must never be re-added. */
    val RETIRED_PINS = emptyList<String>()

    fun certificatePinner(): CertificatePinner =
        CertificatePinner.Builder()
            .apply { PINS.forEach { add(HOST, it) } }
            .build()
}
