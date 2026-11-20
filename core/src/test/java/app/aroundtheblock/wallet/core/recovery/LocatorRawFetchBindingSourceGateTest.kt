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
}
