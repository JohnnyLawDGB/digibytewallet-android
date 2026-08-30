package io.digibyte.core.digiid

/** Which BIP32 identity a Digi-ID login signs with. */
enum class IdentityKeyKind {
    /** The historic shared identity, m/0'/0/0 — one address for every site. */
    LEGACY,

    /** Per-site SLIP-0013 identity, m/13'/A'/B'/C'/D' from the site's callback URI. */
    PER_SITE,
}

/**
 * Decides which identity key a Digi-ID login uses. Pure — no DB, no JNI — so the
 * grandfathering rules are unit-testable.
 *
 * Rules (docs/specs/digiid-key-isolation.md):
 *  - DigiScope domains stay on LEGACY: the Hub account, quickLogin and Hub content
 *    signing are all bound to the wallet's m/0'/0/0 address on api.digiscope.me.
 *  - A domain with a prior SUCCESSFUL legacy login stays on LEGACY — switching keys
 *    would lock the user out of the account that site bound to the old address.
 *  - Every other domain gets an unlinkable PER_SITE identity.
 */
object IdentityKeyPolicy {

    fun isDigiScopeDomain(domain: String): Boolean =
        domain == "digiscope.me" ||
        domain == "api.digiscope.me" ||
        domain.endsWith(".digiscope.me")

    fun choose(domain: String, hasSuccessfulLegacyHistory: Boolean): IdentityKeyKind =
        if (isDigiScopeDomain(domain) || hasSuccessfulLegacyHistory) IdentityKeyKind.LEGACY
        else IdentityKeyKind.PER_SITE
}
