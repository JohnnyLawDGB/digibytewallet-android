package io.digibyte.core.asset

import io.digibyte.core.model.AssetOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DigiAssetDecoder].
 *
 * Test vectors are generated from the reference implementations:
 *   - RenzoDD/digibyte-js (Precision.js encoder)
 *   - RenzoDD/digiasset-core (BitIO.cpp encoder/decoder)
 *
 * Each hex string is a complete OP_RETURN script (starting with 0x6a).
 *
 * Encoding summary:
 *   OP_RETURN (6a) + push-length (1 byte) + DA(4441) + version + opcode + body
 *
 * Fixed-precision encoding (for quantities):
 *   3-bit length prefix -> byte count = prefix + 1
 *   byte count 1: 5-bit mantissa (values 0-31)
 *   byte count 2-4: (count*8-7)-bit mantissa + 4-bit exponent
 *   byte count 5-6: (count*8-6)-bit mantissa + 3-bit exponent
 *   byte count 7: 54-bit mantissa (length prefix 110 or 111, with adjustment)
 *   value = mantissa * 10^exponent
 */
class DigiAssetDecoderTest {

    private lateinit var decoder: DigiAssetDecoder

    // -- Test vectors --
    // V3 transfer: DA + v3 + opcode 0x15 + transfer(output=0, amount=500)
    // Bits: 44410315 (DA magic + v3 + transfer opcode)
    //       000 00000 (skip=0, range=0, percent=0, output=0)
    //       001 000000101 0010 (precision-encoded 500: mantissa=5, exp=2)
    // Hex: 6a0744410315002052
    private val TRANSFER_V3 = "6a0744410315002052"

    // V3 locked issuance: DA + v3 + opcode 0x01 + sha256(metadata) + amount + transfer + issuance_flags
    // metadata hash = sha256('{"name":"TestCoin"}') = d66f1117234767d6892700d575511e1effcd6471013dda0e832dbb67e629d674
    // amount = 2100000, transfer to output 0 = 2100000
    // issuance flags: div=2, locked=1, agg=aggregatable -> 0x50
    private val ISSUANCE_V3_LOCKED =
        "6a2a44410301d66f1117234767d6892700d575511e1effcd6471013dda0e832dbb67e629d674215500215550"

    // V3 burn: DA + v3 + opcode 0x25 + transfer(output=0, 200) + transfer(output=31 burn, 300)
    private val BURN_V3 = "6a0a444103250020221f2032"

    // V3 unlocked dispersed issuance: DA + v3 + opcode 0x05 (no metadata) + amount=1 + transfer + flags
    // issuance flags: div=0, locked=0, agg=dispersed(2) -> 0x08
    private val ISSUANCE_V3_UNLOCKED_DISPERSED = "6a084441030501000108"

    // V3 transfer with skip flag: skip=1 on first instruction
    private val TRANSFER_V3_SKIP = "6a0a44410315802012012051"

    // V3 issuance with rewritable rules (opcode 0x03): metadata(all zeros) + amount=1000 + rules(ff) + transfer + flags
    private val ISSUANCE_V3_RULES =
        "6a2b4441030300000000000000000000000000000000000000000000000000000000000000002013ff00201300"

    // Non-DA OP_RETURN (SegWit commitment)
    private val NON_DA_OP_RETURN = "6a24aa21a9ed6fb29ce476d46dce43e21190d6c2"

    // ---- Real mainnet transaction data ----
    // txid: 1af59aea7181f4f3aeec2c03b8bd48952a06e61051fb76750fb74ed1fd5651e3
    // block 11000208, DigiAsset v2 transfer, 600-sat marker output
    // data: DA(4441) + version(02) + opcode(15 = transfer) + transfer(output=0, amount=1000)
    private val REAL_MAINNET_TRANSFER_V2 = "6a0744410215002013"

    @Before
    fun setUp() {
        decoder = DigiAssetDecoder()
    }

    // -- Helper --
    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ================================================================
    // Detection tests
    // ================================================================

    @Test
    fun `decode detects DA prefix in OP_RETURN`() {
        val result = decoder.decode(hexToBytes(TRANSFER_V3))
        assertNotNull("Should detect DA prefix in transfer OP_RETURN", result)
    }

    @Test
    fun `decode returns null for non-DA OP_RETURN`() {
        val result = decoder.decode(hexToBytes(NON_DA_OP_RETURN))
        assertNull("SegWit commitment should not be detected as DigiAsset", result)
    }

