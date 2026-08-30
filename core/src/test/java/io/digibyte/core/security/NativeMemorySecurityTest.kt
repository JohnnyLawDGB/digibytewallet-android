package io.digibyte.core.security

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Security test: Native Memory Safety
 *
 * Static analysis of the C JNI bridge to verify that seed material
 * is properly zeroed after use and never returned across the boundary.
 *
 * Published as part of the security audit.
 */
class NativeMemorySecurityTest {

    @Test
    fun `jni_wallet_c zeros seed after createWallet`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        // createWallet should call secure_zero on the seed buffer after use
        assertTrue("createWallet must zero seed after use (secure_zero)",
            content.contains("secure_zero(seed,"))
    }

    /**
     * The legacy String entry points (`createWallet`, `recoverWallet`, `unlockSession`) are
     * deleted, and with them the only loops that wrote every wallet address to logcat
     * (`addr[%zu] = %s`). A JNI symbol for any of them coming back means the String seed path
     * or the address dump is back.
     */
    @Test
    fun `jni_wallet_c has no legacy String seed entry points or address-pool dump`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        listOf("NativeBridge_createWallet(", "NativeBridge_recoverWallet(", "NativeBridge_unlockSession(")
            .forEach { sym ->
                assertFalse("$sym must stay deleted", content.contains(sym))
            }
        assertFalse("address pool must never be logged", content.contains("addr[%zu] = %s"))
    }

    @Test
    fun `jni_wallet_c zeros phrase after createWallet`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        assertTrue("createWallet must zero phrase buffer after use",
            content.contains("secure_zero(phrase,") || content.contains("ReleaseStringUTFChars"))
    }

    @Test
    fun `jni_wallet_c zeros seed after recoverWallet`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        // Count secure_zero calls — should be at least 2 (createWallet + recoverWallet)
        val secureZeroCount = Regex("secure_zero\\(seed,").findAll(content).count()
        assertTrue("Both createWallet and recoverWallet must zero seed (found $secureZeroCount calls)",
            secureZeroCount >= 2)
    }

    @Test
    fun `lockSession zeros g_seed`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        assertTrue("lockSession must zero g_seed",
            content.contains("secure_zero(g_seed,"))
    }

    @Test
    fun `generateMnemonic zeros entropy after use`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        assertTrue("generateMnemonic must zero entropy buffer",
            content.contains("secure_zero(entropy,"))
    }

    @Test
    fun `generateMnemonic zeros phrase buffer after creating jstring`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        // After NewStringUTF, the C buffer should be zeroed
        val genSection = content.substringAfter("generateMnemonic").substringBefore("createWallet")
        assertTrue("generateMnemonic must zero phrase buffer after NewStringUTF",
            genSection.contains("secure_zero(phrase,"))
    }

    @Test
    fun `signMessage zeros key after signing`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet_sign.c")
        assertTrue("signMessage must call BRKeyClean after signing",
            content.contains("BRKeyClean(&key)"))
    }

    @Test
    fun `signMessage does not return private key in result`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet_sign.c")
        // Result format should be "address|base64signature" — no key.secret or hex key
        assertTrue("signMessage result must be address|signature format",
            content.contains("snprintf(result") && content.contains("%s|%s"))
        assertFalse("signMessage must not include key.secret in result",
            content.contains("key.secret") && content.contains("result"))
    }

    @Test
    fun `secure_zero is volatile and cannot be optimized away`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_bridge.h")
        assertTrue("secure_zero must use volatile pointer to prevent compiler optimization",
            content.contains("volatile"))
    }

    @Test
    fun `g_seed is not returned by any JNI function`() {
        val walletC = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        val signC = readNativeFile("native/src/main/jni/bridge/jni_wallet_sign.c")
        val peerC = readNativeFile("native/src/main/jni/bridge/jni_peer.c")

        // g_seed should never appear in a return statement or NewStringUTF/NewByteArray
        listOf(walletC, signC, peerC).forEach { content ->
            assertFalse("g_seed must never be passed to NewStringUTF",
                content.contains("NewStringUTF(env, (const char *)g_seed") ||
                content.contains("NewStringUTF(env, g_seed"))
            assertFalse("g_seed must never be passed to SetByteArrayRegion for return",
                Regex("SetByteArrayRegion.*g_seed").containsMatchIn(content))
        }
    }

    @Test
    fun `createWalletFromBytes zeros phraseChars after seed derivation`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        val section = content.substringAfter("createWalletFromBytes").substringBefore("recoverWalletFromBytes")
        assertTrue("createWalletFromBytes must zero phraseChars after use",
            section.contains("secure_zero(phraseChars,"))
    }

    @Test
    fun `recoverWalletFromBytes zeros phraseChars after seed derivation`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        val section = content.substringAfter("recoverWalletFromBytes").substringBefore("lockSession")
        assertTrue("recoverWalletFromBytes must zero phraseChars after use",
            section.contains("secure_zero(phraseChars,"))
    }

    @Test
    fun `g_seed is static not extern`() {
        val header = readNativeFile("native/src/main/jni/bridge/jni_bridge.h")
        assertFalse("g_seed must not be declared extern in header",
            header.contains("extern uint8_t") && header.contains("g_seed"))
        val walletC = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        assertTrue("g_seed must be declared static in jni_wallet.c",
            walletC.contains("static uint8_t") && walletC.contains("g_seed"))
    }

    @Test
    fun `seed accessor functions declared in header`() {
        val header = readNativeFile("native/src/main/jni/bridge/jni_bridge.h")
        assertTrue("seed_sign_transaction must be declared in header",
            header.contains("seed_sign_transaction"))
        assertTrue("seed_derive_key must be declared in header",
            header.contains("seed_derive_key"))
        assertTrue("seed_is_valid must be declared in header",
            header.contains("seed_is_valid"))
    }

    @Test
    fun `signTransaction uses seed_sign_transaction not g_seed directly`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_transaction.c")
        assertTrue("signTransaction must use seed_sign_transaction()",
            content.contains("seed_sign_transaction("))
        assertFalse("signTransaction must not access g_seed directly",
            content.contains("g_seed"))
    }

    @Test
    fun `signMessage uses seed_derive_key not g_seed directly`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet_sign.c")
        assertTrue("signMessage must use seed_derive_key()",
            content.contains("seed_derive_key("))
        assertFalse("signMessage must not access g_seed directly",
            content.contains("g_seed"))
    }

    @Test
    fun `entropy is read from dev_urandom not BRRand`() {
        val content = readNativeFile("native/src/main/jni/bridge/jni_wallet.c")
        // BRRand is NOT cryptographic — must use /dev/urandom
        assertTrue("Must use /dev/urandom for entropy, not BRRand",
            content.contains("/dev/urandom"))
    }

    private fun readNativeFile(path: String): String {
        val candidates = listOf(path, "../$path", "../../$path")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: candidates.map { File(System.getProperty("user.dir"), it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$path not found")
        return file.readText()
    }
}
