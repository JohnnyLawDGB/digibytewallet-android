package io.digibyte.core.digidollar

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.digidollar.EcOps

/**
 * Production [EcOps]: BIP341 x-only tweak-add via the native signer's
 * secp256k1 ([NativeBridge.ddXOnlyTweakAdd], 1:1 mapping). Public-key math
 * only — no secrets cross JNI. Tests use the BouncyCastle double; on-device
 * parity is proven by DigiDollarSignerTest's tap-tweak KAT against the
 * Core-built mint fixture.
 */
object NativeEcOps : EcOps {
    override fun xonlyTweakAdd(xonlyKey: ByteArray, tweak: ByteArray): ByteArray =
        requireNotNull(NativeBridge.ddXOnlyTweakAdd(xonlyKey, tweak)) {
            "xonlyTweakAdd failed: off-curve key or invalid tweak"
        }
}
