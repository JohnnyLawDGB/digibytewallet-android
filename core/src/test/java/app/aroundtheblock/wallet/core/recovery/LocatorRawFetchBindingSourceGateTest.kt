package app.aroundtheblock.wallet.core.recovery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate for DD-LOCATOR-BIND: raw transaction bytes reach the DigiDollar locator through one
 * place, which compares their own id with the txid asked for.
 *
 * The behaviour is tested in [LocatorRawFetchBindingTest]; this pins the shape, so a further raw
 * fetch cannot be added beside the bound one unnoticed.
 */
class LocatorRawFetchBindingSourceGateTest {

    private val root = "src/main/java/app/aroundtheblock/wallet/core"

    private fun code(file: File): String =
        file.readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines().joinToString("\n") { it.substringBefore("//") }

    private val source by lazy { code(File("$root/recovery/UtxoSource.kt")) }

    /** The backend implementation's body: everything from its class header on. */
    private val backend by lazy {
        val at = source.indexOf("class ReconcileBackendUtxoSource")
        assertTrue("ReconcileBackendUtxoSource is in UtxoSource.kt", at >= 0)
        source.substring(at)
    }

    /** Text of `fun name(...)` up to the next member declaration. */
    private fun body(name: String): String {
        val start = Regex("""fun\s+$name\s*\(""").find(backend) ?: error("no fun $name in ReconcileBackendUtxoSource")
        val rest = backend.substring(start.range.last)
        val next = Regex("""\n\s*(override\s+|private\s+|internal\s+)?(suspend\s+)?fun\s""").find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    @Test fun `raw bytes enter the locator source in one place, which compares their id`() {
        val fetches = Regex("""\.fetchRawTransaction\(|\.fetchRawTx\(""").findAll(backend).count()
        assertEquals("one raw fetch in ReconcileBackendUtxoSource", 1, fetches)
        val bound = body("boundRawTransaction")
        assertTrue("the one fetch is inside boundRawTransaction", bound.contains("nodeClient.fetchRawTransaction(txid)"))
        assertTrue(
            "the id is computed from the bytes",
            Regex("""val id\s*=\s*runCatching\s*\{\s*txidOf\(raw\)\s*\}\.getOrNull\(\)\s*\?:\s*return null""").containsMatchIn(bound),
        )
        assertTrue(
            "bytes are handed on only when that id is the txid asked for",
            Regex("""return raw\.takeIf\s*\{\s*id\.equals\(txid,\s*ignoreCase\s*=\s*true\)\s*\}""").containsMatchIn(bound),
        )
        assertEquals("no other return of the bytes", 1, Regex("""\breturn raw\b""").findAll(bound).count())
    }

    @Test fun `every fetch by txid reads only bound bytes`() {
        val byTxid = Regex("""fun\s+(\w+)\s*\(\s*txid:\s*String\s*\)""").findAll(backend)
            .map { it.groupValues[1] }.filter { it != "boundRawTransaction" }.toList()
        assertEquals(
            "the fetches by txid (a new one is listed here once it is bound)",
            listOf("fetchOutputs", "fetchVersion", "fetchInputs"), byTxid,
        )
        for (name in byTxid) {
            val b = body(name)
            assertTrue(
                "$name starts from the bound bytes",
                Regex("""^[^{]*\{\s*val raw\s*=\s*boundRawTransaction\(txid\)\s*\?:\s*return null""").containsMatchIn(b),
            )
            assertEquals("$name has one source of bytes", 1, Regex("""\bval raw\b""").findAll(b).count())
        }
    }

    @Test fun `the readers of raw bytes are fed the bound bytes only`() {
        val readers = Regex("""\b(outputLines|inputLines|rawTxVersion|txidOf)\(([^)]*)\)""").findAll(backend)
            .map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        assertEquals(
            listOf("txidOf" to "raw", "outputLines" to "raw", "rawTxVersion" to "raw", "inputLines" to "raw"),
            readers,
        )
    }

    @Test fun `production wiring computes the id with the shared id function`() {
        assertTrue(Regex("""txidOf\s*=\s*RawTxBinding\.transactionId\s*,""").containsMatchIn(backend))
        val shared = code(File("$root/recovery/RawTxBinding.kt"))
        assertTrue(
            Regex("""val transactionId:\s*\(ByteArray\)\s*->\s*String\?\s*=\s*\{\s*NativeBridge\.rawTransactionId\(it\)\s*\}""")
                .containsMatchIn(shared),
        )
    }

    @Test fun `the scan asks only the source for a transaction's outputs, inputs and version`() {
        val scan = code(File("$root/recovery/RecoveryScanService.kt"))
        assertTrue(Regex("""outputsFor\s*=\s*\{\s*utxoSource\.fetchOutputs\(it\)\s*\}""").containsMatchIn(scan))
        assertTrue(Regex("""inputsFor\s*=\s*\{\s*utxoSource\.fetchInputs\(it\)\s*\}""").containsMatchIn(scan))
        assertTrue(Regex("""versionFor\s*=\s*\{\s*utxoSource\.fetchVersion\(it\)\s*\}""").containsMatchIn(scan))
    }

    /**
     * The node client's two raw-transaction fetches return bytes as served. Each main-source file
     * that calls one is listed here with the number of calls, so a new caller has to be looked at:
     * it either goes through the bound source or compares the id itself.
     */
    @Test fun `every caller of the node client's raw fetches is a known, bound one`() {
        val call = Regex("""\.(fetchRawTransaction|fetchRawTx)\(""")
        val callers = sequenceOf(File("src/main/java"), File("../app/src/main/java"))
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to call.findAll(code(it)).count() }
            .filter { it.second > 0 }
            .sortedBy { it.first }.toList()
        assertEquals(
            listOf("ChainReconciliationService.kt" to 3, "UtxoSource.kt" to 1),
            callers,
        )
    }

