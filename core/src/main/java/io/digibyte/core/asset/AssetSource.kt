package io.digibyte.core.asset

/** Provenance of an asset UTXO row. NATIVE rows were detected by the sovereign
 *  sweep (native knew the tx at insert) and are prunable on native tx-removal;
 *  BACKEND rows were surfaced by the on-demand reconcile and are never auto-pruned. */
object AssetSource {
    const val NATIVE = "NATIVE"
    const val BACKEND = "BACKEND"
}
