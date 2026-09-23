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
class LegacySweepService internal constructor(
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
    /** Reads each input's parent transaction. Native in production. */
    private val parents: RawTxBinding,
    /** Signs one profile's sweep with the foreign seed. Signed hex, or null on refusal. */
    private val signSweep: (
        seed: ByteArray, profile: DerivationProfile, inputs: SweepInputs, destAddress: String, feePerKb: Long,
    ) -> String?,
    /** Broadcasts signed bytes. The relay txid, or null. */
    private val broadcast: (ByteArray) -> String?,
    /** Progress lines. Injected because android.util.Log is an unmocked stub on the JVM. */
    private val log: (String) -> Unit,
) {

    constructor(
        outgoingTxStore: OutgoingTxStore,
        walletTxPersister: WalletTxPersister,
        assetClassifier: ForeignUtxoAssetClassifier,
    ) : this(
        outgoingTxStore = outgoingTxStore,
        walletTxPersister = walletTxPersister,
        assetClassifier = assetClassifier,
        parents = RawTxBinding.native(),
        signSweep = ::nativeSignSweep,
        broadcast = { Broadcaster.broadcast(it) },
        log = { android.util.Log.i("LegacySweep", it) },
    )

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
        /** Outpoints left behind because the transaction they came from could not be read: either
         *  the asset question could not be answered (a raw tx that would not fetch or parse), or
         *  the lookup supplied no parent transaction for them, or one that does not parse, so the
         *  amount and script to sign could not be taken from it. Distinct from [heldBackAssets]:
         *  these MIGHT be plain DGB. Held anyway: an input is signed only once the transaction it
         *  came from has been read. Retrying later may free them. */
        val heldBackUnknown: List<String> = emptyList(),
        /** Outpoints the DigiAsset moves already claimed — spent by a move that broadcast, or
         *  held for one that failed and will be retried. Not a reserve: these are the exact
         *  inputs concrete plans named, which is why AssetFeeReserve's estimate is gone. */
        val heldBackFeeReserve: List<String> = emptyList(),
        /**
         * The fee the signed transaction pays, read back from it: what the outpoints it spends
         * hold, at the amounts their parent transactions state, less what it pays out. Null when
         * nothing was signed.
         */
        val feeSat: Long? = null,
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
            sweepFromSeed(
                seedBytes = seedBytes,
                nonNativeResults = nonNativeResults,
                destAddress = destAddress,
                feePerKb = feePerKb,
                destIsSelf = destIsSelf,
            )
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
        /** Outpoints the DigiAsset moves already spent, or are holding for a retry. The sweep is
         *  the complement of these — see [RecoverySequence.sweepExclusions]. Empty when the
         *  wallet holds no assets. */
        excludeOutpoints: Set<String> = emptySet(),
        /** Reuses the move phase's classification rather than fetching every parent transaction
         *  a second time for the same answer. */
        precomputedVerdicts: Map<io.digibyte.core.reconcile.UtxoEntry, ForeignUtxoAssetClassifier.Verdict>? = null,
        /** True when [destAddress] is an address THIS wallet owns (the default
         *  "recover into my own wallet" path). Recorded on the OutgoingTxStore
         *  entry so the activity list won't misrender the balance-increasing
         *  sweep as a large negative "Sent" (see OutgoingTxStore
         *  .shouldApplyOutgoingOverride). External-address sweeps pass false. */
        destIsSelf: Boolean = false,
    ): Result {
        // Classified ONCE for the whole sweep rather than per profile: profiles share addresses
        // and therefore parent transactions, and the classifier caches by txid within a call.
        val verdicts = precomputedVerdicts
            ?: assetClassifier.classify(nonNativeResults.flatMap { it.utxos })

        // BIP49 is no longer special-cased. BRTransactionSign grew a P2SH-P2WPKH branch, so a
        // wrapped-segwit profile signs and sweeps like any other. Its inputs are in fact SAFER
        // than the legacy ones the provenance gate below defends: BIP143 commits to the input
        // amount, so a stale or under-reported value produces an invalid signature rather than
        // a valid one that silently burns the difference to fee.
        val outcomes = nonNativeResults.map { result ->
            when (val admission = admit(result)) {
                is Admission.Refused ->
                    SweepOutcome(result.profile, null, null, 0L, 0, admission.reason,
                        broadcastState = BroadcastState.FAILED)
                is Admission.Admitted ->
                    sweepOneProfile(seedBytes, result, admission.parents, destAddress, feePerKb,
                        destIsSelf, verdicts, excludeOutpoints)
            }
        }
        return Result(outcomes)
    }

    internal sealed class Admission {
        data class Refused(val reason: String) : Admission()
        /** [parents] says which inputs are proven, with the values their parents state. */
        class Admitted(val parents: RawTxBinding.Verdict.Checked) : Admission()
    }

    /**
     * Pre-sign gate: whether anything on this profile may be signed.
     *
     * A legacy P2PKH signature does not commit to the amount it spends, so the amount handed to
     * the signer has to be the one the chain holds. The whole profile is refused when:
     *   - the reconcile backend was unreachable (nothing it reported was answered);
     *   - any UTXO reports a non-positive amount;
     *   - any parent transaction the lookup supplied disagrees with what the lookup reported for
     *     an outpoint (see [RawTxBinding]). One disagreement means no amount reported for the
     *     profile is used, not just that row's.
     * An input whose parent was not supplied, or does not parse, is not a refusal: it is held
     * back and reported, and the rest of the profile proceeds with proven values only.
     */
    internal fun admit(result: RecoveryScanService.ProfileResult): Admission {
        if (!result.reachableBackend) {
            return Admission.Refused("backend unreachable — refusing to sign against unverified input amounts")
        }
        val bad = result.utxos.firstOrNull { it.amountSatoshi <= 0L }
        if (bad != null) {
            return Admission.Refused(
                "non-positive amount ${bad.amountSatoshi} on ${bad.txid}:${bad.vout} — refusing sweep",
            )
        }
        return when (val checked = parents.check(result)) {
            is RawTxBinding.Verdict.Contradicted -> Admission.Refused(
                "${checked.outpoint}: ${checked.detail} — refusing to sign amounts its parent " +
                    "transaction does not state",
            )
            is RawTxBinding.Verdict.Checked -> Admission.Admitted(checked)
        }
    }

    /** The refusal reason [admit] gives, or null when the profile may proceed. */
    internal fun amountProvenanceGate(result: RecoveryScanService.ProfileResult): String? =
        (admit(result) as? Admission.Refused)?.reason

    private fun sweepOneProfile(
        seed: ByteArray,
        result: RecoveryScanService.ProfileResult,
        checked: RawTxBinding.Verdict.Checked,
        destAddress: String,
        feePerKb: Long,
        destIsSelf: Boolean,
        verdicts: Map<io.digibyte.core.reconcile.UtxoEntry, ForeignUtxoAssetClassifier.Verdict>,
        excludeOutpoints: Set<String>,
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
            log(
                "profile=${profile.label}: holding back ${heldAssets.size} asset-bearing and " +
                    "${heldUnknown.size} unclassified outpoint(s); sweeping ${partition.sweepable.size}",
            )
        }
        // The DigiAsset moves have already run. What they spent — and, for a move that failed,
        // what its plan named — is excluded here. This used to be AssetFeeReserve holding back a
        // per-asset CONSTANT chosen before anything knew what a transfer costs; it shipped at
        // 40,000 sats against a real 54,900-70,100. Reordered, there is nothing to estimate:
        // these are the outpoints concrete plans actually claimed. See RecoverySequence.
        val stillSweepable = partition.sweepable.filterNot { "${it.txid}:${it.vout}" in excludeOutpoints }
        val heldForAssets = partition.sweepable
            .filter { "${it.txid}:${it.vout}" in excludeOutpoints }
            .map { "${it.txid}:${it.vout}" }
        if (heldForAssets.isNotEmpty()) {
            log(
                "profile=${profile.label}: ${heldForAssets.size} outpoint(s) already claimed by " +
                    "the DigiAsset move(s); sweeping ${stillSweepable.size}",
            )
        }
        // Only inputs whose parent transaction states their amount and script are signed, and
        // they are signed with the values the parent states. An input without a readable parent
        // stays where it is and is reported beside the ones the asset check could not answer:
        // for both, the transaction it came from could not be read.
        val proven = stillSweepable.mapNotNull { checked.proven(it) }
        val unproven = stillSweepable.filter { checked.proven(it) == null }.map { "${it.txid}:${it.vout}" }
        if (unproven.isNotEmpty()) {
            log("profile=${profile.label}: holding back ${unproven.size} outpoint(s) whose parent " +
                "transaction was not supplied or did not parse")
        }
        val heldUnread = (heldUnknown + unproven).distinct()
        // #3: each UTXO's (chain,index) is carried straight from its
        // DerivedAddress — no positional reconstruction vs gapExternal, so a
        // dropped empty slot can't sign the wrong child key. #4: a proven copy
        // carries its parent's scriptPubKey, so a lookup row that gave none is
        // no longer skipped; skippedNoScript stays for rows that reach here without one.
        val inputs = assembleSweepInputs(result.copy(utxos = proven))

        if (inputs.txids.isEmpty()) {
            // "Everything was kept on purpose" and "nothing could be used" both arrive here with
            // no inputs, and they mean opposite things. Reporting the first as FAILED with "no
            // mappable UTXOs" tells someone looking at a wallet they can see has coins in it that
            // it malfunctioned and their funds are at risk — when in fact the wallet deliberately
            // kept them so their DigiAsset would still be movable.
            val reservedEverything = heldForAssets.isNotEmpty() && unproven.isEmpty()
            val reason = when {
                reservedEverything ->
                    "Nothing was swept — all of it was kept back so your " +
                        "${partition.assetBearing.size} DigiAsset(s) can still be moved. " +
                        "Your coins are safe where they are."
                unproven.isNotEmpty() ->
                    "no input's parent transaction could be read — ${unproven.size} left in place"
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
                heldBackUnknown = heldUnread,
                // Previously omitted here, so the one branch where the reserve explains
                // EVERYTHING was the one branch that did not mention it.
                heldBackFeeReserve = heldForAssets,
            )
        }

        val signedHex = signSweep(seed, profile, inputs, destAddress, feePerKb) ?: return SweepOutcome(
            profile, null, null, 0L, inputs.txids.size,
            "buildAndSignLegacySweep failed (sign mismatch or dust)",
            broadcastState = BroadcastState.FAILED,
            skippedNoScript = inputs.skippedNoScript,
            heldBackAssets = heldAssets,
            heldBackUnknown = heldUnread,
            heldBackFeeReserve = heldForAssets,
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
                heldBackUnknown = heldUnread,
            )

        // Read back from the signed transaction, not estimated: what the inputs it spends hold,
        // at the amounts their parents state, less what it pays out. A transaction that does not
        // read back as spending exactly the proven inputs is not sent.
        val spent = inputs.txids.indices.associate { "${inputs.txids[it]}:${inputs.vouts[it]}" to inputs.amounts[it] }
        val feeSat = parents.feePaid(txBytes, spent)
            ?: return SweepOutcome(
                profile, signedHex, null, 0L, inputs.txids.size,
                "signed transaction did not read back as spending exactly its inputs — not sent",
                broadcastState = BroadcastState.FAILED,
                skippedNoScript = inputs.skippedNoScript,
                heldBackAssets = heldAssets,
                heldBackUnknown = heldUnread,
                heldBackFeeReserve = heldForAssets,
            )

        val txid = broadcast(txBytes)
        log("profile=${profile.label}: ${inputs.txids.size} input(s), ${inputs.totalIn} sat in, " +
            "fee $feeSat sat read back from the signed transaction; txid=${txid ?: "not relayed"}")
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
                feeSats = feeSat,
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
            heldBackUnknown = heldUnread,
            heldBackFeeReserve = heldForAssets,
            feeSat = feeSat,
        )
    }

    internal companion object {
        fun nativeSignSweep(
            seed: ByteArray,
            profile: DerivationProfile,
            inputs: SweepInputs,
            destAddress: String,
            feePerKb: Long,
        ): String? = NativeBridge.buildAndSignLegacySweep(
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
        )
    }

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
