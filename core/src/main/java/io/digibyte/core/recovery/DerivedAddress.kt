package io.digibyte.core.recovery

/**
 * A derived address paired with its true position in the profile's derivation:
 * [chain] 0 = external (receive), 1 = internal (change); [index] is the child
 * index within that chain. Carried explicitly from derivation through
 * [RecoveryScanService.ProfileResult] to the sweeper so a filtered-out empty
 * slot can never desync a UTXO from its signing key (bug #3).
 */
data class DerivedAddress(
    val address: String,
    val chain: Int,
    val index: Int,
)
