package io.digibyte.core.recovery

import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.WalletTxPersister
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.dandelion.Broadcaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds and broadcasts sweep transactions that move funds from non-native
 * derivation paths (discovered during Universal Restore scan) into the
 * user's current BIP84 wallet.
 *
 * One sweep tx per profile — different profiles need different HMAC keys
 * so we can't combine them into a single signed tx. DGB fees are low
 * enough that one-tx-per-profile is still very cheap (a few hundred
 * satoshis per sweep).
 */
class LegacySweepService(
    private val outgoingTxStore: OutgoingTxStore,
    private val walletTxPersister: WalletTxPersister,
    /**
     * Decides which of a foreign seed's UTXOs may be spent as plain DGB.
     *
     * REQUIRED, deliberately. A nullable classifier would have to default either to sweeping
     * everything — which destroys any DigiAsset on the seed — or to sweeping nothing, which
     * silently breaks recovery. Making the compiler demand it means the decision is made once,
     * at the call site, by someone who can see it.
     */
    private val assetClassifier: ForeignUtxoAssetClassifier,
) {

    /** Acceptance state of a sweep broadcast. A returned txid means the tx
     *  reached local relay / mempool-pending only — NOT that the network
     *  accepted or confirmed it (bug #6). RELAYED is reserved for a future
     *  relay-count confirmation; today a submitted sweep terminates at PENDING
     *  and confirmation is observed later via normal BIP158/SPV sync. */
    enum class BroadcastState { PENDING, RELAYED, FAILED }

    data class SweepOutcome(
        val profile: DerivationProfile,
        val txHex: String?,      // signed hex, or null if we couldn't build
        val txid: String?,       // relay txid (PENDING), or null on broadcast failure
        val sweptSat: Long,
        val inputCount: Int,
        val failureReason: String?,
        val broadcastState: BroadcastState,
        /** Addresses whose backend row had no scriptPubKey and were skipped
         *  rather than aborting the profile (bug #4). Empty on a clean sweep. */
        val skippedNoScript: List<String> = emptyList(),
        /** Outpoints ("txid:vout") left behind because they carry a DigiAsset. Spending these as
         *  plain DGB would destroy the asset, so the sweep proceeds without them and says so. */
        val heldBackAssets: List<String> = emptyList(),
        /** Outpoints left behind because the asset question could not be answered — a raw tx that
         *  would not fetch or parse. Distinct from [heldBackAssets]: these MIGHT be plain DGB.
         *  Held anyway, because being wrong here burns an asset. Retrying later may free them. */
        val heldBackUnknown: List<String> = emptyList(),
        /** Outpoints kept back as fee money so the held assets can actually be moved later.
         *  Without this the assets survive the sweep and are then unmovable. */
        val heldBackFeeReserve: List<String> = emptyList(),
        /** Sats short of what the held assets need to move. Non-zero means they cannot leave this
         *  wallet without new funds — the user has to be told, not left to discover it. */
        val feeReserveShortfall: Long = 0L,
    )

    data class Result(
        val outcomes: List<SweepOutcome>,
    ) {
        val totalSweptSat: Long = outcomes.sumOf { it.sweptSat }
        /** True when every profile at least reached local relay (PENDING/RELAYED).
         *  Deliberately NOT "succeeded": a PENDING tx is submitted, not confirmed
         *  — never claim confirmed success on local relay alone (#6). */
        val allSubmitted: Boolean =
            outcomes.isNotEmpty() && outcomes.all { it.broadcastState != BroadcastState.FAILED }
        /** Any tx still awaiting network relay/confirmation. */
        val anyPending: Boolean = outcomes.any { it.broadcastState == BroadcastState.PENDING }
    }

    /** Sweep every non-native profile result into [destAddress].
     *
     * The mnemonic is re-derived to seed internally; caller is responsible
     * for zeroing the mnemonic from its own state after this call returns. */
    suspend fun sweep(
        mnemonic: String,
        /** NFKD UTF-8 bytes, or null. Caller owns and zeroes them. */
        passphrase: ByteArray?,
        nonNativeResults: List<RecoveryScanService.ProfileResult>,
        destAddress: String,
        feePerKb: Long = 100_000L, // DGB min relay × 1 sat/byte
        destIsSelf: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val phraseBytes = mnemonic.trim().lowercase().toByteArray(Charsets.UTF_8)
        var seedBytes: ByteArray? = null
        try {
            seedBytes = NativeBridge.mnemonicToSeed(phraseBytes, passphrase)
                ?: return@withContext Result(nonNativeResults.map {
                    SweepOutcome(it.profile, null, null, 0L, 0, "seed derivation failed", BroadcastState.FAILED)
                })
            sweepFromSeed(seedBytes, nonNativeResults, destAddress, feePerKb, destIsSelf)
        } finally {
            seedBytes?.fill(0)
            phraseBytes.fill(0)
        }
    }

    /** Seed-bytes entry point for the already-restored (Settings) path. The caller
     *  owns seedBytes and must zero it; we never derive a mnemonic String here. */
    suspend fun sweepFromSeed(
        seedBytes: ByteArray,
        nonNativeResults: List<RecoveryScanService.ProfileResult>,
        destAddress: String,
        feePerKb: Long = 100_000L,
        /** True when [destAddress] is an address THIS wallet owns (the default
         *  "recover into my own wallet" path). Recorded on the OutgoingTxStore
         *  entry so the activity list won't misrender the balance-increasing
         *  sweep as a large negative "Sent" (see OutgoingTxStore
         *  .shouldApplyOutgoingOverride). External-address sweeps pass false. */
        destIsSelf: Boolean = false,
    ): Result {
        // Classified ONCE for the whole sweep rather than per profile: profiles share addresses
        // and therefore parent transactions, and the classifier caches by txid within a call.
        val verdicts = assetClassifier.classify(nonNativeResults.flatMap { it.utxos })

        val outcomes = nonNativeResults.map { result ->
            if (result.profile.addressFormat == 2 /* P2SH-P2WPKH / BIP49 */) {
                SweepOutcome(result.profile, null, null, 0L, 0,
                    "BIP49 P2SH-P2WPKH sweep not yet supported — manual recovery required",
                    broadcastState = BroadcastState.FAILED)
            } else {
                val refusal = amountProvenanceGate(result)
                if (refusal != null) {
                    SweepOutcome(result.profile, null, null, 0L, 0, refusal,
                        broadcastState = BroadcastState.FAILED)
                } else {
                    sweepOneProfile(seedBytes, result, destAddress, feePerKb, destIsSelf, verdicts)
                }
            }
        }
        return Result(outcomes)
    }

    /**
     * Amount-provenance pre-sign gate (bug #2 — fund-loss defense).
     *
     * The legacy P2PKH sighash does NOT commit to input amounts, so a stale or
     * under-reported amountSatoshi still signs into a consensus-valid tx that
     * spends the REAL prevout and burns the unreported remainder to fee. We
     * cannot verify a foreign prevout on-device without fetching it, so we
     * apply the cheap, honest guards we CAN:
     *   - refuse if the reconcile backend was unreachable (amounts are
     *     unverified hints; never sign against a null reconcile result);
     *   - refuse if ANY UTXO reports a non-positive amount — a corrupt/hostile
     *     row, and because the sighash is amount-blind, one bad row means the
     *     whole response's amounts are untrustworthy, so we refuse the entire
     *     profile-sweep rather than sign a subset.
     * Returns a human-readable refusal reason, or null when the profile's
     * UTXOs are safe to hand to the signer. Pure — no JNI, unit-testable.
     */
    internal fun amountProvenanceGate(
        result: RecoveryScanService.ProfileResult,
    ): String? {
        if (!result.reachableBackend) {
            return "backend unreachable — refusing to sign against unverified input amounts"
        }
        val bad = result.utxos.firstOrNull { it.amountSatoshi <= 0L }
        if (bad != null) {
            return "non-positive amount ${bad.amountSatoshi} on ${bad.txid}:${bad.vout} — refusing sweep"
        }
        return null
    }

    private fun sweepOneProfile(
        seed: ByteArray,
        result: RecoveryScanService.ProfileResult,
        destAddress: String,
        feePerKb: Long,
        destIsSelf: Boolean,
        verdicts: Map<io.digibyte.core.reconcile.UtxoEntry, ForeignUtxoAssetClassifier.Verdict>,
    ): SweepOutcome {
        val profile = result.profile

        // Hold back anything carrying a DigiAsset, and anything we could not ask about. Spending
        // an asset UTXO as plain DGB destroys the asset — it is not moved, it is gone — so the
        // sweep proceeds WITHOUT them and reports what it left, rather than refusing outright:
        // the held-back coins are still safe in the old wallet and can be moved deliberately.
        val partition = SweepPartition.split(
            utxos = result.utxos,
            carriesAsset = { verdicts[it]?.carriesAsset ?: false },
            // Absent from the map means never classified — same fail-closed answer as a failed
            // lookup. A UTXO the classifier never saw must not be swept by default.
            classified = { verdicts[it]?.classified ?: false },
        )
        val heldAssets = partition.assetBearing.map { "${it.txid}:${it.vout}" }
        val heldUnknown = partition.unclassified.map { "${it.txid}:${it.vout}" }
        if (heldAssets.isNotEmpty() || heldUnknown.isNotEmpty()) {
            android.util.Log.i(
                "LegacySweep",
                "profile=${profile.label}: holding back ${heldAssets.size} asset-bearing and " +
                    "${heldUnknown.size} unclassified outpoint(s); sweeping ${partition.sweepable.size}",
            )
        }
        // Assets must move while the DGB that pays their fees is still here. Holding an asset
        // back but taking every coin around it leaves it safe and UNMOVABLE — its own marker
        // output is ~6,000 sats against a ~40,000 sat transfer — stranded in a wallet the user is
        // walking away from. So the fee money stays put until the asset does.
        val reserve = AssetFeeReserve.reserve(
            sweepable = partition.sweepable,
            assetCount = partition.assetBearing.size,
        )
        val heldReserve = reserve.reserved.map { "${it.txid}:${it.vout}" }
        if (heldReserve.isNotEmpty()) {
            android.util.Log.i(
                "LegacySweep",
                "profile=${profile.label}: reserving ${reserve.reserved.sumOf { it.amountSatoshi }} " +
                    "sats across ${heldReserve.size} outpoint(s) to move ${partition.assetBearing.size} " +
                    "asset(s); shortfall=${reserve.shortfall}",
            )
        }
        val sweepableResult = result.copy(utxos = reserve.stillSweepable)
        // #3: each UTXO's (chain,index) is carried straight from its
        // DerivedAddress — no positional reconstruction vs gapExternal, so a
        // dropped empty slot can't sign the wrong child key. #4: a UTXO with a
        // null scriptPubKey is collected in skippedNoScript, not fatal.
        val inputs = assembleSweepInputs(sweepableResult)

        if (inputs.txids.isEmpty()) {
            // "Everything was kept on purpose" and "nothing could be used" both arrive here with
            // no inputs, and they mean opposite things. Reporting the first as FAILED with "no
            // mappable UTXOs" tells someone looking at a wallet they can see has coins in it that
            // it malfunctioned and their funds are at risk — when in fact the wallet deliberately
            // kept them so their DigiAsset would still be movable.
            val reservedEverything = reserve.reserved.isNotEmpty() && reserve.shortfall == 0L
            val reason = when {
                reservedEverything ->
                    "Nothing was swept — all of it was kept back so your " +
                        "${partition.assetBearing.size} DigiAsset(s) can still be moved. " +
                        "Your coins are safe where they are."
                inputs.skippedNoScript.isNotEmpty() ->
                    "all ${inputs.skippedNoScript.size} UTXO(s) missing scriptPubKey (old backend?)"
                else -> "no mappable UTXOs"
            }
            return SweepOutcome(
                profile, null, null, 0L, 0, reason,
                // Not a failure when it was deliberate. PENDING would imply a broadcast, so the
                // sweep reports FAILED only for the cases that actually went wrong.
                broadcastState = if (reservedEverything) BroadcastState.PENDING
                                 else BroadcastState.FAILED,
                skippedNoScript = inputs.skippedNoScript,
                heldBackAssets = heldAssets,
                heldBackUnknown = heldUnknown,
                // Previously omitted here, so the one branch where the reserve explains
                // EVERYTHING was the one branch that did not mention it.
                heldBackFeeReserve = heldReserve,
                feeReserveShortfall = reserve.shortfall,
            )
        }

        val signedHex = NativeBridge.buildAndSignLegacySweep(
            seedBytes = seed,
            hmacKey = profile.hmacKey,
            prefixPath = profile.prefixPath,
            txidsHex = inputs.txids.toTypedArray(),
            vouts = inputs.vouts.toIntArray(),
            amounts = inputs.amounts.toLongArray(),
            chainIndices = inputs.chains.toIntArray(),
            addressIndices = inputs.indices.toIntArray(),
            scriptPubKeysHex = inputs.scripts.toTypedArray(),
            destAddress = destAddress,
            feePerKb = feePerKb,
        ) ?: return SweepOutcome(
            profile, null, null, 0L, inputs.txids.size,
            "buildAndSignLegacySweep failed (sign mismatch or dust)",
            broadcastState = BroadcastState.FAILED,
            skippedNoScript = inputs.skippedNoScript,
            heldBackAssets = heldAssets,
            heldBackUnknown = heldUnknown,
            heldBackFeeReserve = heldReserve,
            feeReserveShortfall = reserve.shortfall,
        )

        // Broadcast via the existing publishTransaction JNI — it takes raw
        // bytes, not hex, so decode here.
        val txBytes = runCatching { hexToBytes(signedHex) }.getOrNull()
            ?: return SweepOutcome(
                profile, signedHex, null, inputs.totalIn, inputs.txids.size,
                "signed hex malformed (self-check failed)",
                broadcastState = BroadcastState.FAILED,
                skippedNoScript = inputs.skippedNoScript,
                heldBackAssets = heldAssets,
                heldBackUnknown = heldUnknown,
            )

        val txid = Broadcaster.broadcast(txBytes)
        if (txid != null) {
            // Durability: route the sweep through the same OutgoingTxStore +
            // WalletTxPersister the normal send uses so
            // SyncService.rebroadcastStrandedSends() re-publishes it if a
            // force-stop within ~1s of broadcast strands the stem before the
            // network relays it back. Best-effort — never affects on-chain state.
            //
            // Durability caveat (external-address sweeps only): when destIsSelf
            // is false, the sweep has NO wallet-relevant output, so
            // BRWalletRegisterTransaction orphans it (it isn't in the wallet's
            // tx set) and rebroadcastStrandedSends() — which re-publishes from
            // the wallet's serialized txs — can't re-broadcast it. The
            // OutgoingTxStore record then lingers with no matching wallet tx.
            // Self-transfer sweeps (destIsSelf=true) are registered normally
            // because their output pays a wallet address, so they persist and
            // re-broadcast like an ordinary send.
            //
            // isSelfTransfer tags a "recover into my own wallet" sweep so the
            // activity list leaves the C core's receive categorization intact
            // instead of overriding it to a negative "Sent" (Finding 1).
            outgoingTxStore.record(
                txid = txid,
                sentSats = inputs.totalIn,
                feeSats = estimateFee(txBytes.size, feePerKb),
                toAddress = destAddress,
                isSelfTransfer = destIsSelf,
            )
            walletTxPersister.persist()
        }
        return SweepOutcome(
            profile = profile,
            txHex = signedHex,
            txid = txid,
            sweptSat = inputs.totalIn,
            inputCount = inputs.txids.size,
            failureReason = if (txid == null) "broadcast failed — no peer accepted the sweep" else null,
            // A non-null txid is local relay only — PENDING, never confirmed (#6).
            broadcastState = if (txid == null) BroadcastState.FAILED else BroadcastState.PENDING,
            skippedNoScript = inputs.skippedNoScript,
            heldBackAssets = heldAssets,
            heldBackUnknown = heldUnknown,
            heldBackFeeReserve = heldReserve,
            feeReserveShortfall = reserve.shortfall,
        )
    }

    private fun estimateFee(signedSize: Int, feePerKb: Long): Long =
        (signedSize.toLong() * feePerKb + 999L) / 1000L

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must be even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Input arrays for one sweep tx, assembled from a profile's UTXOs. Each UTXO's
 *  (chain,index) comes straight from its [DerivedAddress] — no positional
 *  reconstruction (bug #3 fix). UTXOs whose backend row lacks a scriptPubKey are
 *  collected in [skippedNoScript] and skipped, not fatal to the profile (bug #4).
 *  Pure + JNI-free so it is unit-testable. */
internal data class SweepInputs(
    val txids: List<String>,
    val vouts: List<Int>,
    val amounts: List<Long>,
    val chains: List<Int>,
    val indices: List<Int>,
    val scripts: List<String>,
    val totalIn: Long,
    val skippedNoScript: List<String>,
)

internal fun assembleSweepInputs(
    result: RecoveryScanService.ProfileResult,
): SweepInputs {
    val byAddress: Map<String, DerivedAddress> =
        result.derivedAddresses.associateBy { it.address }

    val txids = mutableListOf<String>()
    val vouts = mutableListOf<Int>()
    val amounts = mutableListOf<Long>()
    val chains = mutableListOf<Int>()
    val indices = mutableListOf<Int>()
    val scripts = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    var totalIn = 0L

    for (utxo in result.utxos) {
        val derived = byAddress[utxo.address] ?: continue
        val script = utxo.scriptPubKeyHex
        if (script == null) {
            // #4: one missing-scriptPubKey row must not abort the whole profile.
            skipped += utxo.address
            continue
        }
        txids += utxo.txid
        vouts += utxo.vout
        amounts += utxo.amountSatoshi
        chains += derived.chain      // #3: carried from derivation, not reconstructed
        indices += derived.index
        scripts += script
        totalIn += utxo.amountSatoshi
    }
    return SweepInputs(txids, vouts, amounts, chains, indices, scripts, totalIn, skipped)
}
