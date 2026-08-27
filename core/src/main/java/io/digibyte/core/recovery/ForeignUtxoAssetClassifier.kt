package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry

/**
 * Answers, for each UTXO on a foreign seed, "does this outpoint carry a DigiAsset?"
 *
 * Feeds [SweepPartition], which owns what to do with the answer. This half owns only whether the
 * question can be answered at all, and it is built to say "I don't know" rather than guess —
 * because the caller treats "don't know" as "do not sweep", and the alternative to over-holding
 * is burning somebody's asset.
 *
 * ## How it decides
 *
 * The wallet's own asset detection works off transactions registered in `BRWallet`, which a
 * foreign seed's transactions are not. What it can do is fetch the raw transaction that created
 * each outpoint and ask whether that transaction carries a DigiAsset marker at all.
 *
 * That is deliberately coarser than "does *this output* receive units". If the parent transaction
 * is an asset transaction, every one of its outputs is held back, including what may be ordinary
 * DGB change. Over-holding costs a manual move later; under-holding destroys an asset. The
 * asymmetry is the entire reason this class exists, and `AssetTxQuantity` already states the
 * principle: keeping an output out of a plain-DGB spend is a fail-closed decision that must not
 * wait on the quantity being knowable.
 *
 * Both dependencies are injected so the rule is testable without a network or JNI.
 */
class ForeignUtxoAssetClassifier(
    /** Raw transaction bytes for a txid, or null when it could not be fetched. */
    private val fetchRawTx: suspend (txid: String) -> ByteArray?,
    /** Whether raw transaction bytes carry a DigiAsset marker. `NativeBridge::isAssetTransaction`
     *  in production; a lambda in tests. */
    private val isAssetTx: (ByteArray) -> Boolean,
) {

    /** What was learned about one outpoint. */
    data class Verdict(
        val classified: Boolean,
        val carriesAsset: Boolean,
    ) {
        companion object {
            val PLAIN = Verdict(classified = true, carriesAsset = false)
            val ASSET = Verdict(classified = true, carriesAsset = true)
            /** Could not be answered — the caller must hold the outpoint back. */
            val UNKNOWN = Verdict(classified = false, carriesAsset = false)
        }
    }

    /**
     * Classify every outpoint, fetching each parent transaction once however many of its outputs
     * appear.
     *
     * A fetch failure or a parser throw yields [Verdict.UNKNOWN] for that outpoint rather than
     * aborting the scan: one unreachable transaction must not strand a whole wallet's recovery,
     * and the unknown outpoints are reported to the user rather than silently dropped.
     */
    suspend fun classify(utxos: List<UtxoEntry>): Map<UtxoEntry, Verdict> {
        val byTxid = mutableMapOf<String, Verdict>()
        val out = mutableMapOf<UtxoEntry, Verdict>()

        for (utxo in utxos) {
            val verdict = byTxid.getOrPut(utxo.txid) {
                try {
                    val raw = fetchRawTx(utxo.txid)
                    when {
                        raw == null || raw.isEmpty() -> Verdict.UNKNOWN
                        isAssetTx(raw) -> Verdict.ASSET
                        else -> Verdict.PLAIN
                    }
                } catch (_: Throwable) {
                    // Includes a native parser throwing on a malformed transaction. Unknown, not
                    // safe: a transaction we cannot parse is exactly the one to be careful with.
                    Verdict.UNKNOWN
                }
            }
            out[utxo] = verdict
        }
        return out
    }
}
