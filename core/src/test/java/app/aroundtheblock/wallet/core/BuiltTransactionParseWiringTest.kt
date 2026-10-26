package app.aroundtheblock.wallet.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The send review reads the outputs of the wallet's own UNSIGNED draft through the parser that
 * accepts that form (4.1.2 Wave 10, Note 8, minified build).
 *
 * Row 9 made `getRawTransactionOutputs` parse the network's signed format only — right for bytes
 * from a peer, node or indexer, and its own note says the builder's bytes keep the inferring parse.
 * But `TransactionBuilder.Bridge.transactionOutputs` handed it the draft `createTransaction` had
 * just serialized, in which each input carries its scriptPubKey and amount where the signature will
 * go. The signed-only parse returned NULL and every DGB send stopped at review: "Transaction not
 * sent". The JVM tests drive [TransactionBuilder] through a fake, so none of them could see which
 * bridge call the production seam makes — this gate is that check.
 */
class BuiltTransactionParseWiringTest {

    private val src = File("src/main/java/app/aroundtheblock/wallet/core/TransactionBuilder.kt").readLines()
        .map { it.substringBefore("//") }.joinToString("\n")

    @Test fun `the send review parses its own draft with the builder-format reader`() {
        val seam = Regex("""override fun transactionOutputs\(tx: ByteArray\)[^\n]*""").find(src)?.value
        assertTrue("TransactionBuilder.Bridge overrides transactionOutputs", seam != null)
        assertTrue("the draft is read by getBuiltTransactionOutputs, not the signed-only reader: $seam",
            seam!!.contains("NativeBridge.getBuiltTransactionOutputs(tx)"))
    }
}
