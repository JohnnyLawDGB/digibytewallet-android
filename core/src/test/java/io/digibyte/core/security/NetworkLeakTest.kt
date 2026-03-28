package io.digibyte.core.security

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Security test: Network Leak Prevention
 *
 * Static analysis verifying that no wallet code sends seed, private key,
 * or mnemonic material over the network (HTTP, WebSocket, etc.).
 *
 * Published as part of the security audit.
 */
class NetworkLeakTest {

    /**
     * Scan all Kotlin source files for patterns that could leak seed material
     * through network calls.
     */
    @Test
    fun `DigiScopeClient never references seed or mnemonic`() {
        val file = findSource("core/src/main/java/io/digibyte/core/digiscope/DigiScopeClient.kt")
        val content = file.readText().lowercase()

        val dangerousPatterns = listOf("seed", "mnemonic", "phrase", "entropy", "privkey", "privatekey", "g_seed")
        val found = dangerousPatterns.filter { content.contains(it) }

        assertTrue(
            "DigiScopeClient should never reference seed material. Found: $found",
            found.isEmpty()
        )
    }

    @Test
    fun `HubWebSocket never references seed or mnemonic`() {
        val file = findSource("core/src/main/java/io/digibyte/core/hub/HubWebSocket.kt")
        val content = file.readText().lowercase()

        val dangerousPatterns = listOf("seed", "mnemonic", "phrase", "entropy", "privkey", "privatekey")
        val found = dangerousPatterns.filter { content.contains(it) }

        assertTrue(
            "HubWebSocket should never reference seed material. Found: $found",
            found.isEmpty()
        )
    }

    @Test
    fun `DigiIdManager never sends seed only address and signature`() {
        val file = findSource("core/src/main/java/io/digibyte/core/digiid/DigiIdManager.kt")
        val content = file.readText().lowercase()

        val dangerousPatterns = listOf("seed", "mnemonic", "phrase", "entropy", "privkey", "privatekey")
        val found = dangerousPatterns.filter { content.contains(it) }

        assertTrue(
            "DigiIdManager should never reference seed material. Found: $found",
            found.isEmpty()
        )
    }

    @Test
    fun `no Kotlin source file logs seed or mnemonic`() {
        val sourceRoot = findSourceRoot("core/src/main/java")
        val ktFiles = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()

        val violations = mutableListOf<String>()
        val logPattern = Regex("""Log\.[diwev]\([^)]*(?:seed|mnemonic|phrase|entropy|privkey)""", RegexOption.IGNORE_CASE)

        ktFiles.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { idx, line ->
                if (logPattern.containsMatchIn(line)) {
                    violations.add("${file.name}:${idx + 1}: $line")
                }
            }
        }

        assertTrue(
            "Log statements must never contain seed/mnemonic references:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `no source file puts seed in JSON for network transmission`() {
        val sourceRoot = findSourceRoot("core/src/main/java")
        val ktFiles = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()

        val violations = mutableListOf<String>()
        // Look for JSON.put("seed",...) or put("mnemonic",...) patterns
        val jsonPutPattern = Regex("""put\(\s*"(?:seed|mnemonic|phrase|entropy|privkey|privatekey|secret)""", RegexOption.IGNORE_CASE)

        ktFiles.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { idx, line ->
                if (jsonPutPattern.containsMatchIn(line)) {
                    violations.add("${file.name}:${idx + 1}: $line")
                }
            }
        }

        assertTrue(
            "No source file should put seed material into JSON:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `signMessage return value contains only address and base64 signature`() {
        // The JNI signMessage returns "address|base64sig" — verify the Kotlin side
        // only uses .split("|") and doesn't treat parts as key material
        val file = findSource("core/src/main/java/io/digibyte/core/digiid/DigiIdManager.kt")
        val content = file.readText()

        assertTrue("DigiIdManager should split signMessage result with |",
            content.contains(".split(\"|\")") || content.contains("split(\"|\""))
        assertFalse("DigiIdManager should never call NativeBridge.generateMnemonic",
            content.contains("generateMnemonic"))
        assertFalse("DigiIdManager should never call NativeBridge.createWallet",
            content.contains("createWallet"))
    }

    private fun findSource(path: String): File {
        val candidates = listOf(path, "../$path", "../../$path")
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: candidates.map { File(System.getProperty("user.dir"), it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$path not found")
    }

    private fun findSourceRoot(path: String): File {
        val candidates = listOf(path, "../$path", "../../$path")
        return candidates.map { File(it) }.firstOrNull { it.exists() && it.isDirectory }
            ?: candidates.map { File(System.getProperty("user.dir"), it) }.firstOrNull { it.exists() && it.isDirectory }
            ?: throw IllegalStateException("$path not found")
    }
}
