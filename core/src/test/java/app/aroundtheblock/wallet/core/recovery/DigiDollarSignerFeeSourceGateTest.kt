package app.aroundtheblock.wallet.core.recovery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate (DD-FEE-CEIL): the DigiDollar move hands the native signer the rate and the fee of the
 * plan it is signing, and nothing else in their place.
 *
 * The native call cannot run on a JVM, so what the service passes it is pinned by shape here. The
 * native side of the same two figures is pinned by dd_transfer_guard_kat; the plan's own figures by
 * DigiDollarTransferPlanTest and DigiDollarTransferServiceTest.
 */
class DigiDollarSignerFeeSourceGateTest {

    private fun code(path: String): String =
        File(path).readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines().joinToString("\n") { it.substringBefore("//") }

    private val service by lazy {
        code("src/main/java/app/aroundtheblock/wallet/core/recovery/DigiDollarTransferService.kt")
    }

    /** The argument list of the one native signing call. */
    private val call by lazy {
        val calls = Regex("""NativeBridge\.buildAndSignForeignDigiDollarTransfer\(([^;]*?)\n\s*\)""")
            .findAll(service).map { it.groupValues[1] }.toList()
        assertEquals("the DigiDollar move signs through one native call", 1, calls.size)
        calls.single()
    }

    private fun argument(name: String): List<String> =
        Regex("""\b$name\s*=\s*([^\n]*?),?\s*(?:\n|$)""").findAll(call).map { it.groupValues[1].trim() }.toList()

    @Test fun `the signer is handed the rate the plan was sized at`() {
        assertEquals(listOf("plan.feePerKb"), argument("feePerKb"))
    }

    @Test fun `the signer is handed the fee of the plan as the approved fee`() {
        assertEquals(listOf("plan.feeSat"), argument("approvedMaxFeeSats"))
    }

    /** The approved fee and what the transfer spends and returns come from the same plan. */
    @Test fun `the amounts and the change the signer is handed are that plan's`() {
        assertEquals(listOf("plan.feeInputs.map { it.amountSat }.toLongArray()"), argument("feeAmounts"))
        assertEquals(listOf("plan.changeAmountSat"), argument("changeAmount"))
    }

    @Test fun `the plan that is signed is the plan that was built, through the one signer`() {
        assertTrue(
            "the service signs with nativeSign unless a test replaces it",
            Regex("""private val sign:[^=]*->\s*String\?\s*=\s*::nativeSign""").containsMatchIn(service),
        )
        assertEquals(1, Regex("""\bsign\(plan,\s*seedBytes,\s*profileOf\)""").findAll(service).count())
        assertEquals(
            "one signing call in the service",
            1, Regex("""(?<![\w.])sign\(""").findAll(service).count(),
        )
        assertEquals(1, Regex("""is DigiDollarTransferPlan\.Result\.Ok\s*->\s*planned\.plan""").findAll(service).count())
    }
}
