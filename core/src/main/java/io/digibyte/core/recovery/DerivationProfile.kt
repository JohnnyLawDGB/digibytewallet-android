package io.digibyte.core.recovery

/**
 * Describes one derivation path + HMAC seed-key + address format combo the
 * Universal Restore flow probes during seed restore.
 *
 * Rationale for the built-in set: DigiByte has been supported by many wallets
 * over the years (BreadWallet, Coinomi, Trezor, Ledger, Atomic, etc.), each
 * with slightly different derivation conventions. A fresh seed entry only
 * truly covers the user's funds when we check every plausible combination —
 * otherwise funds on unfamiliar paths stay invisible even though the chain
 * still holds them.
 */
data class DerivationProfile(
    /** User-facing label. Shown in the scan results UI. */
    val label: String,
    /** Short free-form description for the UI. */
    val description: String,
    /** HMAC-SHA512 key used to derive the master seed from the BIP39 seed.
     *  Standard Bitcoin/BIP39 wallets use "Bitcoin seed"; DigiByte's
     *  BreadWallet fork used "DigiByte seed". */
    val hmacKey: String,
    /** Hardened prefix (indices without the BIP32_HARD bit). Library adds
     *  the hardening bit internally. Example: BIP44 DGB = [44, 20, 0]. */
    val prefixPath: IntArray,
    /** 0 = P2PKH (D...), 1 = P2WPKH bech32 (dgb1q...), 2 = P2SH-P2WPKH (S...). */
    val addressFormat: Int,
    /**
     * Other encodings of the SAME keys to probe as well.
     *
     * An address format is a way of writing a public key, not a different key. This wallet's own
     * Receive screen offers one BIP84 key as bech32 AND as legacy (D…), so a user who received on
     * the Legacy tab has coins a bech32-only scan cannot see — and recovery answers "no funds
     * found". Silently, about their money.
     *
     * NOT P2SH-P2WPKH (format 2), deliberately: the reconcile backend rejects DigiByte S…
     * addresses with `invalid address` / HTTP 400, and one bad address fails the WHOLE batch —
     * so probing it does not merely miss those coins, it blinds the entire profile. Revisit if
     * the backend learns to accept them.
     *
     * Set only where the wallet actually hands out more than one encoding; probing every format
     * everywhere would multiply the address set for paths that never had the option.
     */
    val extraAddressFormats: List<Int> = emptyList(),
    /** True when this profile matches the wallet's native derivation
     *  (BIP84 DGB). UI treats this specially — no sweep, just import. */
    val isNative: Boolean,
    /** Gap limits — how many external / internal addresses to probe.
     *  Larger limits catch sparse-usage wallets at the cost of backend work. */
    val gapExternal: Int = DEFAULT_GAP_EXTERNAL,
    val gapInternal: Int = DEFAULT_GAP_INTERNAL,
) {
    companion object {
        /** Generous defaults — a wallet that has been active for months may
         *  have address chains extended well past the initial BIP84 gap (20).
         *  Each receive extends external by +20; each send extends internal
         *  by +10. A user with 50 receives and 30 sends would need external=70
         *  and internal=40. 200/100 covers that with headroom. The reconcile
         *  backend handles 500 addresses per batch so this is still one call
         *  per profile. */
        private const val DEFAULT_GAP_EXTERNAL = 200
        private const val DEFAULT_GAP_INTERNAL = 100
        const val HMAC_STANDARD = "Bitcoin seed"
        const val HMAC_DIGIBYTE = "DigiByte seed"
        private const val P2PKH = 0
        private const val P2WPKH = 1
        private const val P2SH_P2WPKH = 2

        /** Add the hardened bit to each element. BIP32's hardened-offset
         *  constant is 0x80000000. */
        private fun hardened(vararg indices: Int): IntArray =
            IntArray(indices.size) { indices[it] or 0x80000000.toInt() }

        /** Built-in profile list. Ordered loosely by likelihood of hits in
         *  the DigiByte ecosystem. The current wallet's native profile is
         *  first so results align with natural UI ordering. */
        val BUILT_INS: List<DerivationProfile> = listOf(
            DerivationProfile(
                label = "BIP84 DGB (current wallet)",
                description = "m/84'/20'/0' — native bech32 addresses (dgb1q…)",
                hmacKey = HMAC_STANDARD,
                prefixPath = hardened(84, 20, 0),
                addressFormat = P2WPKH,
                // The Receive screen hands out this same key as Legacy (D…) too. Without this,
                // coins received on that tab are invisible to recovery.
                extraAddressFormats = listOf(P2PKH),
                isNative = true,
            ),
            DerivationProfile(
                label = "Legacy DigiByte mobile wallet",
                description = "m/0H with \"DigiByte seed\" HMAC — pre-BIP84 DGB BreadWallet",
                hmacKey = HMAC_DIGIBYTE,
                prefixPath = hardened(0),
                addressFormat = P2PKH,
                // Also sweep-eligible: still technically our chain but not the
                // "primary" path new wallets use
                isNative = false,
            ),
            DerivationProfile(
                label = "Legacy m/0H with standard HMAC",
                description = "m/0H with \"Bitcoin seed\" HMAC — early non-DGB BreadWallet forks",
                hmacKey = HMAC_STANDARD,
                prefixPath = hardened(0),
                addressFormat = P2PKH,
                isNative = false,
            ),
            DerivationProfile(
                label = "BIP44 DGB",
                description = "m/44'/20'/0' — Coinomi, Trezor, Ledger-compatible DGB",
                hmacKey = HMAC_STANDARD,
                prefixPath = hardened(44, 20, 0),
                addressFormat = P2PKH,
                isNative = false,
            ),
            DerivationProfile(
                label = "BIP44 wrong-coin accident",
                description = "m/44'/0'/0' — seeds typed into a BTC wallet then imported back",
                hmacKey = HMAC_STANDARD,
                prefixPath = hardened(44, 0, 0),
                addressFormat = P2PKH,
                isNative = false,
            ),
            DerivationProfile(
                label = "BIP49 DGB (P2SH-wrapped segwit)",
                description = "m/49'/20'/0' — wrapped segwit addresses (S…)",
                hmacKey = HMAC_STANDARD,
                prefixPath = hardened(49, 20, 0),
                addressFormat = P2SH_P2WPKH,
                isNative = false,
            ),
        )
    }

    // IntArray requires manual equals/hashCode for data class semantics to work
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivationProfile) return false
        return label == other.label &&
                hmacKey == other.hmacKey &&
                prefixPath.contentEquals(other.prefixPath) &&
                addressFormat == other.addressFormat &&
                extraAddressFormats == other.extraAddressFormats
    }

    override fun hashCode(): Int {
        var h = label.hashCode()
        h = 31 * h + hmacKey.hashCode()
        h = 31 * h + prefixPath.contentHashCode()
        h = 31 * h + addressFormat
        h = 31 * h + extraAddressFormats.hashCode()
        return h
    }

    /** Format the prefix as a human-readable path for logs / UI. */
    fun pathString(): String = buildString {
        append("m")
        for (p in prefixPath) {
            val hard = (p.toLong() and 0x80000000L) != 0L
            val idx = p and 0x7fffffff
            append('/')
            append(idx)
            if (hard) append('\'')
        }
    }
}
