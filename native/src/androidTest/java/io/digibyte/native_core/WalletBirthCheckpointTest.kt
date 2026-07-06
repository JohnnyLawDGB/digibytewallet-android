package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression lock for the fresh-wallet sync anchor.
 *
 * `createWalletFromBytes` must stamp the wallet creation time to "now" so a
 * brand-new wallet (which has no history before it was created) anchors its
 * SPV + BIP158 sync to the NEWEST hardcoded checkpoint rather than re-scanning
 * a fixed, ever-growing span of history.
 *
 * Prior bug: `g_walletCreationTime` was hardcoded to 2025-02-01, which pinned
 * every new wallet to the 20,500,000 checkpoint — ~3.17M blocks (~1.5 years)
 * of pointless catch-up by mid-2026, growing daily. With `time(NULL)` the
 * checkpoint back-off anchors a wallet created now far above 23M.
 */
@RunWith(AndroidJUnit4::class)
class WalletBirthCheckpointTest {
    // Canonical BIP39 all-zeros test vector — a valid mnemonic, not a real
    // wallet. The anchor depends on creation time + compiled checkpoints, not
    // on which seed this is.
    private val MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about"

    @Test
    fun freshWalletAnchorsToRecentCheckpoint() {
        val phrase = MNEMONIC.toByteArray()
        val ok = NativeBridge.createWalletFromBytes(phrase)
        phrase.fill(0)
        assertTrue("createWalletFromBytes must succeed", ok)

        val anchor = NativeBridge.getWalletBirthCheckpointHeight()
        // Was 20_500_000 under the hardcoded 2025-02-01 birthday. A wallet
        // created "now" must anchor to a recent checkpoint (well above 23M),
        // proving the fixed-date regression is gone.
        assertTrue(
            "fresh wallet must anchor to a recent checkpoint (>23,000,000), got $anchor",
            anchor > 23_000_000L
        )
    }
}
