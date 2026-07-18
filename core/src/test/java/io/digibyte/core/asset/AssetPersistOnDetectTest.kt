package io.digibyte.core.asset

import io.digibyte.core.WalletTxPersister
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.ipfs.AssetMetadataService
import io.digibyte.core.model.AssetOperation
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * Persist-on-detect tests (task 6 / C6) for [AssetManager.maybePersistAfterDetect].
 *
 * ## Why this drives the extracted seam rather than the public
 * `processIncomingAssetTx`
 *
 * The brief's original skeleton called `processIncomingAssetTx` with
 * `mockkObject(NativeBridge)` stubbing `getTransactionOutputsForHash` /
 * `getTransactionInputsForHash` / `deriveIssuanceAssetId`. That does not run
 * on the host JVM unit-test runner: `NativeBridge` is a JNI object whose
 * `init` block does `System.loadLibrary("core-lib")`, and merely referencing
 * the object (which `mockkObject(NativeBridge)` must do) forces that static
 * initializer to run, throwing `UnsatisfiedLinkError` before any stubbing
 * takes effect. This is the same pre-existing constraint documented on
 * [AssetProvenanceTaggingTest] (task 2) and [AssetSourceFixTest] (task 3), and
 * in `WalletManagerSavedTransactionsDecodeTest` / `WalletWipeTest`.
 *
 * So the persist *decision* is extracted into its own seam,
 * [AssetManager.maybePersistAfterDetect], which touches nothing but the
 * injected [WalletTxPersister] — no `NativeBridge` involved. It's exercised
 * directly with a real (not mocked) [IncomingAssetInfo] standing in for "an
 * asset tx was detected", and `null` standing in for "it wasn't".
 */
class AssetPersistOnDetectTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaSvc = mockk<AssetMetadataService>(relaxed = true)
    private val persister = mockk<WalletTxPersister>(relaxed = true)
    private lateinit var mgr: AssetManager

    private val detected = IncomingAssetInfo(
        header = DecodedAssetHeader(
            version = 2,
            opcode = 0,
            operation = AssetOperation.TRANSFER,
            metadataHash = null,
            metadataCid = null,
            totalQuantity = null,
            divisibility = 0,
            locked = false,
            aggregation = Aggregation.AGGREGATABLE,
            transferInstructions = emptyList(),
        ),
        assetId = "La1",
    )

    @Before fun setup() {
        mgr = AssetManager(utxoDao, txDao, metaDao, metaSvc, walletTxPersister = persister)
    }

    @Test fun persist_called_on_detect_receive_path() {
        mgr.maybePersistAfterDetect(persistAfterDetect = true, detected = detected)
        verify { persister.persist() }
    }

    @Test fun sweep_path_does_not_persist() {
        mgr.maybePersistAfterDetect(persistAfterDetect = false, detected = detected)
        verify(exactly = 0) { persister.persist() }
    }

    @Test fun non_asset_tx_does_not_persist_even_when_flagged() {
        // A received tx that wasn't actually a DigiAsset tx (detection
        // returned null) must not trigger a wallet-state snapshot.
        mgr.maybePersistAfterDetect(persistAfterDetect = true, detected = null)
        verify(exactly = 0) { persister.persist() }
    }
}
