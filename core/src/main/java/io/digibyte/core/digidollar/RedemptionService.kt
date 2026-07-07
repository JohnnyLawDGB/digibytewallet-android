package io.digibyte.core.digidollar

import io.digibyte.digidollar.EcOps
import io.digibyte.digidollar.MintBuilder
import io.digibyte.digidollar.RedemptionBuilder

/**
 * The Redemption flow glue (issue #13): policy checks (testnet gate, fresh
 * softfork status, position-unlocked), DD-token burn selection covering the
 * position's minted cents, a single DGB fee input, [RedemptionBuilder]
 * assembly, then the sign→broadcast→persist tail — the mirror of [MintService].
 *
 * Redemption is USER-KEY-ONLY: unlike Mint it carries no oracle price into the
 * transaction (the redeem witness needs no oracle signatures —
 * docs/discovery/regtest-oracle-findings.md), so there is no divergence check.
 * The only price-independent gate is that the position's timelock has expired.
 *
 * Owner keys are ALWAYS on the wallet's watched BIP86 chain m/86'/20' (both
 * networks — [MintService.OWNER_KEY_COIN_TYPE]). The wallet only ever mints
 * with owner index (0,0), so every DD token and every collateral position sit
 * at that one key; [collateralOwnerPath] and each burn path therefore use the
 * position's index. [DigiDollarTxSigner.signRedemption] verifies each burn's
 * signature against the key its prevout actually pays, so a token at an
 * unexpected key aborts the send rather than losing funds.
 *
 * Wallet/native surfaces enter through constructor seams so the flow is
 * JVM-testable; production wiring binds them to NativeBridge,
 * DigiDollarTxSigner, Broadcaster, and WalletTxPersister in the DI layer.
 */
