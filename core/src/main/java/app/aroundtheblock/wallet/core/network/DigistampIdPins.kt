package app.aroundtheblock.wallet.core.network

/**
 * Certificate pins for `id.digistamp.co`, DigiStamp's Digi-ID sign-in host (MARKET-SIGNIN-ID-HOST).
 *
 * The Market's view shows this host's sign-in pages, and the wallet posts the signed Digi-ID answer
 * to it, so it is pinned like [DigistampPins.HOST] and under the same rules: the pins for a host
 * live in exactly one place, and `network_security_config.xml` repeats this list for the WebView
 * (PlatformPinSetParityTest holds the two equal).
 *
 * NOT the same chain as `assets.digistamp.co`. Both names resolve to one address, but each has its
 * own certificate on its own key, renewed on its own date, and on 2026-11-21 they were issued by
 * different Let's Encrypt intermediates: this host by YE2, the marketplace by YE1. So YE2 is a live
 * pin here while it is a retired pin there; a retired list is per host.
 *
 * ROTATION RULE (the same as [DigistampPins]): pin the leaf AND the Let's Encrypt intermediate, so a
 * routine ~90-day leaf renewal does not break pinning, plus the ISRG keys above them (Root YE, ISRG
 * Root X2 and X1) as backups, so a renewal on a new key AND a change of intermediate still leaves a
 * pin that matches. The backups trust only certificates Let's Encrypt issues for this host; a failed
 * pin still refuses the connection. When the chain rotates, move the outgoing values into
 * [RETIRED_PINS] and put the new ones in [PINS]. `scripts/check-pins-live.sh` (scheduled in CI) fails
 * when a pinned leaf or intermediate is no longer served.
 *
 * NOTE this host is operated by a third party, so a chain change can arrive without warning.
 *
 * Extract current pins with (one value per served certificate; the script above does the same):
 *   openssl s_client -connect id.digistamp.co:443 -servername id.digistamp.co \
 *     -showcerts </dev/null 2>/dev/null | \
 *     openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
 *     openssl dgst -sha256 -binary | openssl enc -base64
 *
 * Captured 2026-11-21 from the live chain (leaf CN=id.digistamp.co, issued by YE2, valid to
 * 2027-02-04), read twice by separate means that agreed: `openssl s_client -showcerts` for every
 * served certificate, and a second TLS client for the leaf. The YE2, Root YE and Root X2 values
 * equal the ones [DigistampPins] recorded from the marketplace's chain on earlier dates. ISRG Root
 * X1 is above the served chain and is the value [DigistampPins] carries.
 */
object DigistampIdPins {
    const val HOST = "id.digistamp.co"

    /** Current live chain (leaf + Let's Encrypt intermediate YE2) and the ISRG backups above it. */
    val PINS = listOf(
        "sha256/4dpnWZ66VWKWEIqNBMK+Q1c+aROvD560F9yvVxr6+H4=", // leaf, CN=id.digistamp.co
        "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=", // intermediate, Let's Encrypt YE2
        "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // backup, ISRG Root YE
        "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // backup, ISRG Root X2
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // backup, ISRG Root X1
    )

    /** Pins that were live for THIS host in the past and must never be re-added for it. None yet. */
    val RETIRED_PINS = emptyList<String>()
}
