package io.digibyte.core.digidollar

import io.digibyte.digidollar.Collateral
import io.digibyte.digidollar.DdAddress
import io.digibyte.digidollar.DigiDollarLimits
import io.digibyte.digidollar.EcOps
import io.digibyte.digidollar.LockTier
import io.digibyte.digidollar.MintBuilder
import io.digibyte.digidollar.TxOutput

/**
 * The Mint flow glue (issue #11): policy checks (testnet gate, fresh softfork
 * status, ADR-0002 price divergence), funding-UTXO selection from the wallet's
 * P2WPKH coins, [MintBuilder] assembly, then the sign→broadcast→persist tail
 * mirroring [io.digibyte.core.TransactionBuilder.sendTransaction].
 *
 * Funding is a SINGLE UTXO, like the proven reference builder (digidollar-js
 * buildSignedMintTx / Core's MintTxBuilder): largest-first, and if no single
 * coin covers Collateral + fee the Mint fails with an actionable error.
 *
 * All wallet/native surfaces enter through constructor seams ([WalletPort],
 * [signMint], [broadcast], [persist]) so the flow is JVM-testable; production
 * wiring binds them to NativeBridge, DigiDollarTxSigner, Broadcaster, and
 * WalletTxPersister in the DI layer.
 */
class MintService(
    private val wallet: WalletPort,
    private val statusClient: DigiDollarStatusClient,
    private val gate: DigiDollarGate,
    /** Independent (non-Oracle) DGB/USD price for the divergence cross-check. */
    private val independentUsd: suspend () -> Double?,
    private val signMint: (MintBuilder.UnsignedMint, TxOutput) -> ByteArray,
    private val broadcast: (ByteArray) -> String?,
    private val persist: () -> Unit,
    /** Activity-list record (txid, sentSats, feeSats, toAddress) — mirrors
     *  TransactionBuilder's OutgoingTxStore step. Best-effort, post-broadcast. */
    private val recordOutgoing: (String, Long, Long, String) -> Unit,
    private val ecOps: EcOps,
    /** Hard testnet gate: Mint is testnet-only until the 4.0.0 mainnet unlock. */
    private val testnet: Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The wallet surfaces the flow needs, seamed over NativeBridge. */
    interface WalletPort {
        /** Raw [io.digibyte.core.bridge.NativeBridge.listWalletUtxos] lines. */
        fun listWalletUtxos(): String

        /** First unused bech32 change address (getChangeAddress(0, 2)). */
        fun changeAddress(): String?

        fun addressToScriptPubKey(address: String): ByteArray?

        /** BIP86 Owner key m/86'/coinType'/0'/chain/index, x-only 32 bytes. */
        fun deriveOwnerKey(coinType: Int, chain: Int, index: Int): ByteArray?

        /** Last synced block height. */
        fun tipHeight(): Long

        /** Network-estimated block height (peer consensus). */
        fun estimatedTipHeight(): Long
    }

    sealed class MintResult {
        data class Success(
            val txid: String,
            val unlockHeight: Int,
            val collateralSats: Long,
        ) : MintResult()

        /** Policy said no (gate closed, prices diverge) — retry when conditions change. */
        data class Blocked(val reason: String) : MintResult()

        /** The attempt itself failed (funding, signing, broadcast). */
        data class Error(val message: String) : MintResult()
    }

    suspend fun mint(ddCents: Long, tier: LockTier): MintResult {
        if (!inFlight.compareAndSet(false, true)) {
            return MintResult.Error(
                "A Mint is already in progress — concurrent Mints would double-spend the funding coin",
            )
        }
        try {
            return mintLocked(ddCents, tier)
        } finally {
            inFlight.set(false)
        }
    }

    @Suppress("ReturnCount") // sequential guard clauses ARE the flow
    private suspend fun mintLocked(ddCents: Long, tier: LockTier): MintResult {
        if (!testnet) {
            return MintResult.Blocked(
                "DigiDollar Mint is testnet-only until the 4.0.0 mainnet release",
            )
        }
        val status = statusClient.fetchStatus(testnet)
            ?: return MintResult.Blocked("DigiDollar status unavailable — Mint blocked")
        gate.record(status.deployment, nowMs())
        if (status.deployment != DigiDollarDeployment.ACTIVE) {
            return MintResult.Blocked("DigiDollar softfork is not active on this network")
        }

        val divergence = DivergenceGuard.check(status.priceMicroUsd, independentUsd())
        if (divergence is DivergenceGuard.Decision.Block) {
            return MintResult.Blocked(divergence.reason)
        }

        if (ddCents < DigiDollarLimits.MIN_MINT_CENTS || ddCents > DigiDollarLimits.MAX_MINT_CENTS) {
            return MintResult.Error(
                "Mint amount must be between the consensus minimum of " +
                    "${DigiDollarLimits.MIN_MINT_CENTS} and maximum of " +
                    "${DigiDollarLimits.MAX_MINT_CENTS} cents",
            )
        }

        // unlockHeight commits to the synced tip; on 15s blocks the 100-block
        // confirmation buffer only absorbs ~25 minutes of lag, so a wallet
        // still catching up would commit an unlock height the node rejects.
        val tipHeight = wallet.tipHeight()
        val networkHeight = wallet.estimatedTipHeight()
        if (tipHeight <= 0 || networkHeight <= 0 || tipHeight < networkHeight - MAX_TIP_LAG_BLOCKS) {
            return MintResult.Error(
                "Wallet is still syncing (block $tipHeight of $networkHeight) — " +
                    "wait for sync to finish before Minting",
            )
        }

        val changeAddress = wallet.changeAddress()
            ?: return MintResult.Error("Could not derive a change address")
        val changeScriptHex = wallet.addressToScriptPubKey(changeAddress)?.toHex()
            ?: return MintResult.Error("Could not resolve the change address script")
        if (!changeScriptHex.startsWith("0014")) {
            return MintResult.Error("Change address is not native P2WPKH: $changeAddress")
        }
        // ALWAYS the wallet's watched chain m/86'/20' (DGB_COIN_TYPE is 20 on
        // both networks in BRBIP32Sequence.c): a testnet-style coinType 1 key
        // would mint DD tokens outside allAddrs — invisible and unredeemable.
        val ownerKey = wallet.deriveOwnerKey(OWNER_KEY_COIN_TYPE, 0, 0)
            ?: return MintResult.Error("Owner key derivation failed — wallet locked?")

        val unsigned: MintBuilder.UnsignedMint
        val signed: ByteArray
        val fundingUtxo: UtxoLines.Utxo
        try {
            val neededSats = Collateral.requiredSats(
                ddCents = ddCents,
                tier = tier,
                oraclePriceMicroUsd = status.priceMicroUsd,
                dcaMultiplierBps = status.dcaMultiplierBps,
            ) + MintBuilder.DEFAULT_FEE_SATS

            // Native P2WPKH only: DigiDollarTxSigner enforces the same
            // precondition (legacy inputs would only fail later, looking like
            // signer corruption). Single funding input, largest-first — the
            // reference builder's shape.
            fundingUtxo = UtxoLines.parse(wallet.listWalletUtxos())
                .filter { it.scriptPubKeyHex.length == 44 && it.scriptPubKeyHex.startsWith("0014") }
                .maxByOrNull { it.amount }
                ?.takeIf { it.amount >= neededSats }
                ?: return MintResult.Error(
                    "No single spendable coin covers the Collateral + fee " +
                        "($neededSats sats needed) — consolidate funds and retry",
                )

            unsigned = MintBuilder.buildUnsigned(
                fundingUtxo = MintBuilder.FundingUtxo(
                    fundingUtxo.txidHex,
                    fundingUtxo.vout,
                    fundingUtxo.amount,
                ),
                ddCents = ddCents,
                tier = tier,
                oraclePriceMicroUsd = status.priceMicroUsd,
                tipHeight = tipHeight.toInt(),
                feeSats = MintBuilder.DEFAULT_FEE_SATS,
                ownerKeyHex = ownerKey.toHex(),
                changePubKeyHash160Hex = changeScriptHex.removePrefix("0014"),
                ecOps = ecOps,
                dcaMultiplierBps = status.dcaMultiplierBps,
            )
            signed = signMint(unsigned, TxOutput(fundingUtxo.amount, fundingUtxo.scriptPubKeyHex))
        } catch (e: IllegalArgumentException) {
            return MintResult.Error(e.message ?: "Mint assembly failed")
        } catch (e: IllegalStateException) {
            return MintResult.Error(e.message ?: "Mint signing failed")
        }
        val txid = broadcast(signed)
            ?: return MintResult.Error("Failed to broadcast the Mint transaction")
        // Activity-list record + synchronous tx-set snapshot, mirroring
        // TransactionBuilder.sendTransaction's post-broadcast tail. The DD
        // token address (vout 1) is what the user minted TO; the actual fee
        // includes any dust-folded change.
        recordOutgoing(
            txid,
            unsigned.collateralSats,
            fundingUtxo.amount - unsigned.collateralSats - unsigned.changeSats,
            ddTokenAddress(unsigned),
        )
        persist()
        return MintResult.Success(txid, unsigned.unlockHeight, unsigned.collateralSats)
    }

    /** TD…/DD… encoding of the Mint's DD-token output key (vout 1, `5120` + key). */
    private fun ddTokenAddress(unsigned: MintBuilder.UnsignedMint): String {
        val keyHex = unsigned.tx.outputs[1].scriptPubKeyHex.removePrefix("5120")
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val network = if (testnet) DdAddress.Network.TESTNET else DdAddress.Network.MAINNET
        return DdAddress.encode(key, network)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        /** The wallet's watched BIP86 chain coin type (DGB_COIN_TYPE, both networks). */
        const val OWNER_KEY_COIN_TYPE = 20

        /** Max blocks the synced tip may lag the network estimate (~5 min). */
        const val MAX_TIP_LAG_BLOCKS = 20L
    }
}
