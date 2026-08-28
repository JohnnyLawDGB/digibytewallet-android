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
) {

    /** What became of one asset. [txid] is non-null only once the transfer reached relay. */
    data class Move(
        val outpoint: String,
        val units: Long,
        val txid: String?,
        val failureReason: String?,
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
    ): Result = withContext(Dispatchers.IO) {
        val verdicts = assetClassifier.classify(results.flatMap { it.utxos })
        val moves = mutableListOf<Move>()

        for (result in results) {
            // Same partition the sweep used, so the two agree on what was held back and why.
            val partition = SweepPartition.split(
                utxos = result.utxos,
                carriesAsset = { verdicts[it]?.carriesAsset ?: false },
                classified = { verdicts[it]?.classified ?: false },
            )
            if (partition.assetBearing.isEmpty()) continue

            val reserve = AssetFeeReserve.reserve(
                sweepable = partition.sweepable,
                assetCount = partition.assetBearing.size,
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
            val feePool = reserve.reserved.mapNotNull { toSpend(it, byAddress) }

            val planned = ForeignAssetTransferBatch.plan(assets, feePool, destAddress, feePerKb)

            for (item in planned) {
                when (val r = item.result) {
                    is ForeignAssetTransferPlan.Result.Refused ->
                        moves += Move(item.outpoint, 0L, null, "${r.reason}: ${r.detail}")

                    is ForeignAssetTransferPlan.Result.Ok -> {
                        val signed = sign(r.plan, seedBytes, result.profile, feePerKb)
                        if (signed == null) {
                            moves += Move(item.outpoint, r.plan.assetUnits, null,
                                "native refused to sign — see log for the reason")
                            continue
                        }
                        val bytes = runCatching { hexToBytes(signed) }.getOrNull()
                        if (bytes == null) {
                            moves += Move(item.outpoint, r.plan.assetUnits, null,
                                "signed hex malformed")
                            continue
                        }
                        val txid = broadcast(bytes)
                        if (txid == null) {
                            moves += Move(item.outpoint, r.plan.assetUnits, null,
                                "broadcast failed — check peer connection")
                            continue
                        }
                        // Same durability path the sweep and the wallet's own asset send use, so
                        // a force-stop within a second of broadcast does not strand the transfer.
                        // Best-effort: never affects on-chain state.
                        runCatching {
                            outgoingTxStore?.record(
                                txid = txid,
                                sentSats = DA_MARKER_SATS,
                                feeSats = r.plan.feeSat,
                                toAddress = destAddress,
                            )
                            walletTxPersister?.persist()
                        }
                        moves += Move(item.outpoint, r.plan.assetUnits, txid, null)
                    }
                }
            }
        }

        Result(moves)
    }

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
