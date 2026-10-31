package app.aroundtheblock.wallet.core.network

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
 * renewal does not break pinning, plus the ISRG keys above them (Root YE, ISRG Root X2 and X1) as
 * backups, so a renewal on a new key AND a change of intermediate still leaves a pin that matches.
 * The backups trust only certificates Let's Encrypt issues for this host; a failed pin still
 * refuses the connection. When the chain rotates, move the outgoing values into [RETIRED_PINS]
 * and put the new ones in [PINS]. `scripts/check-pins-live.sh` (scheduled in CI) fails when a
 * pinned leaf or intermediate is no longer served.
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
 * Captured 2026-10-29 from the live chain (the leaf was renewed on a new key on 2026-10-09; the
 * 2026-08-23 leaf pin is retired). Leaf valid to 2027-01-07.
 */
object DigistampPins {
    const val HOST = "assets.digistamp.co"

    /** Current live chain (leaf + Let's Encrypt intermediate YE2) and the ISRG backups above it. */
    val PINS = listOf(
        "sha256/4QRqLjH49bkWbXdyanK3jENaFwUIqL2Lr+bXn1Y9Bcw=", // leaf, CN=assets.digistamp.co
        "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=", // intermediate, Let's Encrypt YE2
        "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // backup, ISRG Root YE
        "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // backup, ISRG Root X2
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // backup, ISRG Root X1
    )

    /** Pins that were live in the past and must never be re-added. */
    val RETIRED_PINS = listOf(
        "sha256/APmRUNe9kpbMkhWTnrdA/3y7EIwNGI9MmDKb/DId08I=", // leaf until the 2026-10-09 renewal
    )

    /**
     * The pinner for a client made to reach this host: these pins AND the DigiScope pins
     * ([DigiScopePins]). A pinner set on a client derived from the shared one replaces the shared
     * pinner, so one holding only these pins would let that client reach the DigiScope host — by a
     * redirect, say — with no pin check. OkHttp checks a pinner only for the hosts it holds pins
     * for, so the DigiScope pins change nothing for this host.
     */
    fun certificatePinner(): CertificatePinner =
        CertificatePinner.Builder()
            .apply { PINS.forEach { add(HOST, it) } }
            .apply { DigiScopePins.PINS.forEach { add(DigiScopePins.HOST, it) } }
            .build()
}
