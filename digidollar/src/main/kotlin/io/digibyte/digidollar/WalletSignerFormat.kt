package io.digibyte.digidollar

/**
 * Serialization for hand-off to the C wallet signer (BRTransactionParse →
 * BRWalletSignTransaction): breadwallet's unsigned-input wire format puts
 * the prevout scriptPubKey in the scriptSig slot followed by a non-standard
 * 8-byte LE prevout amount — that is how the parser recovers the address
 * the signer matches against the wallet's key chains and the amount the
 * BIP143 digest commits to. Inputs not listed in [unsignedInputs] serialize
 * normally (their scriptSig and any attached witness pass through), so a
 * partially-signed Redemption keeps its taproot stacks while the fee input
 * travels in unsigned form.
 *
 * The parser detects the unsigned form by deriving an address from the
 * scriptSig slot, so each unsigned prevout script must be address-bearing
 * (P2WPKH `0014…` in every DigiDollar shape).
 */
object WalletSignerFormat {

    /** Serialize [tx] with the inputs in [unsignedInputs] (index → spent
     *  prevout) in breadwallet's unsigned form. */
    fun serialize(tx: Transaction, unsignedInputs: Map<Int, TxOutput>): ByteArray {
        require(unsignedInputs.isNotEmpty()) { "no unsigned inputs — nothing for the wallet signer" }
        for ((i, prevout) in unsignedInputs) {
            require(i in tx.inputs.indices) { "unsigned input index $i out of range" }
            require(tx.witnesses.getOrElse(i) { emptyList() }.isEmpty()) {
                "input $i already carries a witness — it is signed"
            }
            require(tx.inputs[i].scriptSigHex.isEmpty()) { "input $i already carries a scriptSig" }
            require(prevout.scriptPubKeyHex.isNotEmpty()) { "input $i prevout script missing" }
        }

        val hasWitness = tx.witnesses.any { it.isNotEmpty() }
        val out = ByteBuilder()
        out.int32le(tx.version)
        if (hasWitness) {
            out.byte(0x00) // marker
            out.byte(0x01) // flag
        }
        out.varInt(tx.inputs.size.toLong())
        for ((i, input) in tx.inputs.withIndex()) {
            out.bytes(input.txidHex.hexToByteArray().reversedArray())
            out.int32le(input.vout)
            val prevout = unsignedInputs[i]
            if (prevout != null) {
                val spk = prevout.scriptPubKeyHex.hexToByteArray()
                out.varInt(spk.size.toLong())
                out.bytes(spk)
                out.int64le(prevout.valueSats)
            } else {
                val scriptSig = input.scriptSigHex.hexToByteArray()
                out.varInt(scriptSig.size.toLong())
                out.bytes(scriptSig)
            }
            out.int32le(input.sequence.toInt())
        }
        out.varInt(tx.outputs.size.toLong())
        for (output in tx.outputs) {
            out.int64le(output.valueSats)
            val spk = output.scriptPubKeyHex.hexToByteArray()
            out.varInt(spk.size.toLong())
            out.bytes(spk)
        }
        if (hasWitness) {
            for (i in tx.inputs.indices) {
                val stack = tx.witnesses.getOrElse(i) { emptyList() }
                out.varInt(stack.size.toLong())
                for (element in stack) {
                    out.varInt(element.size.toLong())
                    out.bytes(element)
                }
            }
        }
        out.int32le(tx.locktime.toInt())
        return out.build()
    }
}
