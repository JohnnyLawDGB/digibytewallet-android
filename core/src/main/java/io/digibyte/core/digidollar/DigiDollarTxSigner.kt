package io.digibyte.core.digidollar

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.digidollar.MintBuilder
import io.digibyte.digidollar.RedemptionBuilder
import io.digibyte.digidollar.RedemptionSigning
import io.digibyte.digidollar.TxOutput
import io.digibyte.digidollar.WalletSignerFormat

/**
 * Signing orchestration for Kotlin-built DigiDollar transactions (ADR-0001:
 * Kotlin builds the bytes, C signs). Every P2WPKH input (Mint funding,
 * Redemption fee) is signed by the existing wallet signer — the tx travels
 * to [NativeBridge.signTransaction] in breadwallet's unsigned-input format
 * ([WalletSignerFormat]) so BRWalletSignTransaction recovers the prevout
 * address and amount with its production key derivation. Taproot inputs are
 * signed digest-by-digest through [NativeBridge.ddSignDigest] and
 * self-verified against the key the prevout actually pays before broadcast.
 *
 * Both paths end with a witness-strip parity check: the wallet signer must
 * return OUR transaction plus witnesses — any structural drift (input order,
 * outputs, version, locktime) aborts the send.
 *
 * All failures throw [IllegalStateException] with a reason; callers map them
 * to user-facing errors. Broadcast stays with the existing
 * [io.digibyte.core.dandelion.Broadcaster].
 */
object DigiDollarTxSigner {

    /** BIP86 Owner-key derivation m/86'/coinType'/0'/chain/index. */
    data class OwnerKeyPath(val coinType: Int, val chain: Int, val index: Int)

    /**
     * Sign a Mint: the single funding input is the wallet's P2WPKH UTXO
     * described by [fundingPrevout] (its scriptPubKey + amount, from
     * [NativeBridge.listWalletUtxos]). Returns the fully-signed tx bytes.
     */
    fun signMint(unsigned: MintBuilder.UnsignedMint, fundingPrevout: TxOutput): ByteArray {
        val bytes = WalletSignerFormat.serialize(unsigned.tx, mapOf(0 to fundingPrevout))
        val signed = checkNotNull(NativeBridge.signTransaction(bytes)) {
            "wallet signer rejected the Mint (locked session or foreign funding input)"
        }
        checkStrippedParity(unsigned.tx.serialize(), signed)
        return signed
    }

    /**
     * Sign a Redemption: vin0 script-path with the untweaked Owner key at
     * [collateralOwnerPath], each DD burn key-path with the tap-tweaked key
     * at its entry in [burnPaths] (aligned with the builder's ddUtxos order),
     * and the fee input via the wallet signer. Every Schnorr signature is
     * verified against the key its prevout pays before the transaction
     * leaves this function.
     */
    fun signRedemption(
        unsigned: RedemptionBuilder.UnsignedRedemption,
        collateralOwnerPath: OwnerKeyPath,
        burnPaths: List<OwnerKeyPath>,
    ): ByteArray {
        val inputCount = unsigned.tx.inputs.size
        require(burnPaths.size == inputCount - 2) {
            "need one Owner-key path per DD burn (${inputCount - 2}), got ${burnPaths.size}"
        }
        val ownerKey = checkNotNull(
            NativeBridge.ddDeriveOwnerKey(
                collateralOwnerPath.coinType,
                collateralOwnerPath.chain,
                collateralOwnerPath.index,
            ),
        ) { "Owner key derivation failed — locked session?" }

        val witnessed = RedemptionSigning.witnessedTx(unsigned, schnorrSign = { i, digest, tapTweak ->
            val path = if (i == 0) collateralOwnerPath else burnPaths[i - 1]
            val sig = checkNotNull(
                NativeBridge.ddSignDigest(digest, path.coinType, path.chain, path.index, tapTweak),
            ) { "native signer failed for input $i — locked session?" }
            // Script-path (vin0) verifies under the UNTWEAKED Owner key; a
            // key-path burn must verify under the x-only key its prevout
            // pays — this catches a burn UTXO sitting at a key the given
            // path cannot sign for, before anything is broadcast.
            val expectedKey = if (i == 0) ownerKey else prevoutOutputKey(unsigned, i)
            check(NativeBridge.ddSchnorrVerify(expectedKey, digest, sig)) {
                "input $i signature does not verify under its prevout key " +
                    "(wrong Owner-key path for this UTXO?)"
            }
            sig
        })

        val feeIndex = inputCount - 1
        val bytes = WalletSignerFormat.serialize(
            witnessed,
            mapOf(feeIndex to unsigned.prevouts[feeIndex]),
        )
        val signed = checkNotNull(NativeBridge.signTransaction(bytes)) {
            "wallet signer rejected the Redemption fee input (locked session or foreign UTXO)"
        }
        checkStrippedParity(unsigned.tx.serialize(), signed)
        return signed
    }

    /** X-only output key from a `5120…` P2TR prevout script. */
    private fun prevoutOutputKey(
        unsigned: RedemptionBuilder.UnsignedRedemption,
        inputIndex: Int,
    ): ByteArray {
        val spk = unsigned.prevouts[inputIndex].scriptPubKeyHex
        check(spk.length == 68 && spk.startsWith("5120")) {
            "input $inputIndex prevout is not P2TR: $spk"
        }
        return spk.substring(4).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * The signed transaction must be OUR bytes plus witnesses: same version,
     * then the BIP144 marker+flag, then the identical input/output body
     * (P2WPKH scriptSigs stay empty under segwit), witnesses, and the same
     * locktime last.
     */
    private fun checkStrippedParity(unsignedHex: String, signed: ByteArray) {
        val signedHex = signed.joinToString("") { "%02x".format(it) }
        val version = unsignedHex.take(8)
        val body = unsignedHex.substring(8, unsignedHex.length - 8)
        val locktime = unsignedHex.takeLast(8)
        check(
            signedHex.startsWith(version + "0001" + body) &&
                signedHex.endsWith(locktime) &&
                signedHex.length > unsignedHex.length + 4,
        ) { "wallet signer returned a structurally different transaction — aborting" }
    }
}
