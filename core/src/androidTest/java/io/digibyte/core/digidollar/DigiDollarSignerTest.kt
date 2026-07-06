package io.digibyte.core.digidollar

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KATs for the native DigiDollar taproot signer (issue #3, ADR-0001).
 *
 * - BIP340 official test vectors through the raw-key JNI surface
 * - BIP341 tap-tweak proven against the Core-built mint fixture: the
 *   tweaked Owner key must equal the DD-token output key of the fixture
 * - BIP86 derivation against the official BIP86 test vectors
 * - full seed -> derive -> (tweak) -> sign -> verify loop
 */
@RunWith(AndroidJUnit4::class)
class DigiDollarSignerTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ---- BIP340 official vectors (bip-0340/test-vectors.csv, indices 0-3) ----

    private data class Bip340Vector(
        val seckey: String,
        val pubkey: String,
        val aux: String,
        val msg: String,
        val sig: String,
    )

    private val signingVectors = listOf(
        Bip340Vector(
            "0000000000000000000000000000000000000000000000000000000000000003",
            "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
        ),
        Bip340Vector(
            "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "0000000000000000000000000000000000000000000000000000000000000001",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE3341" +
                "8906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
        ),
        Bip340Vector(
            "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
            "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
            "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
            "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1B" +
                "AB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
        ),
        Bip340Vector(
            "0B432B2677937381AEF05BB02A66ECD012773062CF3FA2549E44F58ED2401710",
            "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC" +
                "97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3",
        ),
    )

    @Test
    fun bip340_signing_vectors_produce_the_official_signatures() {
        for ((i, v) in signingVectors.withIndex()) {
            val pub = NativeBridge.ddXOnlyPubKey(hex(v.seckey))
            assertNotNull("vector $i pubkey", pub)
            assertEquals("vector $i pubkey", v.pubkey.lowercase(), pub!!.toHex())

            val sig = NativeBridge.ddSchnorrSign(hex(v.seckey), hex(v.msg), hex(v.aux))
            assertNotNull("vector $i sig", sig)
            assertEquals("vector $i sig", v.sig.lowercase(), sig!!.toHex())

            assertTrue("vector $i verify", NativeBridge.ddSchnorrVerify(pub, hex(v.msg), sig))
        }
    }

    @Test
    fun bip340_verification_rejects_corrupted_and_off_curve_inputs() {
        val v = signingVectors[2]
        val sig = NativeBridge.ddSchnorrSign(hex(v.seckey), hex(v.msg), hex(v.aux))!!

        val corrupted = sig.copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }
        assertFalse(NativeBridge.ddSchnorrVerify(hex(v.pubkey), hex(v.msg), corrupted))

        // BIP340 vector 5's public key: not a point on the curve.
        val offCurve = "EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34"
        assertFalse(NativeBridge.ddSchnorrVerify(hex(offCurve), hex(v.msg), sig))
    }

    // ---- BIP341 tap tweak, proven against the Core-built mint fixture ----

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val tagHash = sha.digest(tag.toByteArray())
        sha.reset()
        sha.update(tagHash)
        sha.update(tagHash)
        sha.update(msg)
        return sha.digest()
    }

    @Test
    fun tap_tweak_of_the_fixture_owner_key_equals_the_dd_token_output_key() {
        // From dgb-support mint-tx.json: OP_RETURN Owner key and the
        // zero-value DD-token P2TR output key Core derived from it.
        val ownerKey = hex("c20a139635a064cbfb7ee7c8f1d4362de68f5d6b02e8cf1f6906f0c0e760c034")
        val expectedOutputKey = "0b1869065a47f4d36a8061e10b6942de58a132db1c1c5b5f7c8f7f4909a4d14a"

        val tweaked = NativeBridge.ddXOnlyTweakAdd(ownerKey, taggedHash("TapTweak", ownerKey))
        assertNotNull(tweaked)
        assertEquals(33, tweaked!!.size)
        assertEquals(expectedOutputKey, tweaked.copyOfRange(1, 33).toHex())
    }

    // ---- BIP86 derivation + end-to-end seed signing ----

    private val bip86Mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun bip86_owner_keys_match_the_official_vectors_and_sign_verifiably() {
        assertTrue(NativeBridge.createWalletFromBytes(bip86Mnemonic.toByteArray()))
        try {
            // BIP86 official vectors (coin type 0): internal keys for
            // m/86'/0'/0'/0/0 and m/86'/0'/0'/0/1.
            val key0 = NativeBridge.ddDeriveOwnerKey(coinType = 0, chain = 0, index = 0)
            assertNotNull(key0)
            assertEquals(
                "cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115",
                key0!!.toHex(),
            )
            val key1 = NativeBridge.ddDeriveOwnerKey(coinType = 0, chain = 0, index = 1)
            assertNotNull(key1)
            assertEquals(
                "83dfe85a3151d2517290da461fe2815591ef69f2b18a2ce63f01697a8b313145",
                key1!!.toHex(),
            )

            // Script-path signing verifies against the UNtweaked Owner key.
            val digest = ByteArray(32) { it.toByte() }
            val leafSig = NativeBridge.ddSignDigest(digest, 0, 0, 0, tapTweak = false)
            assertNotNull(leafSig)
            assertTrue(NativeBridge.ddSchnorrVerify(key0, digest, leafSig!!))

            // Key-path signing verifies against the tweaked output key.
            val keyPathSig = NativeBridge.ddSignDigest(digest, 0, 0, 0, tapTweak = true)
            assertNotNull(keyPathSig)
            val outputKey = NativeBridge.ddXOnlyTweakAdd(key0, taggedHash("TapTweak", key0))!!
                .copyOfRange(1, 33)
            assertTrue(NativeBridge.ddSchnorrVerify(outputKey, digest, keyPathSig!!))
            assertFalse(NativeBridge.ddSchnorrVerify(key0, digest, keyPathSig))
        } finally {
            NativeBridge.lockSession()
        }
    }

    @Test
    fun signer_returns_null_without_a_seed() {
        NativeBridge.lockSession()
        assertEquals(null, NativeBridge.ddDeriveOwnerKey(0, 0, 0))
        assertEquals(null, NativeBridge.ddSignDigest(ByteArray(32), 0, 0, 0, true))
    }
}
