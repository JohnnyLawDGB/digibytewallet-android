package io.digibyte.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing for the `digibyte:` URI — the wallet's entry point for QR codes and deep links,
 * i.e. **entirely untrusted input**.
 *
 * This had no coverage at all before: [DigiByteUri.parse] called `android.net.Uri`, which is
 * a throwing stub on a plain JVM, so no unit test could reach it. The parse is now pure for
 * the same reason `PeerPenaltyPersist` and `CfRecoveryPolicy` are — a security-relevant
 * decision belongs in a unit test, not only in something you can observe on a device.
 *
 * The asset fields extend BIP21. A wallet that does not understand them ignores unknown
 * params and sees a plain DGB request, which fails safe rather than wrong.
 */
class DigiByteUriTest {

    // ── Existing BIP21 behaviour, pinned so the rewrite cannot silently change it ──

    @Test fun a_bare_address_parses_as_an_address() {
        assertEquals("DTest123", DigiByteUri.parse("DTest123")?.address)
    }

    @Test fun amount_is_converted_from_dgb_to_satoshis() {
        val u = DigiByteUri.parse("digibyte:DTest123?amount=1.5")
        assertEquals("DTest123", u?.address)
        assertEquals(150_000_000L, u?.amount)
    }

    @Test fun label_and_message_are_percent_decoded() {
        val u = DigiByteUri.parse("digibyte:DTest123?label=Coffee%20Shop&message=Order%20%231")
        assertEquals("Coffee Shop", u?.label)
        assertEquals("Order #1", u?.message)
    }

    @Test fun a_foreign_scheme_is_rejected() {
        assertNull(DigiByteUri.parse("digiid://example.com/callback"))
        assertNull(DigiByteUri.parse("https://example.com"))
    }

    @Test fun blank_input_is_rejected() {
        assertNull(DigiByteUri.parse("   "))
        assertNull(DigiByteUri.parse("digibyte:"))
    }

    // ── Asset transfer request ──

    /**
     * The marketplace hand-off shape: "transfer N units of asset X to this address."
     * Quantity is in the asset's own RAW units — never scaled like DGB, because divisibility
     * is a property of the asset and the sender does not get to assume it.
     */
    @Test fun an_asset_transfer_request_carries_the_asset_id_and_raw_quantity() {
        val u = DigiByteUri.parse("digibyte:DTest123?assetId=La3Qxyz&assetAmount=5")
        assertEquals("DTest123", u?.address)
        assertEquals("La3Qxyz", u?.assetId)
        assertEquals(5L, u?.assetAmount)
        assertNull("an asset request must not imply a DGB amount", u?.amount)
    }

    /**
     * Fail closed. A URI naming an asset without a usable quantity must be rejected outright,
     * NOT degraded into a plain DGB send to an address the requester chose — that would turn a
     * malformed asset request into a payment prompt for the wrong thing entirely.
     */
    @Test fun an_asset_id_without_a_valid_quantity_is_rejected() {
        assertNull(DigiByteUri.parse("digibyte:DTest123?assetId=La3Qxyz"))
        assertNull(DigiByteUri.parse("digibyte:DTest123?assetId=La3Qxyz&assetAmount="))
        assertNull(DigiByteUri.parse("digibyte:DTest123?assetId=La3Qxyz&assetAmount=abc"))
        assertNull(DigiByteUri.parse("digibyte:DTest123?assetId=La3Qxyz&assetAmount=1.5"))
    }

    /** Zero and negative quantities transfer nothing; treat them as malformed, not as a no-op. */
    @Test fun a_non_positive_asset_quantity_is_rejected() {
        assertNull(DigiByteUri.parse("digibyte:DTest123?assetId=La3Qxyz&assetAmount=0"))
        assertNull(DigiByteUri.parse("digibyte:DTest123?assetId=La3Qxyz&assetAmount=-5"))
    }

    /** A quantity with no asset names nothing to send, so it cannot be acted on either. */
    @Test fun a_quantity_without_an_asset_id_is_rejected() {
        assertNull(DigiByteUri.parse("digibyte:DTest123?assetAmount=5"))
    }

    /** Round-trips through the encoder so a wallet-generated request re-reads identically. */
    @Test fun an_encoded_asset_request_parses_back_to_the_same_values() {
        val encoded = DigiByteUri.encodeAssetRequest("DTest123", "La3Qxyz", 42L, label = "Sale #7")
        val u = DigiByteUri.parse(encoded)
        assertEquals("DTest123", u?.address)
        assertEquals("La3Qxyz", u?.assetId)
        assertEquals(42L, u?.assetAmount)
        assertEquals("Sale #7", u?.label)
    }

    /** A plain DGB request must not come back claiming to be an asset request. */
    @Test fun a_plain_payment_request_has_no_asset_fields() {
        val u = DigiByteUri.parse("digibyte:DTest123?amount=2")
        assertNull(u?.assetId)
        assertNull(u?.assetAmount)
    }
}
