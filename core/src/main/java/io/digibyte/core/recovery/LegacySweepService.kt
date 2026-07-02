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
        passphrase: String?,
        nonNativeResults: List<RecoveryScanService.ProfileResult>,
        destAddress: String,
        feePerKb: Long = 100_000L, // DGB min relay × 1 sat/byte
    ): Result = withContext(Dispatchers.IO) {
        val phraseBytes = mnemonic.trim().lowercase().toByteArray(Charsets.UTF_8)
        var seedBytes: ByteArray? = null
        try {
            seedBytes = NativeBridge.mnemonicToSeed(phraseBytes, passphrase)
                ?: return@withContext Result(nonNativeResults.map {
                    SweepOutcome(it.profile, null, null, 0L, 0, "seed derivation failed", BroadcastState.FAILED)
                })
            sweepFromSeed(seedBytes, nonNativeResults, destAddress, feePerKb)
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
    ): Result {
        val outcomes = nonNativeResults.map { result ->
            if (result.profile.addressFormat == 2 /* P2SH-P2WPKH / BIP49 */) {
                SweepOutcome(result.profile, null, null, 0L, 0,
                    "BIP49 P2SH-P2WPKH sweep not yet supported — manual recovery required",
                    BroadcastState.FAILED)
            } else {
                sweepOneProfile(seedBytes, result, destAddress, feePerKb)
            }
        }
        return Result(outcomes)
    }

    private fun sweepOneProfile(
        seed: ByteArray,
        result: RecoveryScanService.ProfileResult,
        destAddress: String,
        feePerKb: Long,
    ): SweepOutcome {
        val profile = result.profile
        val addrs = result.addresses

        // Map each UTXO's address back to its (chain, index) within the
        // profile's derived address list. We know the order from the
        // deriveAddresses call: external[0..gapExternal-1] then
        // internal[0..gapInternal-1].
        val addrIndex: Map<String, Int> =
            addrs.withIndex().associate { (i, a) -> a to i }

        val txids = mutableListOf<String>()
        val vouts = mutableListOf<Int>()
        val amounts = mutableListOf<Long>()
        val chains = mutableListOf<Int>()
        val indices = mutableListOf<Int>()
        val scripts = mutableListOf<String>()
        var totalIn = 0L

        for (utxo in result.utxos) {
            val pos = addrIndex[utxo.address] ?: continue
            val (chain, index) = if (pos < profile.gapExternal) {
                0 to pos
            } else {
                1 to (pos - profile.gapExternal)
            }

            val script = utxo.scriptPubKeyHex
                ?: return SweepOutcome(
                    profile, null, null, 0L, 0,
                    "scriptPubKey missing for ${utxo.address} (old backend?)",
                    BroadcastState.FAILED
                )

            txids += utxo.txid
            vouts += utxo.vout
            amounts += utxo.amountSatoshi
            chains += chain
            indices += index
            scripts += script
            totalIn += utxo.amountSatoshi
        }

        if (txids.isEmpty()) {
            return SweepOutcome(profile, null, null, 0L, 0, "no mappable UTXOs", BroadcastState.FAILED)
        }

        val signedHex = NativeBridge.buildAndSignLegacySweep(
            seedBytes = seed,
            hmacKey = profile.hmacKey,
            prefixPath = profile.prefixPath,
            txidsHex = txids.toTypedArray(),
            vouts = vouts.toIntArray(),
            amounts = amounts.toLongArray(),
            chainIndices = chains.toIntArray(),
            addressIndices = indices.toIntArray(),
            scriptPubKeysHex = scripts.toTypedArray(),
            destAddress = destAddress,
            feePerKb = feePerKb,
        ) ?: return SweepOutcome(
            profile, null, null, 0L, txids.size,
            "buildAndSignLegacySweep failed (sign mismatch or dust)",
            BroadcastState.FAILED
        )

        // Broadcast via the existing publishTransaction JNI — it takes raw
        // bytes, not hex, so decode here.
        val txBytes = runCatching { hexToBytes(signedHex) }.getOrNull()
            ?: return SweepOutcome(
                profile, signedHex, null, totalIn, txids.size,
                "signed hex malformed (self-check failed)",
                BroadcastState.FAILED
            )

        val txid = Broadcaster.broadcast(txBytes)
        if (txid != null) {
            // Durability: route the sweep through the same OutgoingTxStore +
            // WalletTxPersister the normal send uses so
            // SyncService.rebroadcastStrandedSends() re-publishes it if a
            // force-stop within ~1s of broadcast strands the stem before the
            // network relays it back. Best-effort — never affects on-chain state.
            outgoingTxStore.record(
                txid = txid,
                sentSats = totalIn,
                feeSats = estimateFee(txBytes.size, feePerKb),
                toAddress = destAddress,
            )
            walletTxPersister.persist()
        }
        return SweepOutcome(
            profile = profile,
            txHex = signedHex,
            txid = txid,
            sweptSat = totalIn,
            inputCount = txids.size,
            failureReason = if (txid == null) "broadcast failed — no peer accepted the sweep" else null,
            // A non-null txid is local relay only — PENDING, never confirmed (#6).
            broadcastState = if (txid == null) BroadcastState.FAILED else BroadcastState.PENDING,
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
