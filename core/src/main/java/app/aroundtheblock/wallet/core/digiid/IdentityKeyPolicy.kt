package app.aroundtheblock.wallet.core.digiid

/** Which BIP32 identity a Digi-ID login signs with. */
enum class IdentityKeyKind {
    /** The historic shared identity, m/0'/0/0 — one address for every site. */
    LEGACY,

    /** Per-site SLIP-0013 identity, m/13'/A'/B'/C'/D' from the site's callback URI. */
    PER_SITE,
}

/**
 * Decides which identity key a Digi-ID login uses. Pure — no DB, no JNI — so the rule is
 * unit-testable.
 *
 * The rule is the owner's decision Q4 A (Mark, 2026-11-10): a site signs with the shared key only
 * if it is on a FIXED list, the same on every device; every other site gets its per-site identity.
 * The list is [DIGISCOPE_HOSTS]: the Hub account, quickLogin and Hub content signing are all bound
 * to the wallet's m/0'/0/0 address on api.digiscope.me. Nothing else decides: not this phone's
 * Digi-ID history (its `derivation` column records which identity signed, and that is all), and
 * not how the wallet came to be on this phone. So the same seed signs a site the same way on every
 * device, iOS included (`Policy/DigiIdIdentity.swift`, `DigiIdIdentityTests`).
 *
 * Through 4.1.4 two more rules applied, and both are gone: a site with a successful shared-key login
 * in this phone's history kept the shared key (grandfathering), and a restored wallet was offered
 * the choice of identity on a site it had no successful login to (4.1.2 row 16).
 */
object IdentityKeyPolicy {

    /**
     * The hosts that sign in with the shared identity — exactly these. No name is taken for
     * DigiScope because it ends in digiscope.me, and no other spelling of these two is on the list:
     * a name under either host, a trailing dot or a lookalike gets its own per-site identity.
     */
    val DIGISCOPE_HOSTS: Set<String> = setOf("digiscope.me", "api.digiscope.me")

    /**
     * Whether [domain] is on the list, compared character for character (byte for byte: the list
     * is ASCII), with no case folding here. [domain] is a request's
     * [app.aroundtheblock.wallet.core.model.DigiIdRequest.domain]: the callback's host as the one
     * parse reads it, which lower-cases its letters and accepts no other respelling
     * (`isPlainAuthority`). iOS reads its list against the same lower-cased host, byte for byte.
     */
    fun isDigiScopeDomain(domain: String): Boolean = domain in DIGISCOPE_HOSTS

    /** The identity a login to [domain] signs with: the shared key exactly for the list. */
    fun choose(domain: String): IdentityKeyKind =
        if (isDigiScopeDomain(domain)) IdentityKeyKind.LEGACY else IdentityKeyKind.PER_SITE
}
