package io.digibyte.core.asset

import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.digibyte.core.ipfs.AssetMetadataService
import io.digibyte.core.model.AssetOperation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Provenance-tagging tests for the per-output persistence decision (task-2 /
 * C5) that [AssetManager.processIncomingAssetTx] makes for every detected
 * asset UTXO:
 *  - A genuinely new asset outpoint is inserted tagged [AssetSource.NATIVE].
 *  - Re-detecting an outpoint the DB already holds re-tags provenance via
 *    `markAssetSource` ONLY. It must never re-INSERT (which would REPLACE and
 *    reset `spent`/`blockHeight` — the exact data-loss bug this task fixes)
 *    and must never lower an already-resolved `asset_quantity`.
 *
 * ## Why this drives [AssetManager.persistDetectedAssetOutput] directly
 * rather than the public `processIncomingAssetTx`
 *
 * `processIncomingAssetTx`'s prefix (OP_RETURN decode + locked-issuance
 * asset-id derivation) unavoidably calls through the real `NativeBridge`
 * singleton (`NativeBridge.getTransactionOutputsForHash`, etc). `NativeBridge`
 * is a JNI object whose `init` block does `System.loadLibrary("core-lib")`,
 * which is unavailable on the host JVM unit-test runner — confirmed by
 * running this test with `mockkObject(NativeBridge)` as the brief originally
 * specified: it fails with `UnsatisfiedLinkError` before any mocking can take
 * effect, because merely referencing the `NativeBridge` object (to pass it to
 * `mockkObject`) forces the JVM to run its static initializer. This is a
 * pre-existing, documented constraint of this codebase (see
 * `WalletManagerSavedTransactionsDecodeTest` / `WalletWipeTest`, which hit the
 * identical wall and solve it the same way: test the extracted, pure/DAO-only
 * seam instead of the NativeBridge-calling entry point).
 *
 * So `AssetManager.persistDetectedAssetOutput` was extracted (visibility
 * `internal`, not `private`) as exactly the per-output branch under test here
 * — the SAME code `processIncomingAssetTx`'s loop calls in production, not a
 * reimplementation — and this test drives it directly by mocking only
 * [UtxoDao]. `computedQty` is still derived by running a hand-built,
 * decoder-valid DigiAsset v3 ISSUANCE OP_RETURN through the REAL
 * [DigiAssetDecoder] (see [decodedIssuanceQuantity] below), so the "real
 * branch" requirement is satisfied end-to-end except for the NativeBridge JNI
 * hop itself.
 *
 * Fixture byte layout (cf. [DigiAssetDecoder] / [BitReader] and the encoding
 * notes atop DigiAssetDecoderTest.kt):
 *
 *   6a 06            OP_RETURN, push 6 bytes
 *      44 41          "DA" magic
 *      03             version 3
 *      05             opcode 5 (issuance, no metadata/rules)
 *      0a             fixed-precision quantity: 3-bit length=000 (-> 1 byte)
 *                     + 5-bit mantissa 01010 (=10), implicit exponent 0 -> 10
 *      10             issuance flags: divisibility=0, locked=1,
 *                     aggregation=00 (AGGREGATABLE) -> 0b00010000
 *
 * That's exactly 8 bits (the flags byte) left after the quantity field, so
 * the transfer-instruction loop parses zero instructions and decode()
 * succeeds cleanly — independently re-derived against the production
 * BitReader algorithm (readBits/readFixedPrecision) before being pinned here.
 * `totalQuantity=10` is chosen to equal the existing row's `assetQuantity` in
 * the re-tag test, so `computedQty > existingRow.assetQuantity` is false and
 * `updateAssetQuantity` is provably never invoked for that case (not just
 * "never invoked with a lower value").
 */
class AssetProvenanceTaggingTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaSvc = mockk<AssetMetadataService>(relaxed = true)
    private lateinit var mgr: AssetManager

    private val txid = "a".repeat(64)

    // See class doc for the byte-by-byte derivation of this fixture.
    private val issuanceOpReturnHex = "6a06444103050a10"

    @Before fun setup() {
        mgr = AssetManager(utxoDao, txDao, metaDao, metaSvc)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Decodes [issuanceOpReturnHex] through the REAL [DigiAssetDecoder] and
     *  returns its totalQuantity (10), asserting it really is a locked
     *  ISSUANCE header so the fixture doc above stays honest. */
    private fun decodedIssuanceQuantity(): Long {
        val header = DigiAssetDecoder().decode(hexToBytes(issuanceOpReturnHex))
        checkNotNull(header) { "fixture must decode — see class doc for byte layout" }
        check(header.operation == AssetOperation.ISSUANCE) { "fixture must be an ISSUANCE" }
        check(header.locked) { "fixture must be locked to exercise the deriveIssuanceAssetId path" }
        return checkNotNull(header.totalQuantity)
    }

    @Test fun new_owned_output_is_inserted_as_NATIVE() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(txid, 1) } returns null

        mgr.persistDetectedAssetOutput(
            txHashHex = txid,
            vout = 1,
            scriptPubKey = ByteArray(0),
            sats = 6000L,
            blockHeight = 0L,
            placeholderAssetId = "La1234",
            computedQty = decodedIssuanceQuantity(),
        )

        coVerify {
            utxoDao.insertAll(withArg { list ->
                assert(list.any { it.vout == 1 && it.assetSource == AssetSource.NATIVE })
            })
        }
    }

    @Test fun existing_output_is_retagged_not_reinserted() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(txid, 1) } returns UtxoEntity(
            txid = txid, vout = 1, scriptPubKey = ByteArray(0), satoshis = 6000,
            blockHeight = 800000, isAsset = true, assetId = "La1234", assetQuantity = 10,
            spent = false, assetSource = AssetSource.BACKEND
        )

        mgr.persistDetectedAssetOutput(
            txHashHex = txid,
            vout = 1,
            scriptPubKey = ByteArray(0),
            sats = 6000L,
            blockHeight = 0L,
            placeholderAssetId = "La1234",
            computedQty = decodedIssuanceQuantity(),
        )

        coVerify { utxoDao.markAssetSource(txid, 1, AssetSource.NATIVE) }
        coVerify(exactly = 0) { utxoDao.insertAll(any()) }
        coVerify(exactly = 0) { utxoDao.updateAssetQuantity(txid, 1, less(10L)) }
    }
}
