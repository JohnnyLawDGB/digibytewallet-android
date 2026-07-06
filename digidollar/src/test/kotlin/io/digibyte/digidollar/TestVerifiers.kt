package io.digibyte.digidollar

import java.math.BigInteger
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner

/** BIP340 Schnorr verification over BouncyCastle points — tests only. */
object TestSchnorr {

    private val params = CustomNamedCurves.getByName("secp256k1")

    fun verify(xonlyPubKey: ByteArray, msg32: ByteArray, sig64: ByteArray): Boolean {
        if (xonlyPubKey.size != 32 || msg32.size != 32 || sig64.size != 64) return false
        val n = params.n
        val fieldP = params.curve.field.characteristic

        val r = BigInteger(1, sig64.copyOfRange(0, 32))
        val s = BigInteger(1, sig64.copyOfRange(32, 64))
        if (r >= fieldP || s >= n) return false

        val pubPoint = runCatching {
            params.curve.decodePoint(byteArrayOf(2) + xonlyPubKey)
        }.getOrNull() ?: return false

        val e = BigInteger(
            1,
            Taproot.taggedHash("BIP0340/challenge", sig64.copyOfRange(0, 32) + xonlyPubKey + msg32),
        ).mod(n)

        val bigR = params.g.multiply(s).add(pubPoint.multiply(n.subtract(e))).normalize()
        if (bigR.isInfinity) return false
        if (bigR.yCoord.toBigInteger().testBit(0)) return false
        return bigR.xCoord.toBigInteger() == r
    }
}

/** ECDSA verification (DER, low-level) over BouncyCastle — tests only. */
object TestEcdsa {

    private val params = CustomNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)

    fun verify(compressedPubKey: ByteArray, digest32: ByteArray, der: ByteArray): Boolean {
        val point = runCatching { params.curve.decodePoint(compressedPubKey) }.getOrNull()
            ?: return false
        val (r, s) = parseDer(der) ?: return false
        val signer = ECDSASigner()
        signer.init(false, ECPublicKeyParameters(point, domain))
        return signer.verifySignature(digest32, r, s)
    }

    private fun parseDer(der: ByteArray): Pair<BigInteger, BigInteger>? {
        // 0x30 len 0x02 rlen r 0x02 slen s
        if (der.size < 8 || der[0].toInt() != 0x30 || der[2].toInt() != 0x02) return null
        val rLen = der[3].toInt()
        val r = BigInteger(1, der.copyOfRange(4, 4 + rLen))
        if (der[4 + rLen].toInt() != 0x02) return null
        val sLen = der[5 + rLen].toInt()
        val s = BigInteger(1, der.copyOfRange(6 + rLen, 6 + rLen + sLen))
        return r to s
    }
}
