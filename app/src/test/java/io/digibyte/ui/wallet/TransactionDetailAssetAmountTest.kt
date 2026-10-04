package io.digibyte.ui.wallet

import io.digibyte.core.asset.AssetTxAmount
import io.digibyte.ui.components.TxKind
import io.digibyte.ui.components.TypedAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which amount the details screen leads with: the pure half of TransactionDetailAssetAmountGateTest. */
class TransactionDetailAssetAmountTest {

    @Test fun `the details lead with the asset amount only for an asset transaction`() {
        val amount = AssetTxAmount(units = 1, decimals = 0, label = "CHANG", name = "Chang Coin")
        assertEquals(amount, assetAmountForDetail(TxKind.DIGIASSET, TypedAmount.Asset(amount)))
        assertNull(assetAmountForDetail(TxKind.DIGIASSET, null))
        assertNull(assetAmountForDetail(TxKind.DGB, TypedAmount.Asset(amount)))
        assertNull(assetAmountForDetail(TxKind.DIGIDOLLAR, TypedAmount.Formatted("$1.00")))
        assertNull(assetAmountForDetail(null, TypedAmount.Asset(amount)))
    }
}
