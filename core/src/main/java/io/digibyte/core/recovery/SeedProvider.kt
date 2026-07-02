package io.digibyte.core.recovery

/**
 * Supplies the wallet's **64-byte BIP39 seed** as a zeroable [ByteArray], or
 * `null` if no wallet seed is available (e.g. no wallet on disk, or decrypt
 * failed).
 *
 * CRITICAL-3 contract: the returned array is owned by the caller, who MUST
 * `fill(0)` it in a `finally` once it has been consumed. Implementations are
 * responsible for converting the on-disk secret (a BIP39 *mnemonic* in this
 * wallet) into the 64-byte seed and for zeroing any intermediate buffers they
 * allocate (e.g. the decrypted mnemonic bytes).
 *
 * This is the seam the recovery flow uses instead of reaching into the
 * encrypted seed store directly. It is implemented over the existing
 * seed-loading code (see `WalletManager.loadSeedDerivedBip39Seed`).
 */
fun interface SeedProvider {
    /** @return the 64-byte BIP39 seed, or null if unavailable. Caller zeros it. */
    fun loadSeed(): ByteArray?
}
