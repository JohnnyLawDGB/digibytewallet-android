package io.digibyte.ui.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class DigiDollarFormatTest {
    @Test fun formatsCentsAsUsd() {
        assertEquals("$50.00", WalletViewModel.formatDigiDollar(5000))
        assertEquals("$1.23", WalletViewModel.formatDigiDollar(123))
        assertEquals("$0.09", WalletViewModel.formatDigiDollar(9))
        assertEquals("$0.00", WalletViewModel.formatDigiDollar(0))
        assertEquals("$1,234.56", WalletViewModel.formatDigiDollar(123456))
    }
}
