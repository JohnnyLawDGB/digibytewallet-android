package io.digibyte.core.recovery

import io.digibyte.core.OutgoingTxStore
import io.digibyte.core.WalletTxPersister
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.dandelion.Broadcaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves a foreign wallet's DigiDollar into this one.
 *
 * ## Why this is separate from the asset and DGB paths
 *
 * A DigiDollar token output carries zero satoshis and lives at m/86'/20'/0' as a Taproot output.
 * It is invisible to the ordinary UTXO lookup, it is signed with Schnorr rather than ECDSA, and
 * its transaction is identified by an nVersion marker. None of that fits the asset path, and
 * bending the asset path around it would put three unrelated value types through one set of
 * branches.
 *
 * ## Ordering
 *
 * Runs alongside the DigiAsset move and BEFORE the sweep, for the same reason: the DGB paying the
 * consensus fee is exactly what the sweep would otherwise take. Its inputs are then excluded from
 * the sweep by the same [RecoverySequence] mechanism the asset moves use.
 *
 * The native touchpoints are injected so the orchestration — what is reported when the lookup
 * succeeds and the spend fails, or the reverse — is testable on a JVM.
 */
class DigiDollarTransferService(
    private val outgoingTxStore: OutgoingTxStore? = null,
    private val walletTxPersister: WalletTxPersister? = null,
    /** Sign the planned transfer with the foreign seed. Signed hex, or null on refusal. */
    private val sign: (DigiDollarTransferPlan.Plan, ByteArray, DerivationProfile) -> String? =
        ::nativeSign,
    private val broadcast: (ByteArray) -> String? = { Broadcaster.broadcast(it) },
    private val log: (level: Char, message: String) -> Unit = { level, message ->
        if (level == 'w') android.util.Log.w(TAG, message) else android.util.Log.i(TAG, message)
    },
    private val recordOutgoing: (
        txid: String, sentSats: Long, feeSats: Long, toAddress: String, isSelfTransfer: Boolean,
    ) -> Unit = { txid, sent, fee, to, self ->
        outgoingTxStore?.record(txid = txid, sentSats = sent, feeSats = fee, toAddress = to,
            isSelfTransfer = self)
        walletTxPersister?.persist()
    },
) {

    /**
     * What became of the wallet's dollars.
     *
     * [cents] is everything found, which is deliberately reported even when none of it moved —
     * knowing the dollars exist is useful on its own, and silence about them is the bug this
     * whole path exists to fix.
     *
     * [movedCents] is a separate figure and is never read off [cents]: it is the amount the
     * broadcast transfer was planned and signed for. Whatever was found beyond it is
     * [leftBehindCents], so a move that carried only part of the balance reads as partial.
     */
    data class Result(
        val cents: Long,
        val txid: String?,
        val failureReason: String?,
        /** Outpoints the transfer spent, for excluding from the sweep. */
        val spentInputs: List<String> = emptyList(),
        /** Cents we can see and cannot spend — the outpoint could not be located. */
        val unlocatableCents: Long = 0L,
        /** False when a lookup could not be made. Not the same as holding nothing. */
        val reachable: Boolean = true,
        /**
         * Why the planner refused, as data. [failureReason] is an English sentence built for the
         * log; a screen showing a user their own money needs the reason in their own language and
         * in DGB, which only the structured form allows.
         */
        val refusalReason: DigiDollarTransferPlan.Reason? = null,
        /** Satoshis short of the consensus fee, when [refusalReason] is BELOW_FEE_FLOOR. */
        val shortfallSat: Long = 0L,
        /** Cents the broadcast transfer carried — the figure handed to the signer. Zero unless
         *  [txid] is set. */
        val movedCents: Long = 0L,
    ) {
        val moved: Boolean get() = txid != null
        val hasDollars: Boolean get() = cents > 0
        /** Found and still where it was: everything in [cents] the transfer did not carry. */
        val leftBehindCents: Long get() = (cents - movedCents).coerceAtLeast(0L)
    }

    /**
     * @param seedBytes    the FOREIGN seed. Caller owns it and must zero it.
     * @param scan         what [DigiDollarScan] found on that seed.
     * @param feeInputs    plain DGB to pay the consensus fee with.
     * @param feeProfile   the derivation the fee inputs came from — they are NOT at m/86'.
     * @param recipientKeyHex this wallet's taproot output key, from getDigiDollarReceiveAddress.
     */
    suspend fun move(
        seedBytes: ByteArray,
        scan: DigiDollarScan.Result,
        feeInputs: List<ForeignAssetTransferPlan.Spend>,
        feeProfile: DerivationProfile,
        recipientKeyHex: String,
        changeAddress: String,
        feePerKb: Long = DEFAULT_FEE_PER_KB,
    ): Result = withContext(Dispatchers.IO) {
        if (!scan.hasDollars) {
            // Nothing to report and nothing to do. Distinguished from an unreachable lookup,
            // which is carried through so the UI never says "no dollars" about an unanswered
            // question.
            return@withContext Result(0L, null, null, reachable = scan.reachable)
        }

        log('i', "found ${DigiDollarHolding.formatCents(scan.cents)} in DigiDollar across " +
            "${scan.holdings.size} outpoint(s); ${scan.unlocatableCents} cents unlocatable")

        val planned = planFor(scan, feeInputs, recipientKeyHex, changeAddress, feePerKb)

        val plan = when (planned) {
            is DigiDollarTransferPlan.Result.Refused -> {
                log('w', "DigiDollar move refused — ${planned.reason}: ${planned.detail}")
                return@withContext Result(
                    cents = scan.cents,
                    txid = null,
                    failureReason = "${planned.reason}: ${planned.detail}",
                    unlocatableCents = scan.unlocatableCents,
                    reachable = scan.reachable,
                    refusalReason = planned.reason,
                    shortfallSat = planned.shortfallSat,
                )
            }
            is DigiDollarTransferPlan.Result.Ok -> planned.plan
        }

        log('i', "planned: ${plan.cents} cents, ${plan.ddInputs.size} DD input(s), " +
            "${plan.feeInputs.size} fee input(s), fee ${plan.feeSat} sats, " +
            "change ${plan.changeAmountSat} -> ${plan.changeAddress}")

        val signed = sign(plan, seedBytes, feeProfile)
        if (signed == null) {
            log('w', "native refused to sign the DigiDollar transfer")
            return@withContext Result(scan.cents, null,
                "native refused to sign — see log for the reason",
                unlocatableCents = scan.unlocatableCents, reachable = scan.reachable)
        }
        val bytes = runCatching { hexToBytes(signed) }.getOrNull()
            ?: return@withContext Result(scan.cents, null, "signed hex malformed",
                unlocatableCents = scan.unlocatableCents, reachable = scan.reachable)

        val txid = broadcast(bytes)
            ?: return@withContext Result(scan.cents, null,
                "broadcast failed — check peer connection",
                unlocatableCents = scan.unlocatableCents, reachable = scan.reachable)

        // Same durability path the sweep and the asset move use. Recorded as a SELF transfer: the
        // destination is this wallet's own DigiDollar address, so the C core categorizes it as a
        // receive and the activity list must not override that into "Sent".
        runCatching {
            recordOutgoing(txid, 0L, plan.feeSat, plan.changeAddress, true)
        }

        log('i', "DigiDollar MOVED in $txid: ${plan.cents} of ${scan.cents} cents")
        Result(
            cents = scan.cents,
            txid = txid,
            failureReason = null,
            spentInputs = plan.ddInputs.map { "${it.txid}:${it.vout}" } +
                plan.feeInputs.map { "${it.txid}:${it.vout}" },
            unlocatableCents = scan.unlocatableCents,
            reachable = scan.reachable,
            // The signed figure, not the found one: what the scan could not locate is not in
            // this transaction, and the result must not say it is.
            movedCents = plan.cents,
        )
    }

    companion object {
        private const val TAG = "DigiDollarMove"

        /** The fee rate a transfer is sized at unless the caller names another. */
        const val DEFAULT_FEE_PER_KB = 100_000L

        /**
         * Whether [feeInputs] are all the DGB that moving [scan] needs.
         *
         * Puts to [DigiDollarTransferPlan] the question [move] puts to it, with the same
         * arguments, so the answer is about the fee this transfer is charged at its real size and
         * not about a fixed figure. True as soon as no more DGB is asked for — which includes a
         * transfer declined for a reason more DGB would not change, where further coins add
         * nothing.
         */
        fun feeInputsSuffice(
            scan: DigiDollarScan.Result,
            feeInputs: List<ForeignAssetTransferPlan.Spend>,
            recipientKeyHex: String,
            changeAddress: String,
            feePerKb: Long = DEFAULT_FEE_PER_KB,
        ): Boolean {
            val planned = planFor(scan, feeInputs, recipientKeyHex, changeAddress, feePerKb)
            return !(planned is DigiDollarTransferPlan.Result.Refused &&
                planned.reason == DigiDollarTransferPlan.Reason.BELOW_FEE_FLOOR)
        }

        /** The one place a scan becomes a plan, so [move] and [feeInputsSuffice] cannot differ. */
        private fun planFor(
            scan: DigiDollarScan.Result,
            feeInputs: List<ForeignAssetTransferPlan.Spend>,
            recipientKeyHex: String,
            changeAddress: String,
            feePerKb: Long,
        ): DigiDollarTransferPlan.Result = DigiDollarTransferPlan.build(
            holdings = scan.holdings,
            totalCents = scan.movableCents,
            feeInputs = feeInputs,
            recipientKeyHex = recipientKeyHex,
            changeAddress = changeAddress,
            feePerKb = feePerKb,
        )

        private fun nativeSign(
            plan: DigiDollarTransferPlan.Plan,
            seedBytes: ByteArray,
            feeProfile: DerivationProfile,
        ): String? = NativeBridge.buildAndSignForeignDigiDollarTransfer(
            seedBytes = seedBytes,
            ddTxidsHex = plan.ddInputs.map { it.txid }.toTypedArray(),
            ddVouts = plan.ddInputs.map { it.vout }.toIntArray(),
            ddScriptsHex = plan.ddInputs.map { it.scriptPubKeyHex }.toTypedArray(),
            ddChains = plan.ddInputs.map { it.address.chain }.toIntArray(),
            ddIndices = plan.ddInputs.map { it.address.index }.toIntArray(),
            // The fee inputs are NOT at m/86' — they sit wherever the scan found the plain DGB.
            feeHmacKey = feeProfile.hmacKey,
            feePrefixPath = feeProfile.prefixPath,
            feeTxidsHex = plan.feeInputs.map { it.txid }.toTypedArray(),
            feeVouts = plan.feeInputs.map { it.vout }.toIntArray(),
            feeAmounts = plan.feeInputs.map { it.amountSat }.toLongArray(),
            feeScriptsHex = plan.feeInputs.map { it.scriptPubKeyHex }.toTypedArray(),
            feeChains = plan.feeInputs.map { it.chain }.toIntArray(),
            feeIndices = plan.feeInputs.map { it.index }.toIntArray(),
            recipientKeyHex = plan.recipientKeyHex,
            changeAddress = plan.changeAddress.takeIf { plan.changeAmountSat > 0 },
            changeAmount = plan.changeAmountSat,
            cents = plan.cents,
        )

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "odd-length hex" }
            return ByteArray(hex.length / 2) {
                ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
            }
        }
    }
}
