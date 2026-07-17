package io.digibyte.core.asset.send

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DigiAsset marker value against DigiByte 9.26's raised dust policy.
 *
 * DigiByte Core 9.26 raised its dust relay fee to 30,000 sat/kB. Measured
 * against a live 9.26.4 node with `testmempoolaccept` (unsigned probe txs,
 * where dust is evaluated before signature checks), the dust floor per output
 * script type is:
 *
 *   - Legacy P2PKH : 5,460 sat  (5,459 = dust, 5,462 = accepted)
 *   - Segwit P2WPKH: 2,940 sat
 *   - Taproot P2TR : ~3,300 sat
 *
 * A DigiAsset transfer's recipient marker goes to an ARBITRARY address whose
 * script type we don't control, so [DA_MARKER_SATS] must clear the worst case
 * (legacy). The old 700-sat marker was ~8x under dust and every asset send was
 * rejected network-wide with reject-reason "dust" — un-fixable by raising the
 * fee. This test fails loudly if anyone lowers the marker back under dust.
 */
class AssetDustPolicyTest {

    private companion object {
        const val LEGACY_P2PKH_DUST = 5_460L
        const val SEGWIT_P2WPKH_DUST = 2_940L
        const val TAPROOT_P2TR_DUST = 3_300L
    }

    @Test
    fun daMarker_clearsLegacyDustFloor_worstCaseRecipient() {
        assertTrue(
            "DA_MARKER_SATS ($DA_MARKER_SATS) must exceed the legacy P2PKH dust " +
                "floor ($LEGACY_P2PKH_DUST) so a marker to ANY recipient relays",
            DA_MARKER_SATS > LEGACY_P2PKH_DUST
        )
    }

    @Test
    fun daMarker_clearsSegwitAndTaprootDustFloors() {
        assertTrue(DA_MARKER_SATS > SEGWIT_P2WPKH_DUST)
        assertTrue(DA_MARKER_SATS > TAPROOT_P2TR_DUST)
    }

    @Test
    fun daMarker_staysNegligiblySmall_notOverpaying() {
        // Sanity ceiling: a marker is a tiny carrier value, not a payment.
        // Keep it well under 0.001 DGB (100,000 sat) so bumping above dust
        // never silently locks up meaningful value per output.
        assertTrue(DA_MARKER_SATS < 100_000L)
    }
}