    @Test
    fun `decode returns null for empty input`() {
        assertNull(decoder.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for too-short script`() {
        assertNull(decoder.decode(hexToBytes("6a02")))
        assertNull(decoder.decode(hexToBytes("6a024441"))) // DA magic but no version/opcode
    }

    @Test
    fun `containsAsset returns true for DA script`() {
        assertTrue(decoder.containsAsset(hexToBytes(TRANSFER_V3)))
        assertTrue(decoder.containsAsset(hexToBytes(ISSUANCE_V3_LOCKED)))
        assertTrue(decoder.containsAsset(hexToBytes(BURN_V3)))
    }

    @Test
    fun `containsAsset returns false for non-DA script`() {
        assertFalse(decoder.containsAsset(hexToBytes(NON_DA_OP_RETURN)))
        assertFalse(decoder.containsAsset(ByteArray(0)))
        assertFalse(decoder.containsAsset(hexToBytes("6a024442"))) // "DB" not "DA"
    }

    // ================================================================
    // Operation identification tests
    // ================================================================

    @Test
    fun `decode identifies transfer operation`() {
        val result = decoder.decode(hexToBytes(TRANSFER_V3))!!
        assertEquals(AssetOperation.TRANSFER, result.operation)
        assertEquals(3, result.version)
        assertEquals(0x15, result.opcode)
    }

    @Test
    fun `decode identifies issuance operation`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!
        assertEquals(AssetOperation.ISSUANCE, result.operation)
        assertEquals(3, result.version)
        assertEquals(0x01, result.opcode)
    }

    @Test
    fun `decode identifies burn operation`() {
        val result = decoder.decode(hexToBytes(BURN_V3))!!
        assertEquals(AssetOperation.BURN, result.operation)
        assertEquals(3, result.version)
        assertEquals(0x25, result.opcode)
    }

    @Test
    fun `decode identifies issuance with no metadata (opcode 05)`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_UNLOCKED_DISPERSED))!!
        assertEquals(AssetOperation.ISSUANCE, result.operation)
        assertEquals(0x05, result.opcode)
        assertNull("Opcode 0x05 should have no metadata CID", result.metadataCid)
    }

    @Test
    fun `decode identifies issuance with rewritable rules (opcode 03)`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_RULES))!!
        assertEquals(AssetOperation.ISSUANCE, result.operation)
        assertEquals(0x03, result.opcode)
    }

    // ================================================================
    // Metadata extraction tests
    // ================================================================

    @Test
    fun `decode extracts metadata CID when present`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!
        assertNotNull("Issuance with opcode 0x01 should have metadata CID", result.metadataCid)
        // CID should start with 'b' (base32 CIDv1 prefix)
        assertTrue(
            "CID should start with 'b' prefix",
            result.metadataCid!!.startsWith("b")
        )
        // Verify the metadata hash bytes match the known hash
        assertNotNull(result.metadataHash)
        val expectedHash = hexToBytes("d66f1117234767d6892700d575511e1effcd6471013dda0e832dbb67e629d674")
        assertTrue(
            "Metadata hash should match input",
            result.metadataHash!!.contentEquals(expectedHash)
        )
    }

    @Test
    fun `decode returns null CID for all-zero metadata hash in rules issuance`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_RULES))!!
        // The rules issuance has an all-zero metadata hash
        assertNull(
            "All-zero metadata hash should produce null CID",
            result.metadataCid
        )
    }

    @Test
    fun `decode returns null metadata for transfer`() {
        val result = decoder.decode(hexToBytes(TRANSFER_V3))!!
        assertNull("Transfers don't carry metadata hash", result.metadataHash)
        assertNull("Transfers don't carry metadata CID", result.metadataCid)
    }

    // ================================================================
    // Issuance flags tests
    // ================================================================

    @Test
    fun `decode extracts locked flag from issuance`() {
        val locked = decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!
        assertTrue("Issuance with flags 0x50 should be locked", locked.locked)

        val unlocked = decoder.decode(hexToBytes(ISSUANCE_V3_UNLOCKED_DISPERSED))!!
        assertFalse("Issuance with flags 0x08 should be unlocked", unlocked.locked)
    }

    @Test
    fun `decode extracts divisibility from issuance`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!
        assertEquals("Flags 0x50 has divisibility 2", 2, result.divisibility)

        val nft = decoder.decode(hexToBytes(ISSUANCE_V3_UNLOCKED_DISPERSED))!!
        assertEquals("Flags 0x08 has divisibility 0", 0, nft.divisibility)
    }

    @Test
    fun `decode extracts aggregation type from issuance`() {
        val agg = decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!
        assertEquals(Aggregation.AGGREGATABLE, agg.aggregation)

        val dispersed = decoder.decode(hexToBytes(ISSUANCE_V3_UNLOCKED_DISPERSED))!!
        assertEquals(Aggregation.DISPERSED, dispersed.aggregation)
    }

    // ================================================================
    // Quantity decoding tests
    // ================================================================

    @Test
    fun `decode extracts total quantity from issuance`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!
        assertEquals(
            "Issuance should report 2100000 total tokens",
            2100000L,
            result.totalQuantity
        )
    }

    @Test
    fun `decode extracts quantity 1 from NFT issuance`() {
        val result = decoder.decode(hexToBytes(ISSUANCE_V3_UNLOCKED_DISPERSED))!!
        assertEquals(
            "NFT issuance should report quantity 1",
            1L,
            result.totalQuantity
        )
    }

    @Test
    fun `transfer has no total quantity`() {
        val result = decoder.decode(hexToBytes(TRANSFER_V3))!!
        assertNull("Transfers don't have totalQuantity", result.totalQuantity)
    }

    // ================================================================
    // Transfer instruction tests
    // ================================================================

    @Test
    fun `decode parses transfer instructions for transfer`() {
        val result = decoder.decode(hexToBytes(TRANSFER_V3))!!
        assertTrue(
            "Transfer should have at least one instruction",
            result.transferInstructions.isNotEmpty()
        )
        val first = result.transferInstructions.first()
        assertFalse("First instruction should not be skip", first.skip)
        assertFalse("First instruction should not be range", first.range)
        assertFalse("First instruction should not be percent", first.percent)
        assertEquals("First instruction target output 0", 0, first.outputIndex)
        assertEquals("First instruction amount 500", 500L, first.amount)
        assertFalse("First instruction is not a burn", first.isBurn)
    }

    @Test
    fun `decode parses burn instruction with output 31`() {
        val result = decoder.decode(hexToBytes(BURN_V3))!!
        val burnInstr = result.transferInstructions.find { it.isBurn }
        assertNotNull("Burn opcode should have a burn instruction", burnInstr)
        assertEquals(31, burnInstr!!.outputIndex)
        assertEquals(300L, burnInstr.amount)
    }

    @Test
    fun `decode parses transfer with skip flag`() {
        val result = decoder.decode(hexToBytes(TRANSFER_V3_SKIP))!!
        assertTrue(
            "Should have at least 2 instructions",
            result.transferInstructions.size >= 2
        )
        assertTrue("First instruction should have skip=true", result.transferInstructions[0].skip)
        assertEquals(100L, result.transferInstructions[0].amount)
        assertFalse("Second instruction should have skip=false", result.transferInstructions[1].skip)
    }

    // ================================================================
    // toAssetData conversion tests
    // ================================================================

    @Test
    fun `toAssetData converts issuance correctly`() {
        val header = decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!
        val assetData = header.toAssetData("TestAssetId")
        assertEquals("TestAssetId", assetData.assetId)
        assertEquals(2100000L, assetData.quantity)
        assertEquals(AssetOperation.ISSUANCE, assetData.operation)
        assertNotNull(assetData.metadataCid)
        assertTrue(assetData.locked)
        assertEquals(2, assetData.divisibility)
    }

    @Test
    fun `toAssetData converts transfer correctly`() {
        val header = decoder.decode(hexToBytes(TRANSFER_V3))!!
        val assetData = header.toAssetData()
        assertEquals("unknown", assetData.assetId)
        assertEquals(500L, assetData.quantity)
        assertEquals(AssetOperation.TRANSFER, assetData.operation)
    }

    // ================================================================
    // Real mainnet transaction tests
    // ================================================================

    @Test
    fun `decode real mainnet v2 transfer`() {
        // txid: 1af59aea7181f4f3aeec2c03b8bd48952a06e61051fb76750fb74ed1fd5651e3
        // block 11000208 — real DigiAsset v2 transfer on mainnet
        // Script: 6a0744410215002013
        // Data:   DA(4441) + version(02) + opcode(15) + transfer(output=0, amount=1000)
        val result = decoder.decode(hexToBytes(REAL_MAINNET_TRANSFER_V2))
        assertNotNull("Real mainnet tx should decode", result)
        assertEquals(2, result!!.version)
        assertEquals(AssetOperation.TRANSFER, result.operation)
        assertEquals(0x15, result.opcode)
        assertNull("V2 transfers have no metadata", result.metadataHash)

        // Transfer instruction: output 0, amount 1000
        assertTrue(
            "Should have transfer instructions",
            result.transferInstructions.isNotEmpty()
        )
        val first = result.transferInstructions.first()
        assertEquals(0, first.outputIndex)
        assertEquals(1000L, first.amount)
    }

    /**
     * Real on-chain issuance: the "Mobile Tester" DigiAsset (created via digiscope
     * 2026-07-10). Its OP_RETURN is a v1 opcode-1 issuance carrying a 20-byte SHA1
     * torrent hash followed by the 32-byte SHA256 metadata hash. The decoder must
     * skip the SHA1 and build CIDv1(raw, that SHA256) = the real, IPFS-reachable
     * metadata CID. Proves the DECODER is correct — so a runtime null metadataCid
     * ("metadata offline") comes from decoding a TRANSFER (which carries no hash) or
     * a failed network walk to the issuance tx, NOT from the decoder.
     */
    @Test
    fun `decodes real Mobile Tester issuance to correct IPFS metadata CID`() {
        val opReturn = "6a3a4441010134fdfc1f913ab9aae04fa1daf92f389248ff21f1" +
            "be4e676d3cfc8a454fcc38c94b192b8ef916fbbc8f0903c558568b21cafd2a620f1098fa4"
        val header = decoder.decode(hexToBytes(opReturn))
        assertNotNull("real issuance OP_RETURN should decode", header)
        assertEquals(AssetOperation.ISSUANCE, header!!.operation)
        assertEquals(
            "bafkreif6jztw2ph4rjcu7tbyzffrsk4o7elpxpepbeb4kwcwrmq4v7jkmi",
            header.metadataCid
        )
    }
}
