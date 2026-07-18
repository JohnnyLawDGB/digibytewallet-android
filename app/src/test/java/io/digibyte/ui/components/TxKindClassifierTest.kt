package io.digibyte.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure classification rules for the activity-list transaction kind. */
class TxKindClassifierTest {

    @Test
    fun assetFlag_takesPrecedence_evenOverADdType() {
        // An asset carrier tx is flagged on the entity; classify it DigiAsset
        // regardless of the DD type value (they never actually co-occur, but
        // asset-first is the defined precedence).
        assertEquals(TxKind.DIGIASSET, classifyTxKind(isAssetTx = true, digiDollarType = 0))
        assertEquals(TxKind.DIGIASSET, classifyTxKind(isAssetTx = true, digiDollarType = 2))
    }

    @Test
    fun nonzeroDdType_isDigiDollar() {
        assertEquals(TxKind.DIGIDOLLAR, classifyTxKind(isAssetTx = false, digiDollarType = 1)) // MINT
        assertEquals(TxKind.DIGIDOLLAR, classifyTxKind(isAssetTx = false, digiDollarType = 2)) // TRANSFER
        assertEquals(TxKind.DIGIDOLLAR, classifyTxKind(isAssetTx = false, digiDollarType = 3)) // REDEEM
    }

    @Test
    fun plainTx_isDgb() {
        assertEquals(TxKind.DGB, classifyTxKind(isAssetTx = false, digiDollarType = 0))
    }
}
