package io.digibyte.core.recovery

import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.WalletTxPersister
import io.digibyte.core.asset.send.DA_MARKER_SATS
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.dandelion.Broadcaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves the DigiAssets a sweep deliberately left behind into the user's current wallet.
 *
 * ## Where this sits
 *
 * [LegacySweepService] takes the plain DGB and holds back two things: outpoints carrying assets
 * (spending one as ordinary DGB destroys the asset) and enough DGB to move them later
 * ([AssetFeeReserve]). This is the later. It is a SEPARATE, user-initiated step rather than a
 * tail of the sweep — an irreversible asset move deserves its own confirmation, and it must
 * remain possible to run it after a sweep that has already broadcast.
 *
 * ## Ordering
 *
 * Safe to run before or after the sweep. The fee pool is whatever [AssetFeeReserve] reserved,
 * which is exactly the set the sweep left unspent, so the same call is correct either way.
 *
 * ## The native touchpoints are injected
 *
 * Parsing, signing and broadcasting are lambdas so the orchestration — which asset got which
 * money, what happens when one of several fails — is testable on a JVM. The defaults are the
 * real thing.
 */
class ForeignAssetTransferService(
    private val assetClassifier: ForeignUtxoAssetClassifier,
    private val outgoingTxStore: OutgoingTxStore? = null,
    private val walletTxPersister: WalletTxPersister? = null,
    /** Raw transaction bytes to its outputs. Native, because these bytes are remote-supplied. */
    private val parseOutputs: (ByteArray) -> List<ForeignAssetQuantity.Output>? = ::nativeParseOutputs,
    /** Sign a planned transfer with the foreign seed. Returns signed hex, or null on refusal. */
    private val sign: (ForeignAssetTransferPlan.Plan, ByteArray, DerivationProfile, Long) -> String? =
        ::nativeSign,
    /** Broadcast signed bytes. Returns the relay txid, or null. */
    private val broadcast: (ByteArray) -> String? = { Broadcaster.broadcast(it) },
    /**
     * Record the outgoing transaction for durability and activity-list rendering.
     *
     * Injected so `isSelfTransfer` is assertable. It is not a detail: the move sends to an
     * address THIS wallet owns, so the C core categorizes it as a receive. Recording it as an
     * ordinary send makes the activity list override that and show the user "Sent" for a
     * transaction that increased their balance — see OutgoingTxStore.shouldApplyOutgoingOverride.
     */
    /**
     * Where progress and refusals go.
     *
     * Injected rather than calling android.util.Log directly so the orchestration stays testable
     * on a JVM, where android.util.Log is an unmocked stub that throws. Flipping
     * returnDefaultValues for the whole module would fix the symptom by making every other
     * Android stub silently return null across 800-odd tests.
     */
    private val log: (level: Char, message: String) -> Unit = { level, message ->
        if (level == 'w') android.util.Log.w(TAG, message) else android.util.Log.i(TAG, message)
    },
    private val recordOutgoing: (
        txid: String, sentSats: Long, feeSats: Long, toAddress: String, isSelfTransfer: Boolean,
    ) -> Unit = { txid, sent, fee, to, self ->
        outgoingTxStore?.record(
            txid = txid, sentSats = sent, feeSats = fee, toAddress = to, isSelfTransfer = self,
        )
        walletTxPersister?.persist()
    },
) {

    /** What became of one asset. [txid] is non-null only once the transfer reached relay. */
    data class Move(
        val outpoint: String,
        val units: Long,
        val txid: String?,
        val failureReason: String?,
        /** Every outpoint this move's plan spends. Empty when no plan was built. The sweep is
         *  the complement of these — see [RecoverySequence.sweepExclusions]. */
        val spentInputs: List<String> = emptyList(),
    ) {
        val moved: Boolean get() = txid != null
    }

    data class Result(val moves: List<Move>) {
        val movedCount: Int get() = moves.count { it.moved }
        /** True only when every asset found actually reached relay. Deliberately not "no
         *  failures": an empty batch has no failures and moved nothing. */
        val allMoved: Boolean get() = moves.isNotEmpty() && moves.all { it.moved }
    }

    /**
     * @param seedBytes   the FOREIGN seed. Caller owns it and must zero it.
     * @param results     the scanned profiles, as handed to [LegacySweepService].
     * @param destAddress the current wallet's receive address. Every output goes here.
     */
    suspend fun moveAssets(
        seedBytes: ByteArray,
        results: List<RecoveryScanService.ProfileResult>,
        destAddress: String,
        feePerKb: Long = 100_000L,
        /** Reuses the sweep's classification. Each pass fetches every parent transaction, so
         *  classifying twice for one recovery doubles the network work for the same answer. */
        precomputedVerdicts: Map<io.digibyte.core.reconcile.UtxoEntry, ForeignUtxoAssetClassifier.Verdict>? = null,
    ): Result = withContext(Dispatchers.IO) {
        val verdicts = precomputedVerdicts
            ?: assetClassifier.classify(results.flatMap { it.utxos })
        val moves = mutableListOf<Move>()

        for (result in results) {
            // Same partition the sweep used, so the two agree on what was held back and why.
            val partition = SweepPartition.split(
                utxos = result.utxos,
                carriesAsset = { verdicts[it]?.carriesAsset ?: false },
                classified = { verdicts[it]?.classified ?: false },
            )
            if (partition.assetBearing.isEmpty()) continue

            log('i', "profile=${result.profile.label}: ${partition.assetBearing.size} asset-bearing " +
                    "outpoint(s) to move, ${partition.sweepable.size} plain",
            )

            val byAddress = result.derivedAddresses.associateBy { it.address }

            // The coarse classifier holds back EVERY output of an asset transaction, including
            // ordinary DGB change. Those read as zero units and are refused rather than moved —
            // and must never be promoted into the plain sweep on the strength of that zero,
            // because an under-read there destroys an asset. See ForeignAssetQuantity.
            val assets = mutableListOf<ForeignAssetTransferBatch.AssetItem>()
            for (utxo in partition.assetBearing) {
                val spend = toSpend(utxo, byAddress)
                if (spend == null) {
                    // No derivation position, or no scriptPubKey — we cannot sign for it. Report
                    // it rather than drop it: an asset missing from this list reads to the user
                    // as one that moved.
                    moves += Move(
                        outpoint = "${utxo.txid}:${utxo.vout}",
                        units = 0L,
                        txid = null,
                        failureReason = "no signing key for ${utxo.address} — " +
                            "its derivation position or scriptPubKey is missing",
                    )
                    continue
                }
                assets += ForeignAssetTransferBatch.AssetItem(spend, unitsOn(result, utxo))
            }
            // A fee UTXO we cannot sign for is simply not usable as fee money; it is reported by
            // the sweep, not here, and dropping it only narrows the pool.
            // Every plain-DGB outpoint, not a reserved subset. The sweep has not run yet, so
            // all of it is still available — and what these plans spend is what the sweep will
            // exclude. Nothing is estimated.
            val feePool = partition.sweepable.mapNotNull { toSpend(it, byAddress) }

            log('i', "profile=${result.profile.label}: fee pool ${feePool.size} outpoint(s) / " +
                    "${feePool.sumOf { it.amountSat }} sats; units=" +
                    assets.joinToString { "${it.spend.txid.take(8)}:${it.spend.vout}=${it.units}" },
            )

            val planned = ForeignAssetTransferBatch.plan(assets, feePool, destAddress, feePerKb)

            for (item in planned) {
                when (val r = item.result) {
                    is ForeignAssetTransferPlan.Result.Refused -> {
                        log('w', "${item.outpoint}: refused — ${r.reason}: ${r.detail}")
                        // No plan, so no inputs to protect beyond the asset outpoint itself.
                        moves += Move(item.outpoint, 0L, null, "${r.reason}: ${r.detail}")
                    }

                    is ForeignAssetTransferPlan.Result.Ok -> {
                        log('i', "${item.outpoint}: planned ${r.plan.assetUnits} unit(s), " +
                                "${r.plan.inputs.size} input(s), fee ${r.plan.feeSat} sats, " +
                                "change ${r.plan.outputs.last().amountSat} sats -> $destAddress",
                        )
                        val signed = sign(r.plan, seedBytes, result.profile, feePerKb)
                        if (signed == null) {
                            log('w', "${item.outpoint}: native refused to sign")
                            moves += Move(item.outpoint, r.plan.assetUnits, null,
                                "native refused to sign — see log for the reason",
                                spentInputs = r.plan.outpoints())
                            continue
                        }
                        val bytes = runCatching { hexToBytes(signed) }.getOrNull()
                        if (bytes == null) {
                            moves += Move(item.outpoint, r.plan.assetUnits, null,
                                "signed hex malformed", spentInputs = r.plan.outpoints())
                            continue
                        }
                        val txid = broadcast(bytes)
                        if (txid == null) {
                            log('w', "${item.outpoint}: broadcast failed")
                            moves += Move(item.outpoint, r.plan.assetUnits, null,
                                "broadcast failed — check peer connection",
                                spentInputs = r.plan.outpoints())
                            continue
                        }
                        // Same durability path the sweep and the wallet's own asset send use, so
                        // a force-stop within a second of broadcast does not strand the transfer.
                        // Best-effort: never affects on-chain state.
                        runCatching {
                            recordOutgoing(
                                txid,
                                DA_MARKER_SATS,
                                r.plan.feeSat,
                                destAddress,
                                // The destination is OUR receive address. Without this the
                                // activity list renders a balance-increasing asset move as
                                // "Sent" — observed on mainnet, 2026-08-28.
                                true,
                            )
                        }
                        log('i', "${item.outpoint}: MOVED in $txid")
                        moves += Move(item.outpoint, r.plan.assetUnits, txid, null,
                            spentInputs = r.plan.outpoints())
                    }
                }
            }
        }

        Result(moves)
    }

    private fun ForeignAssetTransferPlan.Plan.outpoints(): List<String> =
        inputs.map { "${it.txid}:${it.vout}" }

    /** Units on this outpoint, read from the parent transaction the scan already fetched. */
    private fun unitsOn(
        result: RecoveryScanService.ProfileResult,
        utxo: io.digibyte.core.reconcile.UtxoEntry,
    ): Long {
        val raw = result.rawTxs[utxo.txid]?.hex ?: return 0L
        val bytes = runCatching { hexToBytes(raw) }.getOrNull() ?: return 0L
        val outputs = runCatching { parseOutputs(bytes) }.getOrNull() ?: return 0L
        return ForeignAssetQuantity.unitsOn(outputs, utxo.vout)
    }

    /**
     * A UTXO paired with the derivation position its key lives at. Read from [DerivedAddress],
     * never reconstructed from a list position — a dropped empty slot once made that reconstruction
     * sign with the wrong child key.
     */
    private fun toSpend(
        utxo: io.digibyte.core.reconcile.UtxoEntry,
        byAddress: Map<String, DerivedAddress>,
    ): ForeignAssetTransferPlan.Spend? {
        val derived = byAddress[utxo.address] ?: return null
        val script = utxo.scriptPubKeyHex ?: return null
        return ForeignAssetTransferPlan.Spend(
            txid = utxo.txid,
            vout = utxo.vout,
            amountSat = utxo.amountSatoshi,
            scriptPubKeyHex = script,
            chain = derived.chain,
            index = derived.index,
        )
    }

    companion object {
        /** Matches LegacySweepService's tag convention so a whole recovery reads as one story. */
        private const val TAG = "ForeignAssetMove"

        private fun nativeParseOutputs(rawTx: ByteArray): List<ForeignAssetQuantity.Output>? =
            NativeBridge.getRawTransactionOutputs(rawTx)?.mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val vout = parts[0].toIntOrNull() ?: return@mapNotNull null
                val sats = parts[1].toLongOrNull() ?: return@mapNotNull null
                val script = runCatching { hexToBytes(parts[2]) }.getOrNull() ?: return@mapNotNull null
                ForeignAssetQuantity.Output(vout, sats, script)
            }

        private fun nativeSign(
            plan: ForeignAssetTransferPlan.Plan,
            seedBytes: ByteArray,
            profile: DerivationProfile,
            feePerKb: Long,
        ): String? = NativeBridge.buildAndSignForeignAssetTransfer(
            seedBytes = seedBytes,
            hmacKey = profile.hmacKey,
            prefixPath = profile.prefixPath,
            txidsHex = plan.inputs.map { it.txid }.toTypedArray(),
            vouts = plan.inputs.map { it.vout }.toIntArray(),
            amounts = plan.inputs.map { it.amountSat }.toLongArray(),
            chainIndices = plan.inputs.map { it.chain }.toIntArray(),
            addressIndices = plan.inputs.map { it.index }.toIntArray(),
            scriptPubKeysHex = plan.inputs.map { it.scriptPubKeyHex }.toTypedArray(),
            outputAddresses = plan.outputs.map { it.address }.toTypedArray(),
            outputAmounts = plan.outputs.map { it.amountSat }.toLongArray(),
            outputScriptsHex = plan.outputs.map { it.scriptHex }.toTypedArray(),
            feePerKb = feePerKb,
        )

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "odd-length hex" }
            return ByteArray(hex.length / 2) {
                ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
            }
        }
    }
}
