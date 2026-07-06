package io.digibyte.digidollar

import java.math.BigInteger
import org.bouncycastle.crypto.ec.CustomNamedCurves

/**
 * JVM EcOps for tests: real secp256k1 point math via BouncyCastle.
 * Production uses the native signer's ddXOnlyTweakAdd (ADR-0001).
 */
object BouncyCastleEcOps : EcOps {

    private val params = CustomNamedCurves.getByName("secp256k1")

    override fun xonlyTweakAdd(xonlyKey: ByteArray, tweak: ByteArray): ByteArray {
        require(xonlyKey.size == 32 && tweak.size == 32)
        val t = BigInteger(1, tweak)
        require(t.signum() > 0 && t < params.n) { "tweak out of range" }

        // BIP340 x-only keys imply an even Y coordinate (0x02 prefix).
        val base = params.curve.decodePoint(byteArrayOf(2) + xonlyKey)
        val tweaked = base.add(params.g.multiply(t)).normalize()
        require(!tweaked.isInfinity) { "tweaked key is the point at infinity" }

        val parity = if (tweaked.yCoord.toBigInteger().testBit(0)) 1 else 0
        val x = tweaked.xCoord.toBigInteger().toByteArray()
            .let { it.copyOfRange(maxOf(0, it.size - 32), it.size) }
        return byteArrayOf(parity.toByte()) + ByteArray(32 - x.size) + x
    }
}
