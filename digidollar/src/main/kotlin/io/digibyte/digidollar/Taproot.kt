package io.digibyte.digidollar

import java.security.MessageDigest

/**
 * secp256k1 point operation the protocol layer cannot do itself: the
 * x-only tweak-add of BIP341. Returns 33 bytes — a parity byte (0 or 1)
 * followed by the x coordinate of `xonlyKey + tweak*G`. In production the
 * native signer provides this (public-key math only, no secrets); tests
 * use a BouncyCastle double.
 */
fun interface EcOps {
    fun xonlyTweakAdd(xonlyKey: ByteArray, tweak: ByteArray): ByteArray
}

/**
 * DigiDollar taproot structures, mirroring DigiByte Core v9.26.4
 * (digidollar/scripts.h): the Collateral output's two-leaf MAST and the
 * DD-token output. Byte layouts are proven against Core-built regtest
 * fixtures (the redeem fixture's witness carries the actual leaf script
 * and control block Core accepted).
 */
@Suppress("TooManyFunctions") // one cohesive BIP341 surface; splitting hides the structure
object Taproot {

    /** BIP341 NUMS internal key for Collateral outputs — key-path spends
     *  are provably impossible; every spend is script-path. */
    const val COLLATERAL_NUMS_KEY =
        "50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0"

    private const val OP_CLTV = 0xb1
    private const val OP_DROP = 0x75
    private const val OP_DIGIDOLLAR = 0xbb
    private const val OP_DDVERIFY = 0xbc
    private const val OP_CHECKSIG = 0xac
    private const val OP_CHECKCOLLATERAL = 0xbe
    private const val OP_NOT = 0x91
    private const val OP_VERIFY = 0x69

    private const val LEAF_VERSION = 0xc0

    /**
     * The normal Redemption tapscript leaf:
     * `<lockHeight> OP_CLTV OP_DROP OP_DIGIDOLLAR <ddCents> OP_DDVERIFY
     * <ownerKey> OP_CHECKSIG` — spendable by the Owner's signature alone
     * once the timelock expires. Returned as hex.
     */
    fun normalRedemptionLeafScript(lockHeight: Int, ddCents: Long, ownerKeyHex: String): String {
        val ownerKey = ownerKeyHex.hexToByteArray()
        require(ownerKey.size == 32) { "Owner key must be 32 bytes" }
        val script = mutableListOf<Byte>()
        script.push(ScriptNum.encode(lockHeight.toLong()))
        script.op(OP_CLTV)
        script.op(OP_DROP)
        script.op(OP_DIGIDOLLAR)
        script.push(ScriptNum.encode(ddCents))
        script.op(OP_DDVERIFY)
        script.push(ownerKey)
        script.op(OP_CHECKSIG)
        return script.toByteArray().toHex()
    }

    /**
     * The emergency (ERR) tapscript leaf — spendable after the timelock
     * only while the system is under 100% collateralized:
     * `<lockHeight> OP_CLTV OP_DROP <100> OP_CHECKCOLLATERAL OP_NOT
     * OP_VERIFY OP_DIGIDOLLAR <ddCents> OP_DDVERIFY <ownerKey> OP_CHECKSIG`.
     */
    fun errLeafScript(lockHeight: Int, ddCents: Long, ownerKeyHex: String): String {
        val ownerKey = ownerKeyHex.hexToByteArray()
        require(ownerKey.size == 32) { "Owner key must be 32 bytes" }
        val script = mutableListOf<Byte>()
        script.push(ScriptNum.encode(lockHeight.toLong()))
        script.op(OP_CLTV)
        script.op(OP_DROP)
        script.push(ScriptNum.encode(100))
        script.op(OP_CHECKCOLLATERAL)
        script.op(OP_NOT)
        script.op(OP_VERIFY)
        script.op(OP_DIGIDOLLAR)
        script.push(ScriptNum.encode(ddCents))
        script.op(OP_DDVERIFY)
        script.push(ownerKey)
        script.op(OP_CHECKSIG)
        return script.toByteArray().toHex()
    }

    /** BIP341 tapleaf hash of a script at leaf version 0xc0. */
    fun tapLeafHash(scriptHex: String): ByteArray {
        val script = scriptHex.hexToByteArray()
        return taggedHash(
            "TapLeaf",
            byteArrayOf(LEAF_VERSION.toByte()) + compactSize(script.size) + script,
        )
    }

