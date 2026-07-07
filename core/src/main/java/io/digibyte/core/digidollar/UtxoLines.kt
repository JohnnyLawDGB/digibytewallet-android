package io.digibyte.core.digidollar

/**
 * Parser for the native UTXO listings that feed the Kotlin-built DigiDollar
 * flows (ADR-0001). Both [io.digibyte.core.bridge.NativeBridge.listWalletUtxos]
 * and [io.digibyte.core.bridge.NativeBridge.listDigiDollarUtxos] emit one UTXO
 * per line as `txid:vout:amount:scriptPubKeyHex` (jni_wallet.c
 * `_utxo_append_line`). [amount] is satoshis for wallet DGB coins and USD cents
 * for DigiDollar token outputs — the caller interprets it per listing.
 *
 * Shared by [MintService] (funding selection) and [RedemptionService] (DD-burn
 * and fee selection) so the wire format is parsed in exactly one place.
 */
internal object UtxoLines {

    data class Utxo(
        val txidHex: String,
        val vout: Int,
        /** Satoshis for wallet coins; USD cents for DigiDollar token outputs. */
        val amount: Long,
        val scriptPubKeyHex: String,
    )

    /** @throws IllegalArgumentException on a malformed line (mapped by callers
     *  to a typed error rather than a crash). */
    fun parse(raw: String): List<Utxo> =
        raw.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(":")
            require(parts.size == 4) { "malformed UTXO line from the native listing: $line" }
            Utxo(parts[0], parts[1].toInt(), parts[2].toLong(), parts[3])
        }.toList()
}