    // ---- a raw fetch under a new name -------------------------------------------------------
    //
    // The tests above find a raw fetch by the names it has today. These three pin the whole of
    // what the source declares and asks the node client for, and the whole of what the node client
    // can hand back as a transaction, so a fetch under another name has to be listed here, after
    // it is bound.

    @Test fun `the locator source declares these functions and no others`() {
        val declared = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(""").findAll(backend)
            .map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("fetchUtxos", "fetchDigiDollar", "boundRawTransaction", "fetchOutputs", "fetchVersion", "fetchInputs"),
            declared,
        )
        assertEquals("every declaration was read by name", Regex("""\bfun\b""").findAll(backend).count(), declared.size)
        assertEquals(
            "ReconcileBackendUtxoSource is the only class in UtxoSource.kt",
            1, Regex("""\b(class|object)\s+\w""").findAll(source).count(),
        )
    }

    @Test fun `the locator source asks the node client for these three things and no others`() {
        val calls = Regex("""\bnodeClient\s*\??\.\s*(\w+)\s*\(""").findAll(backend).map { it.groupValues[1] }.toList()
        assertEquals(listOf("reconcileAddresses", "digiDollarHolding", "fetchRawTransaction"), calls)
        // The property, the production constructor's parameter and its hand-over (twice on one
        // line) are the only other mentions: the client is not passed on or reached another way.
        assertEquals(
            "mentions of nodeClient outside those calls",
            4, Regex("""\bnodeClient\b""").findAll(backend).count() - calls.size,
        )
        assertTrue(Regex("""private val nodeClient:\s*DgbNodeClient\s*,""").containsMatchIn(backend))
        assertEquals("the client is named once as a type in the class header", 2, Regex("""\bDgbNodeClient\b""").findAll(backend).count())
    }

    @Test fun `the node client hands back a transaction through these functions and no others`() {
        val client = code(File("$root/reconcile/DgbNodeClient.kt"))
        // Declared as returning transaction bytes or a raw-transaction entry.
        val returning = Regex("""\bfun\s+(\w+)\s*\([^)]*\)\s*:\s*(?:ByteArray|RawTxEntry)\b""").findAll(client)
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("fetchRawTx", "fetchRawTransaction", "parseRawTxResponse"), returning)
        // Each is mentioned where it is declared, and the parser once more by the fetch that uses
        // it: none is wrapped by a function under another name.
        fun mentions(name: String) = Regex("""\b$name\b""").findAll(client).count()
        assertEquals(1, mentions("fetchRawTransaction"))
        assertEquals(1, mentions("fetchRawTx"))
        assertEquals(2, mentions("parseRawTxResponse"))
        // A transaction's hex is read out of an answer in these places: the two fetches above
        // (one through the parser) and the reconcile answer's parent transactions.
        assertEquals(3, Regex("""\.(?:optString|getString)\(\s*"hex"""").findAll(client).count())
        assertEquals(1, Regex("""\.(?:optString|getString)\(\s*"rawtx"""").findAll(client).count())
        assertEquals("raw-transaction entries are built in two places", 2, Regex("""\bRawTxEntry\(""").findAll(client).count() - 1)
        assertEquals("transaction bytes are built in one place", 1, Regex("""\bByteArray\(""").findAll(client).count())
    }
}