class RedemptionService(
    private val wallet: WalletPort,
    private val statusClient: DigiDollarStatusClient,
    private val gate: DigiDollarGate,
    private val signRedemption: (
        RedemptionBuilder.UnsignedRedemption,
        DigiDollarTxSigner.OwnerKeyPath,
        List<DigiDollarTxSigner.OwnerKeyPath>,
    ) -> ByteArray,
    private val broadcast: (ByteArray) -> String?,
    private val persist: () -> Unit,
    /** Activity-list record (txid, collateralReturnedSats, feeSats,
     *  collateralReturnAddress) — best-effort, post-broadcast. */
    private val recordOutgoing: (String, Long, Long, String) -> Unit,
    private val ecOps: EcOps,
    /** Hard testnet gate: Redemption is testnet-only until the 4.0.0 unlock. */
    private val testnet: Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The wallet surfaces the flow needs, seamed over NativeBridge. */
    interface WalletPort {
        /** Raw [io.digibyte.core.bridge.NativeBridge.listDigiDollarUtxos] lines
         *  (`txid:vout:cents:scriptPubKeyHex`). */
        fun listDigiDollarUtxos(): String

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

    /**
     * The collateral position being redeemed. Sourced by the caller (issue #10
     * Positions, `listdigidollarpositions`); the Redemption flow only assembles
     * and sends. [ownerChain]/[ownerIndex] identify the BIP86 owner key that
     * shaped the Mint (coin type is always [MintService.OWNER_KEY_COIN_TYPE]).
     */
    data class CollateralPosition(
        val txidHex: String,
        val vout: Int,
        val valueSats: Long,
        val lockHeight: Int,
        val ddCents: Long,
        val ownerChain: Int = 0,
        val ownerIndex: Int = 0,
    )

    sealed class RedemptionResult {
        data class Success(
            val txid: String,
            val collateralReturnedSats: Long,
            val ddChangeCents: Long,
        ) : RedemptionResult()

        /** Policy said no (gate closed, position still locked) — retry later. */
        data class Blocked(val reason: String) : RedemptionResult()

        /** The attempt itself failed (selection, signing, broadcast). */
        data class Error(val message: String) : RedemptionResult()
    }

    suspend fun redeem(position: CollateralPosition): RedemptionResult {
        if (!inFlight.compareAndSet(false, true)) {
            return RedemptionResult.Error(
                "A Redemption is already in progress — concurrent Redemptions " +
                    "would double-spend the fee coin",
            )
        }
        try {
            return redeemLocked(position)
        } finally {
            inFlight.set(false)
        }
    }

    @Suppress("ReturnCount", "LongMethod") // sequential guard clauses ARE the flow
    private suspend fun redeemLocked(position: CollateralPosition): RedemptionResult {
        if (!testnet) {
            return RedemptionResult.Blocked(
                "DigiDollar Redemption is testnet-only until the 4.0.0 mainnet release",
            )
        }
        val status = statusClient.fetchStatus(testnet)
            ?: return RedemptionResult.Blocked("DigiDollar status unavailable — Redemption blocked")
        gate.record(status.deployment, nowMs())
        if (status.deployment != DigiDollarDeployment.ACTIVE) {
            return RedemptionResult.Blocked("DigiDollar softfork is not active on this network")
        }

        // The redeem tx sets nLockTime = the Mint's unlock height; the network
        // must have reached it or the node rejects the spend as non-final. A
        // stale local view could also select an already-spent fee/DD coin, so
        // require the synced tip to be current before touching UTXOs.
        val tipHeight = wallet.tipHeight()
        val networkHeight = wallet.estimatedTipHeight()
        if (tipHeight <= 0 || networkHeight <= 0 || tipHeight < networkHeight - MintService.MAX_TIP_LAG_BLOCKS) {
            return RedemptionResult.Error(
                "Wallet is still syncing (block $tipHeight of $networkHeight) — " +
                    "wait for sync to finish before Redeeming",
            )
        }
        if (networkHeight < position.lockHeight) {
            return RedemptionResult.Blocked(
                "Position is still time-locked — unlocks at block ${position.lockHeight}, " +
                    "chain at $networkHeight",
            )
        }

        val changeAddress = wallet.changeAddress()
            ?: return RedemptionResult.Error("Could not derive a change address")
        val changeScriptHex = wallet.addressToScriptPubKey(changeAddress)?.toHex()
            ?: return RedemptionResult.Error("Could not resolve the change address script")
        if (!changeScriptHex.startsWith("0014")) {
            return RedemptionResult.Error("Change address is not native P2WPKH: $changeAddress")
        }
        val ownerKey = wallet.deriveOwnerKey(MintService.OWNER_KEY_COIN_TYPE, position.ownerChain, position.ownerIndex)
            ?: return RedemptionResult.Error("Owner key derivation failed — wallet locked?")

        val unsigned: RedemptionBuilder.UnsignedRedemption
        val signed: ByteArray
        val burnCount: Int
        try {
            // DD tokens are fungible cents; burn largest-first until the
            // position's minted amount is covered. Full redemption only —
            // RedemptionBuilder requires the burn to meet or exceed ddCents.
            val ddUtxos = UtxoLines.parse(wallet.listDigiDollarUtxos())
                .filter { it.scriptPubKeyHex.length == 68 && it.scriptPubKeyHex.startsWith("5120") }
                .sortedByDescending { it.amount }
            val burns = mutableListOf<UtxoLines.Utxo>()
            var burned = 0L
            for (u in ddUtxos) {
                if (burned >= position.ddCents) break
                burns.add(u)
                burned += u.amount
            }
            if (burned < position.ddCents) {
                return RedemptionResult.Error(
                    "Not enough DigiDollar to redeem this position " +
                        "(${position.ddCents} cents needed, $burned available)",
                )
            }
            burnCount = burns.size

            // Single P2WPKH fee input, largest-first — mirrors Mint funding.
            val feeUtxo = UtxoLines.parse(wallet.listWalletUtxos())
                .filter { it.scriptPubKeyHex.length == 44 && it.scriptPubKeyHex.startsWith("0014") }
                .maxByOrNull { it.amount }
                ?.takeIf { it.amount >= REDEEM_FEE_SATS }
                ?: return RedemptionResult.Error(
                    "No spendable coin covers the Redemption fee " +
                        "($REDEEM_FEE_SATS sats) — add DGB and retry",
                )

            unsigned = RedemptionBuilder.buildUnsigned(
                collateralUtxo = RedemptionBuilder.CollateralUtxo(
                    position.txidHex,
                    position.vout,
                    position.valueSats,
                    position.lockHeight,
                    position.ddCents,
                ),
                ddUtxos = burns.map {
                    RedemptionBuilder.DdTokenUtxo(it.txidHex, it.vout, it.amount, it.scriptPubKeyHex)
                },
                feeUtxo = RedemptionBuilder.FeeUtxo(
                    feeUtxo.txidHex,
                    feeUtxo.vout,
                    feeUtxo.amount,
                    feeUtxo.scriptPubKeyHex,
                ),
                feeSats = REDEEM_FEE_SATS,
                ownerKeyHex = ownerKey.toHex(),
                collateralReturnScriptPubKeyHex = changeScriptHex,
                dgbChangeScriptPubKeyHex = changeScriptHex,
                ecOps = ecOps,
            )

            val ownerPath = DigiDollarTxSigner.OwnerKeyPath(
                MintService.OWNER_KEY_COIN_TYPE,
                position.ownerChain,
                position.ownerIndex,
            )
            signed = signRedemption(unsigned, ownerPath, List(burnCount) { ownerPath })
        } catch (e: IllegalArgumentException) {
            return RedemptionResult.Error(e.message ?: "Redemption assembly failed")
        } catch (e: IllegalStateException) {
            return RedemptionResult.Error(e.message ?: "Redemption signing failed")
        }

        val txid = broadcast(signed)
            ?: return RedemptionResult.Error("Failed to broadcast the Redemption transaction")
        recordOutgoing(txid, position.valueSats, REDEEM_FEE_SATS, changeAddress)
        persist()
        return RedemptionResult.Success(txid, position.valueSats, unsigned.ddChangeCents)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        /** Same flat DD fee the Mint uses; above the 0.1 DGB consensus floor. */
        const val REDEEM_FEE_SATS = MintBuilder.DEFAULT_FEE_SATS
    }
}