    /**
     * Control block for a script-path spend of the Collateral output via the
     * normal Redemption leaf: `(0xc0 | outputKeyParity) ++ NUMS internal key
     * ++ ERR-leaf sibling hash`. Returned as hex.
     */
    fun collateralControlBlock(
        lockHeight: Int,
        ddCents: Long,
        ownerKeyHex: String,
        ecOps: EcOps,
    ): String {
        val sibling = tapLeafHash(errLeafScript(lockHeight, ddCents, ownerKeyHex))
        val root = tapBranchHash(
            tapLeafHash(normalRedemptionLeafScript(lockHeight, ddCents, ownerKeyHex)),
            sibling,
        )
        val nums = COLLATERAL_NUMS_KEY.hexToByteArray()
        val tweaked = ecOps.checkedTweakAdd(nums, taggedHash("TapTweak", nums + root))
        val parity = tweaked[0].toInt()
        return (byteArrayOf((LEAF_VERSION or parity).toByte()) + nums + sibling).toHex()
    }

    /**
     * The Collateral output's x-only key (hex): NUMS internal key tweaked
     * by the two-leaf MAST root (normal Redemption leaf + ERR leaf).
     */
    fun collateralOutputKey(
        lockHeight: Int,
        ddCents: Long,
        ownerKeyHex: String,
        ecOps: EcOps,
    ): String {
        val root = tapBranchHash(
            tapLeafHash(normalRedemptionLeafScript(lockHeight, ddCents, ownerKeyHex)),
            tapLeafHash(errLeafScript(lockHeight, ddCents, ownerKeyHex)),
        )
        val nums = COLLATERAL_NUMS_KEY.hexToByteArray()
        val tweaked = ecOps.checkedTweakAdd(nums, taggedHash("TapTweak", nums + root))
        return tweaked.copyOfRange(1, 33).toHex()
    }

    /**
     * The DD-token output's x-only key (hex): the Owner key with the
     * key-path-only BIP341 tweak (no script tree). This is also what a
     * standard BIP86 receive address decodes to, so a Mint to a wallet
     * address is spendable by that wallet.
     */
    fun ddTokenOutputKey(ownerKeyHex: String, ecOps: EcOps): String {
        val ownerKey = ownerKeyHex.hexToByteArray()
        require(ownerKey.size == 32) { "Owner key must be 32 bytes" }
        val tweaked = ecOps.checkedTweakAdd(ownerKey, taggedHash("TapTweak", ownerKey))
        return tweaked.copyOfRange(1, 33).toHex()
    }

    /** BIP341 tapbranch hash: tagged hash of the two children, lexicographic. */
    fun tapBranchHash(a: ByteArray, b: ByteArray): ByteArray {
        val (lo, hi) = if (compareLexicographic(a, b) <= 0) a to b else b to a
        return taggedHash("TapBranch", lo + hi)
    }

    /** BIP340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || msg). */
    fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val tagHash = sha.digest(tag.toByteArray(Charsets.US_ASCII))
        sha.reset()
        sha.update(tagHash)
        sha.update(tagHash)
        sha.update(msg)
        return sha.digest()
    }

    /** Enforce the EcOps contract on results an implementation hands back:
     *  exactly a parity byte (0/1) followed by the 32-byte x coordinate. */
    private fun EcOps.checkedTweakAdd(xonlyKey: ByteArray, tweak: ByteArray): ByteArray {
        val out = xonlyTweakAdd(xonlyKey, tweak)
        require(out.size == 33 && out[0].toInt() in 0..1) {
            "EcOps.xonlyTweakAdd must return parity(0/1) + 32-byte x, got ${out.size} bytes"
        }
        return out
    }

    private fun compareLexicographic(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val cmp = (a[i].toInt() and 0xff).compareTo(b[i].toInt() and 0xff)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    private fun compactSize(n: Int): ByteArray {
        require(n in 0..252) { "compactSize > 252 not needed for tapscripts here" }
        return byteArrayOf(n.toByte())
    }

    private fun MutableList<Byte>.op(opcode: Int) = add(opcode.toByte())

    private fun MutableList<Byte>.push(bytes: ByteArray) {
        require(bytes.size <= 75) { "push too large for a direct-length opcode" }
        add(bytes.size.toByte())
        addAll(bytes.asList())
    }
}
